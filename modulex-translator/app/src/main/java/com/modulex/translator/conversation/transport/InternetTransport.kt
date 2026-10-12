package com.modulex.translator.conversation.transport

import android.content.Context
import com.modulex.translator.core.protocol.ProtocolLimits
import com.modulex.translator.core.security.Fingerprints
import com.modulex.translator.core.transport.FrameChannel
import com.modulex.translator.data.IceServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json as KJson

/** Events from the signaling relay (protocol v1, see signaling/README.md). */
sealed interface SignalEvent {
    data class Joined(val peers: Int) : SignalEvent
    data object PeerJoined : SignalEvent
    data object PeerLeft : SignalEvent
    data class Signal(val payload: JsonElement) : SignalEvent
    data class Error(val code: String) : SignalEvent
    data object Closed : SignalEvent
}

/** WebSocket client for the relay. Only wss:// is accepted. Carries SDP/ICE, never conversation text. */
class SignalingClient(private val baseUrl: String, private val room: String) {
    private val events = Channel<SignalEvent>(Channel.UNLIMITED)
    private var socket: WebSocket? = null

    init {
        require(baseUrl.startsWith("wss://")) { "signaling must use wss://" }
    }

    fun connect(): Channel<SignalEvent> {
        val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/v1/rooms/" + room).build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { KJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val ev = when (o["type"]?.jsonPrimitive?.content) {
                    "joined" -> SignalEvent.Joined(o["peers"]?.jsonPrimitive?.int ?: 1)
                    "peer-joined" -> SignalEvent.PeerJoined
                    "peer-left" -> SignalEvent.PeerLeft
                    "signal" -> o["payload"]?.let { SignalEvent.Signal(it) }
                    "error" -> SignalEvent.Error(o["code"]?.jsonPrimitive?.content ?: "unknown")
                    else -> null
                }
                ev?.let { events.trySend(it) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { events.trySend(SignalEvent.Closed) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.trySend(SignalEvent.Closed) }
        })
        return events
    }

    fun signal(payload: JsonElement) {
        socket?.send(buildJsonObject { put("type", "signal"); put("payload", payload) }.toString())
    }

    fun close() {
        socket?.close(1000, "bye")
        socket = null
    }
}

/**
 * WebRTC data channel between the two phones. Ordered and reliable; DTLS-encrypted end to end.
 * The DTLS certificate fingerprints from both SDPs are exposed as the channel fingerprints so
 * the session proofs (keyed by the QR/pairing secret) bind to them: a relay that substitutes
 * its own DTLS endpoints cannot produce valid proofs.
 */
class WebRtcTransport(context: Context) {
    companion object {
        @Volatile private var factory: PeerConnectionFactory? = null

        private fun factory(context: Context): PeerConnectionFactory = factory ?: synchronized(this) {
            factory ?: run {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
                PeerConnectionFactory.builder().createPeerConnectionFactory().also { factory = it }
            }
        }

        fun fingerprintOf(sdp: String): String? = sdp.lineSequence()
            .firstOrNull { it.startsWith("a=fingerprint:sha-256 ", ignoreCase = true) }
            ?.substringAfter(' ')?.trim()?.let(Fingerprints::normalise)
    }

    private val appContext = context.applicationContext

    /**
     * Negotiates one connection through [signaling]. The conversation host makes the offer.
     * Returns once the data channel is open.
     */
    suspend fun establish(isHost: Boolean, signaling: SignalingClient, iceServers: List<IceServer>, timeoutMs: Long = 45_000): FrameChannel {
        val events = signaling.connect()
        val rtcIce = iceServers.map {
            PeerConnection.IceServer.builder(it.url).apply {
                it.username?.let(::setUsername); it.credential?.let(::setPassword)
            }.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(rtcIce).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val incoming = Channel<ByteArray>(256)
        val open = CompletableDeferred<Unit>()
        val pendingRemoteIce = ArrayList<IceCandidate>()
        var remoteSet = false

        lateinit var pc: PeerConnection
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                signaling.signal(buildJsonObject {
                    put("t", "ice"); put("mid", c.sdpMid); put("idx", c.sdpMLineIndex); put("c", c.sdp)
                })
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED) {
                    open.completeExceptionally(IllegalStateException("ice_failed")); incoming.close()
                }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionReceivingChange(b: Boolean) = Unit
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) = Unit
            override fun onAddStream(s: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(s: org.webrtc.MediaStream) = Unit
            override fun onDataChannel(dc: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
        }
        pc = factory(appContext).createPeerConnection(config, observer) ?: error("peer connection unavailable")
        // Negotiated channel with a fixed id: both sides create it, no in-band announcement needed.
        val dc = pc.createDataChannel("mx", DataChannel.Init().apply { ordered = true; negotiated = true; id = 0 })
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) = Unit
            override fun onStateChange() {
                when (dc.state()) {
                    DataChannel.State.OPEN -> open.complete(Unit)
                    DataChannel.State.CLOSED -> incoming.close()
                    else -> Unit
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val n = buffer.data.remaining()
                if (n > ProtocolLimits.MAX_FRAME_BYTES) return
                val bytes = ByteArray(n).also { buffer.data.get(it) }
                incoming.trySend(bytes)
            }
        })

        suspend fun localDescription(offer: Boolean): SessionDescription {
            val created = CompletableDeferred<SessionDescription>()
            val obs = object : SimpleSdp() {
                override fun onCreateSuccess(sdp: SessionDescription) { created.complete(sdp) }
                override fun onCreateFailure(error: String?) { created.completeExceptionally(IllegalStateException(error)) }
            }
            if (offer) pc.createOffer(obs, MediaConstraints()) else pc.createAnswer(obs, MediaConstraints())
            val sdp = created.await()
            val set = CompletableDeferred<Unit>()
            pc.setLocalDescription(object : SimpleSdp() {
                override fun onSetSuccess() { set.complete(Unit) }
                override fun onSetFailure(error: String?) { set.completeExceptionally(IllegalStateException(error)) }
            }, sdp)
            set.await()
            return sdp
        }

        suspend fun setRemote(sdp: SessionDescription) {
            val set = CompletableDeferred<Unit>()
            pc.setRemoteDescription(object : SimpleSdp() {
                override fun onSetSuccess() { set.complete(Unit) }
                override fun onSetFailure(error: String?) { set.completeExceptionally(IllegalStateException(error)) }
            }, sdp)
            set.await()
            remoteSet = true
            pendingRemoteIce.forEach { pc.addIceCandidate(it) }
            pendingRemoteIce.clear()
        }

        var localSdp: String? = null
        var remoteSdp: String? = null
        try {
            withTimeout(timeoutMs) {
                var offered = false
                suspend fun offer() {
                    if (offered) return
                    offered = true
                    val sdp = localDescription(offer = true)
                    localSdp = sdp.description
                    signaling.signal(buildJsonObject { put("t", "offer"); put("sdp", sdp.description) })
                }
                while (!open.isCompleted) {
                    when (val ev = events.receive()) {
                        is SignalEvent.Joined -> if (isHost && ev.peers == 2) offer()
                        SignalEvent.PeerJoined -> if (isHost) offer()
                        is SignalEvent.Signal -> {
                            val o = ev.payload as? JsonObject ?: continue
                            when (o["t"]?.jsonPrimitive?.content) {
                                "offer" -> if (!isHost) {
                                    val sdp = o["sdp"]!!.jsonPrimitive.content
                                    remoteSdp = sdp
                                    setRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))
                                    val answer = localDescription(offer = false)
                                    localSdp = answer.description
                                    signaling.signal(buildJsonObject { put("t", "answer"); put("sdp", answer.description) })
                                }
                                "answer" -> if (isHost) {
                                    val sdp = o["sdp"]!!.jsonPrimitive.content
                                    remoteSdp = sdp
                                    setRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                                }
                                "ice" -> {
                                    val c = IceCandidate(
                                        o["mid"]?.jsonPrimitive?.content, o["idx"]?.jsonPrimitive?.int ?: 0, o["c"]!!.jsonPrimitive.content,
                                    )
                                    if (remoteSet) pc.addIceCandidate(c) else pendingRemoteIce += c
                                }
                            }
                        }
                        SignalEvent.PeerLeft -> { offered = false }
                        is SignalEvent.Error -> if (ev.code != "no_peer") error("signaling_${ev.code}")
                        SignalEvent.Closed -> error("signaling_closed")
                    }
                    if (open.isCompleted) break
                }
                open.await()
            }
        } catch (e: Exception) {
            dc.dispose(); pc.dispose(); signaling.close()
            throw e
        }
        val localFp = localSdp?.let(::fingerprintOf) ?: error("missing local DTLS fingerprint")
        val remoteFp = remoteSdp?.let(::fingerprintOf) ?: error("missing remote DTLS fingerprint")
        // The relay is no longer needed once the data channel is open.
        signaling.close()
        return DataChannelFrameChannel(dc, pc, incoming, localFp, remoteFp)
    }

    private open class SimpleSdp : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}

private class DataChannelFrameChannel(
    private val dc: DataChannel,
    private val pc: PeerConnection,
    private val incoming: Channel<ByteArray>,
    override val localFingerprint: String,
    override val remoteFingerprint: String,
) : FrameChannel {
    override val label = "Internet"
    @Volatile private var closed = false

    override suspend fun send(frame: ByteArray) {
        if (closed || !dc.send(DataChannel.Buffer(ByteBuffer.wrap(frame), true))) throw java.io.IOException("data channel closed")
    }

    override suspend fun receive(): ByteArray? = incoming.receiveCatching().getOrNull()

    override fun close() {
        if (closed) return
        closed = true
        incoming.close()
        runCatching { dc.close(); dc.dispose() }
        runCatching { pc.close(); pc.dispose() }
    }
}

/** Entry point used by the conversation controller for Internet mode. */
object InternetTransportFactory {
    fun create(context: Context) = InternetConnector(WebRtcTransport(context))
}

class InternetConnector(private val rtc: WebRtcTransport) {
    suspend fun connect(isHost: Boolean, signalingUrl: String, room: String, iceServers: List<IceServer>): FrameChannel =
        rtc.establish(isHost, SignalingClient(signalingUrl, room), iceServers)
}
