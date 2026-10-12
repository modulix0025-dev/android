package com.modulex.translator.core.conversation

import com.modulex.translator.core.protocol.InboundSequencer
import com.modulex.translator.core.protocol.MessageType
import com.modulex.translator.core.protocol.MessageValidator
import com.modulex.translator.core.protocol.OutboundReliableQueue
import com.modulex.translator.core.protocol.Validation
import com.modulex.translator.core.protocol.WireCodec
import com.modulex.translator.core.protocol.WireMessage
import com.modulex.translator.core.security.SecureIds
import com.modulex.translator.core.security.SessionAuth
import com.modulex.translator.core.transport.FrameChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class Role { HOST, GUEST }

data class ParticipantProfile(
    val displayName: String,
    /** Language this participant speaks. */
    val speaks: String,
    /** Language this participant wants to read/hear. */
    val wants: String,
)

data class ConversationTimings(
    val handshakeTimeoutMs: Long = 10_000,
    val pingIntervalMs: Long = 5_000,
    val idleTimeoutMs: Long = 15_000,
    val retryTickMs: Long = 1_000,
    val retryAfterMs: Long = 3_000,
    val maxAttempts: Int = 5,
)

sealed interface LinkState {
    data object Idle : LinkState
    data object Handshaking : LinkState
    data class Connected(val transport: String, val sas: String, val peer: ParticipantProfile) : LinkState
    data class Interrupted(val reason: String) : LinkState
    data class Ended(val byPeer: Boolean, val reason: String) : LinkState
}

sealed interface SessionEvent {
    data class PeerJoined(val peerId: String, val profile: ParticipantProfile, val sas: String, val rejoin: Boolean) : SessionEvent
    data class Received(val message: WireMessage) : SessionEvent
    data class PeerLanguagesChanged(val profile: ParticipantProfile) : SessionEvent
    data class Delivered(val messageId: String) : SessionEvent
    data class LatencyMeasured(val rttMs: Long) : SessionEvent
    data class ProtocolViolation(val reason: String) : SessionEvent
    data class AuthenticationFailed(val reason: String) : SessionEvent
}

/**
 * One conversation between two devices. Survives transport interruptions: [attach] is called once
 * per connection and returns when that connection ends; unacknowledged messages are replayed on
 * the next connection and duplicates are discarded by sequence number.
 */
class ConversationSession(
    val role: Role,
    val sessionId: String,
    private val secret: ByteArray,
    val localId: String,
    localProfile: ParticipantProfile,
    private val timings: ConversationTimings = ConversationTimings(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val lock = Mutex()
    private val outbound = OutboundReliableQueue(retryAfterMs = timings.retryAfterMs, maxAttempts = timings.maxAttempts)
    private val inbound = InboundSequencer()

    @Volatile var localProfile: ParticipantProfile = localProfile
        private set
    @Volatile var peerId: String? = null
        private set
    @Volatile var peerProfile: ParticipantProfile? = null
        private set
    @Volatile private var channel: FrameChannel? = null
    @Volatile private var ended = false

    val isEnded: Boolean get() = ended

    sealed interface AttachResult {
        data object ChannelClosed : AttachResult
        data class Rejected(val reason: String) : AttachResult
        data object SessionEnded : AttachResult
    }

    /**
     * Runs the authenticated handshake and then services [ch] until it closes.
     * The caller owns reconnection policy.
     */
    suspend fun attach(ch: FrameChannel): AttachResult {
        if (ended) { ch.close(); return AttachResult.SessionEnded }
        _state.value = LinkState.Handshaking
        val hs = withTimeoutOrNull(timings.handshakeTimeoutMs) {
            if (role == Role.GUEST) guestHandshake(ch) else hostHandshake(ch)
        } ?: HandshakeResult.Failed("handshake timed out")
        if (hs is HandshakeResult.Failed) {
            ch.close()
            _state.value = LinkState.Interrupted(hs.reason)
            return AttachResult.Rejected(hs.reason)
        }
        hs as HandshakeResult.Ok
        channel = ch
        val sas = if (role == Role.HOST) {
            SessionAuth.shortAuthString(secret, sessionId, ch.localFingerprint, ch.remoteFingerprint)
        } else {
            SessionAuth.shortAuthString(secret, sessionId, ch.remoteFingerprint, ch.localFingerprint)
        }
        _state.value = LinkState.Connected(ch.label, sas, hs.peer)
        _events.emit(SessionEvent.PeerJoined(peerId!!, hs.peer, sas, hs.rejoin))

        // Peer told us what it already has; replay the rest in order.
        lock.withLock {
            outbound.acknowledge(hs.peerAckSeq).forEach { _events.emit(SessionEvent.Delivered(it)) }
            for (m in outbound.replayAll(clock())) ch.send(WireCodec.encode(m))
        }

        val result = try {
            serve(ch)
        } finally {
            if (channel === ch) channel = null
            ch.close()
        }
        if (!ended) _state.value = LinkState.Interrupted("connection lost")
        return result
    }

    private sealed interface HandshakeResult {
        data class Ok(val peer: ParticipantProfile, val peerAckSeq: Long, val rejoin: Boolean) : HandshakeResult
        data class Failed(val reason: String) : HandshakeResult
    }

    private suspend fun guestHandshake(ch: FrameChannel): HandshakeResult {
        val hello = control(MessageType.HELLO).copy(
            displayName = localProfile.displayName,
            sourceLang = localProfile.speaks,
            targetLang = localProfile.wants,
            proof = SessionAuth.joinProof(secret, sessionId, localId, ch.localFingerprint, ch.remoteFingerprint),
            ackSeq = inbound.highestContiguous,
        )
        ch.send(WireCodec.encode(hello))
        val frame = ch.receive() ?: return HandshakeResult.Failed("closed during handshake")
        val m = WireCodec.decode(frame) ?: return HandshakeResult.Failed("malformed handshake")
        if (m.type == MessageType.ERROR) return fail("host refused: ${m.errorCode}")
        if (m.type != MessageType.HELLO_ACK) return HandshakeResult.Failed("unexpected ${m.type}")
        val v = MessageValidator.validate(m, sessionId, peerId)
        if (v is Validation.Rejected) return fail(v.reason)
        val expected = SessionAuth.hostProof(secret, sessionId, m.senderId, localId, ch.localFingerprint, ch.remoteFingerprint)
        if (!SessionAuth.verify(expected, m.proof)) return fail("host could not prove it knows the session secret")
        val rejoin = peerId != null
        peerId = m.senderId
        val profile = ParticipantProfile(m.displayName ?: "", m.sourceLang ?: "", m.targetLang ?: "")
        peerProfile = profile
        return HandshakeResult.Ok(profile, m.ackSeq ?: 0, rejoin)
    }

    private suspend fun hostHandshake(ch: FrameChannel): HandshakeResult {
        val frame = ch.receive() ?: return HandshakeResult.Failed("closed during handshake")
        val m = WireCodec.decode(frame) ?: return HandshakeResult.Failed("malformed handshake")
        if (m.type != MessageType.HELLO) return HandshakeResult.Failed("expected hello, got ${m.type}")
        val v = MessageValidator.validate(m, sessionId, peerId)
        if (v is Validation.Rejected) {
            sendError(ch, "rejected")
            return fail(v.reason)
        }
        val expected = SessionAuth.joinProof(secret, sessionId, m.senderId, ch.remoteFingerprint, ch.localFingerprint)
        if (!SessionAuth.verify(expected, m.proof)) {
            sendError(ch, "auth_failed")
            return fail("guest could not prove it knows the session secret")
        }
        val rejoin = peerId != null
        peerId = m.senderId
        val profile = ParticipantProfile(m.displayName ?: "", m.sourceLang!!, m.targetLang!!)
        peerProfile = profile
        val ack = control(MessageType.HELLO_ACK).copy(
            displayName = localProfile.displayName,
            sourceLang = localProfile.speaks,
            targetLang = localProfile.wants,
            proof = SessionAuth.hostProof(secret, sessionId, localId, m.senderId, ch.remoteFingerprint, ch.localFingerprint),
            ackSeq = inbound.highestContiguous,
        )
        ch.send(WireCodec.encode(ack))
        return HandshakeResult.Ok(profile, m.ackSeq ?: 0, rejoin)
    }

    private suspend fun fail(reason: String): HandshakeResult.Failed {
        _events.emit(SessionEvent.AuthenticationFailed(reason))
        return HandshakeResult.Failed(reason)
    }

    private suspend fun sendError(ch: FrameChannel, code: String) {
        runCatching { ch.send(WireCodec.encode(control(MessageType.ERROR).copy(errorCode = code))) }
    }

    private suspend fun serve(ch: FrameChannel): AttachResult = coroutineScope {
        val lastInbound = java.util.concurrent.atomic.AtomicLong(clock())
        val outstandingPings = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val done = CompletableDeferred<AttachResult>()
        var violations = 0

        val reader = launch {
            while (true) {
                val frame = ch.receive() ?: break
                lastInbound.set(clock())
                val m = WireCodec.decode(frame)
                if (m == null) {
                    _events.emit(SessionEvent.ProtocolViolation("malformed frame"))
                    if (++violations > 5) break else continue
                }
                val v = MessageValidator.validate(m, sessionId, peerId)
                if (v is Validation.Rejected) {
                    _events.emit(SessionEvent.ProtocolViolation(v.reason))
                    if (++violations > 5) break else continue
                }
                when (m.type) {
                    MessageType.UTTERANCE, MessageType.LANGUAGE_UPDATE, MessageType.BYE -> {
                        var byeSeen = false
                        val outcome = lock.withLock { inbound.accept(m) }
                        when (outcome) {
                            is InboundSequencer.Outcome.Deliver -> for (d in outcome.messages) {
                                when (d.type) {
                                    MessageType.UTTERANCE -> _events.emit(SessionEvent.Received(d))
                                    MessageType.LANGUAGE_UPDATE -> {
                                        val p = ParticipantProfile(peerProfile?.displayName ?: "", d.sourceLang!!, d.targetLang!!)
                                        peerProfile = p
                                        _events.emit(SessionEvent.PeerLanguagesChanged(p))
                                    }
                                    else -> byeSeen = true
                                }
                            }
                            InboundSequencer.Outcome.Overflow -> {
                                _events.emit(SessionEvent.ProtocolViolation("sequence overflow"))
                                break
                            }
                            else -> Unit
                        }
                        sendNow(ch, control(MessageType.ACK).copy(ackSeq = inbound.highestContiguous))
                        if (byeSeen) {
                            ended = true
                            _state.value = LinkState.Ended(byPeer = true, reason = "peer left")
                            done.complete(AttachResult.SessionEnded)
                            break
                        }
                    }
                    MessageType.ACK -> lock.withLock { outbound.acknowledge(m.ackSeq!!) }
                        .forEach { _events.emit(SessionEvent.Delivered(it)) }
                    MessageType.PING -> sendNow(ch, control(MessageType.PONG).copy(nonce = m.nonce))
                    MessageType.PONG -> outstandingPings.remove(m.nonce!!)?.let {
                        _events.emit(SessionEvent.LatencyMeasured(clock() - it))
                    }
                    MessageType.ERROR -> {
                        _events.emit(SessionEvent.ProtocolViolation("peer error: ${m.errorCode}"))
                        break
                    }
                    MessageType.HELLO, MessageType.HELLO_ACK -> _events.emit(SessionEvent.ProtocolViolation("unexpected ${m.type}"))
                }
            }
            done.complete(if (ended) AttachResult.SessionEnded else AttachResult.ChannelClosed)
        }

        val heartbeat = launch {
            while (true) {
                delay(timings.pingIntervalMs)
                if (clock() - lastInbound.get() > timings.idleTimeoutMs) {
                    done.complete(AttachResult.ChannelClosed)
                    break
                }
                val nonce = SecureIds.newId()
                outstandingPings[nonce] = clock()
                if (outstandingPings.size > 8) outstandingPings.remove(outstandingPings.keys.first())
                if (!sendNow(ch, control(MessageType.PING).copy(nonce = nonce))) {
                    done.complete(AttachResult.ChannelClosed); break
                }
            }
        }

        val retrier = launch {
            while (true) {
                delay(timings.retryTickMs)
                val decision = lock.withLock { outbound.due(clock()) }
                when (decision) {
                    is OutboundReliableQueue.RetryDecision.Resend -> for (m in decision.messages) {
                        if (!sendNow(ch, m)) { done.complete(AttachResult.ChannelClosed); break }
                    }
                    is OutboundReliableQueue.RetryDecision.GaveUp -> {
                        done.complete(AttachResult.ChannelClosed); break
                    }
                }
            }
        }

        try {
            done.await()
        } finally {
            reader.cancel(); heartbeat.cancel(); retrier.cancel()
            // Closing unblocks a reader stuck in blocking socket I/O.
            ch.close()
        }
    }

    private suspend fun sendNow(ch: FrameChannel, m: WireMessage): Boolean = try {
        ch.send(WireCodec.encode(m)); true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private fun control(type: MessageType) = WireMessage(
        type = type, sessionId = sessionId, messageId = SecureIds.newId(), senderId = localId, timestamp = clock(),
    )

    /**
     * Queues a translated utterance for reliable delivery. Returns its message id, or null when the
     * outbound queue is full (the UI should tell the user the peer is unreachable).
     */
    suspend fun sendUtterance(translated: String, sourceLang: String, targetLang: String, original: String?): String? =
        enqueueReliable { seq ->
            control(MessageType.UTTERANCE).copy(
                seq = seq, translatedText = translated, sourceLang = sourceLang, targetLang = targetLang, originalText = original,
            )
        }

    suspend fun updateLanguages(profile: ParticipantProfile): String? {
        localProfile = profile
        return enqueueReliable { seq ->
            control(MessageType.LANGUAGE_UPDATE).copy(seq = seq, sourceLang = profile.speaks, targetLang = profile.wants)
        }
    }

    /** Ends the session for both sides. Best effort delivery of BYE if currently connected. */
    suspend fun end() {
        if (ended) return
        enqueueReliable { seq -> control(MessageType.BYE).copy(seq = seq) }
        ended = true
        delay(200) // give the BYE a moment on a live connection
        channel?.close()
        _state.value = LinkState.Ended(byPeer = false, reason = "ended locally")
    }

    private suspend fun enqueueReliable(build: (Long) -> WireMessage): String? {
        if (ended) return null
        val m = lock.withLock { outbound.enqueue(build, clock()) } ?: return null
        channel?.let { sendNow(it, m) }
        return m.messageId
    }

    val pendingOutbound: Int get() = outbound.size
}
