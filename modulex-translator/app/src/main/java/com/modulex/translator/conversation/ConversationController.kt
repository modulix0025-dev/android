package com.modulex.translator.conversation

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.modulex.translator.audio.AudioCapture
import com.modulex.translator.audio.ListenMode
import com.modulex.translator.audio.VoiceEvent
import com.modulex.translator.audio.VoicePipeline
import com.modulex.translator.conversation.transport.BluetoothTransport
import com.modulex.translator.conversation.transport.InternetTransportFactory
import com.modulex.translator.conversation.transport.LocalNetwork
import com.modulex.translator.conversation.transport.PeerTls
import com.modulex.translator.conversation.transport.TcpGuest
import com.modulex.translator.conversation.transport.TcpHost
import com.modulex.translator.conversation.transport.WifiDirect
import com.modulex.translator.core.conversation.ConversationSession
import com.modulex.translator.core.conversation.LinkState
import com.modulex.translator.core.conversation.ParticipantProfile
import com.modulex.translator.core.conversation.Role
import com.modulex.translator.core.conversation.SessionEvent
import com.modulex.translator.core.language.LanguageRegistry
import com.modulex.translator.core.security.PairingPayload
import com.modulex.translator.core.security.SecureIds
import com.modulex.translator.core.security.SessionAuth
import com.modulex.translator.core.security.TransportKind
import com.modulex.translator.core.transport.FrameChannel
import com.modulex.translator.core.translation.TranslationResult
import com.modulex.translator.data.HistoryEntry
import com.modulex.translator.data.HistoryKind
import com.modulex.translator.data.HistoryRepository
import com.modulex.translator.data.SettingsRepository
import com.modulex.translator.engine.EngineHub
import com.modulex.translator.security.DeviceIdentity
import com.modulex.translator.tts.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

enum class Delivery { PENDING, DELIVERED, FAILED, RECEIVED }

data class ChatMessage(
    val id: String,
    val mine: Boolean,
    val original: String?,
    val translated: String,
    val sourceLang: String,
    val targetLang: String,
    val timestamp: Long,
    val delivery: Delivery,
    /** Debug metric: end of phrase (or send tap) to message sent, in ms. */
    val pipelineMs: Long? = null,
)

sealed interface ConvPhase {
    data object Idle : ConvPhase
    data object Preparing : ConvPhase
    data class WaitingForPeer(val payload: PairingPayload, val pairingCode: String?) : ConvPhase
    data object Connecting : ConvPhase
    data class Connected(val sas: String, val transport: String, val sasConfirmed: Boolean) : ConvPhase
    data class Reconnecting(val attempt: Int) : ConvPhase
    data class Ended(val byPeer: Boolean, val reason: String? = null) : ConvPhase
    data class Failed(val code: String) : ConvPhase
}

enum class MicState { OFF, LISTENING, HEARING_SPEECH, PROCESSING, PAUSED_FOR_PLAYBACK }
enum class InputMode { HANDS_FREE, PUSH_TO_TALK, TEXT }

data class ConvUiState(
    val phase: ConvPhase = ConvPhase.Idle,
    val role: Role? = null,
    val transport: TransportKind? = null,
    val me: ParticipantProfile = ParticipantProfile("", "ar", "en"),
    val peer: ParticipantProfile? = null,
    val messages: List<ChatMessage> = emptyList(),
    val latencyMs: Long? = null,
    val mic: MicState = MicState.OFF,
    val partial: String? = null,
    val inputMode: InputMode = InputMode.HANDS_FREE,
    val muted: Boolean = false,
    val speakAloud: Boolean = true,
    val showOriginal: Boolean = true,
    val shareOriginal: Boolean = true,
    val notice: String? = null,
)

/**
 * Runs one two-device conversation at a time: pairing, transport, reconnection, speech capture,
 * translation (performed by the *sender* into the reader's language) and delivery.
 */
class ConversationController(
    private val context: Context,
    private val hub: EngineHub,
    private val voice: VoicePipeline,
    private val capture: AudioCapture,
    private val speech: SpeechOutput,
    private val identity: DeviceIdentity,
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ConvUiState())
    val state: StateFlow<ConvUiState> = _state.asStateFlow()

    private var session: ConversationSession? = null
    private var linkJob: Job? = null
    private var eventsJob: Job? = null
    private var micJob: Job? = null
    private val resources = ArrayList<Closeable>()
    private val wifiDirect by lazy { WifiDirect(context) }
    private val tls by lazy { PeerTls(identity) }
    private val talking = MutableStateFlow(false)
    @Volatile private var playbackActive = false

    val active: Boolean get() = session != null && _state.value.phase !is ConvPhase.Ended && _state.value.phase !is ConvPhase.Failed

    // ------------------------------------------------------------------ start

    fun host(transport: TransportKind, me: ParticipantProfile) {
        reset()
        val s = settings.settings.value
        _state.value = ConvUiState(
            phase = ConvPhase.Preparing, role = Role.HOST, transport = transport, me = me,
            speakAloud = s.speakIncoming, shareOriginal = s.shareOriginalInConversation,
        )
        startForeground()
        linkJob = scope.launch {
            try {
                when (transport) {
                    TransportKind.INTERNET -> hostInternet(me)
                    else -> hostLocal(transport, me)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(errorCode(e, transport))
            }
        }
    }

    fun join(payload: PairingPayload, me: ParticipantProfile) {
        reset()
        val s = settings.settings.value
        _state.value = ConvUiState(
            phase = ConvPhase.Connecting, role = Role.GUEST, transport = payload.transport, me = me,
            speakAloud = s.speakIncoming, shareOriginal = s.shareOriginalInConversation,
        )
        startForeground()
        val sess = ConversationSession(Role.GUEST, payload.sessionId, payload.secret, SecureIds.newId(), me)
        bind(sess)
        linkJob = scope.launch {
            try {
                if (payload.transport == TransportKind.WIFI_DIRECT) {
                    wifiDirect.joinGroup(payload.p2pNetworkName ?: error("p2p_missing"), payload.p2pPassphrase ?: error("p2p_missing"))
                }
                guestLoop(sess) { connectGuest(payload) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(errorCode(e, payload.transport))
            }
        }
    }

    /** Internet join by typed pairing code (no QR). */
    fun joinWithCode(code: String, me: ParticipantProfile) {
        val url = settings.settings.value.signalingUrl
        if (!url.startsWith("wss://")) { _state.update { it.copy(phase = ConvPhase.Failed("net_not_configured")) }; return }
        val (secret, room) = runCatching { SessionAuth.deriveFromPairingCode(code) }.getOrElse {
            _state.update { it.copy(phase = ConvPhase.Failed("invalid_code")) }; return
        }
        join(PairingPayload(room, secret, TransportKind.INTERNET, "", signalingUrl = url, room = room,
            expiresAtMillis = System.currentTimeMillis() + 60_000), me)
    }

    private suspend fun hostLocal(transport: TransportKind, me: ParticipantProfile) {
        val sessionId = SecureIds.newId()
        val secret = SecureIds.randomBytes(32)
        val sess = ConversationSession(Role.HOST, sessionId, secret, SecureIds.newId(), me)
        bind(sess)
        val fp = identity.fingerprint
        val expires = System.currentTimeMillis() + 15 * 60_000
        val accept: suspend () -> FrameChannel
        val payload: PairingPayload
        when (transport) {
            TransportKind.LAN -> {
                val ip = LocalNetwork.localIpv4() ?: error("no_network")
                val host = TcpHost(tls, "LAN", ip).also { resources += it }
                payload = PairingPayload(sessionId, secret, transport, me.displayName, fp, ip.hostAddress, host.port, expiresAtMillis = expires)
                accept = host::accept
            }
            TransportKind.WIFI_DIRECT -> {
                if (!wifiDirect.available) error("p2p_unavailable")
                val name = "DIRECT-mx-" + SecureIds.newId().take(6)
                val pass = SecureIds.newId().take(16)
                val info = wifiDirect.createGroup(name, pass)
                resources += Closeable { wifiDirect.removeGroup() }
                val host = TcpHost(tls, "Wi-Fi Direct", info.groupOwnerAddress).also { resources += it }
                payload = PairingPayload(sessionId, secret, transport, me.displayName, fp, info.groupOwnerAddress.hostAddress, host.port,
                    p2pNetworkName = name, p2pPassphrase = pass, expiresAtMillis = expires)
                accept = host::accept
            }
            TransportKind.BLUETOOTH -> {
                val bt = BluetoothTransport(context, tls)
                if (!bt.available) error("bt_unavailable")
                if (!bt.enabled) error("bt_off")
                val host = bt.Host(sessionId).also { resources += it }
                host.awaitAdvertising()
                payload = PairingPayload(sessionId, secret, transport, me.displayName, fp, expiresAtMillis = expires)
                accept = host::accept
            }
            TransportKind.INTERNET -> error("unreachable")
        }
        _state.update { it.copy(phase = ConvPhase.WaitingForPeer(payload, null)) }
        // The host keeps listening: the guest reconnects to the same listener after interruptions.
        while (scope.isActive && !sess.isEnded) {
            val ch = accept()
            val r = sess.attach(ch)
            if (r is ConversationSession.AttachResult.Rejected) {
                _state.update { it.copy(notice = "auth_failed") }
            }
            if (!sess.isEnded && sess.peerId != null) _state.update { it.copy(phase = ConvPhase.Reconnecting(1)) }
        }
    }

    private suspend fun hostInternet(me: ParticipantProfile) {
        val s = settings.settings.value
        if (!s.signalingUrl.startsWith("wss://")) error("net_not_configured")
        val code = SecureIds.newPairingCode()
        val (secret, room) = SessionAuth.deriveFromPairingCode(code)
        val sess = ConversationSession(Role.HOST, room, secret, SecureIds.newId(), me)
        bind(sess)
        val payload = PairingPayload(room, secret, TransportKind.INTERNET, me.displayName, signalingUrl = s.signalingUrl, room = room,
            expiresAtMillis = System.currentTimeMillis() + 15 * 60_000)
        _state.update { it.copy(phase = ConvPhase.WaitingForPeer(payload, code)) }
        val webrtc = InternetTransportFactory.create(context)
        var attempt = 0
        while (scope.isActive && !sess.isEnded) {
            val ch = runCatching { webrtc.connect(isHost = true, s.signalingUrl, room, s.iceServers) }.getOrNull()
            if (ch == null) {
                attempt++
                if (sess.peerId != null) _state.update { it.copy(phase = ConvPhase.Reconnecting(attempt)) }
                delay(backoff(attempt)); continue
            }
            attempt = 0
            val r = sess.attach(ch)
            if (r is ConversationSession.AttachResult.Rejected) _state.update { it.copy(notice = "auth_failed") }
        }
    }

    private suspend fun connectGuest(p: PairingPayload): FrameChannel = when (p.transport) {
        TransportKind.LAN, TransportKind.WIFI_DIRECT ->
            TcpGuest.connect(tls, p.address!!, p.port!!, p.hostFingerprint!!, if (p.transport == TransportKind.LAN) "LAN" else "Wi-Fi Direct")
        TransportKind.BLUETOOTH -> {
            val bt = BluetoothTransport(context, tls)
            if (!bt.enabled) error("bt_off")
            bt.connect(p.sessionId, p.hostFingerprint!!)
        }
        TransportKind.INTERNET -> {
            val s = settings.settings.value
            InternetTransportFactory.create(context).connect(isHost = false, p.signalingUrl!!, p.room!!, s.iceServers)
        }
    }

    /** Connects, serves, and reconnects with backoff until the session ends or auth fails. */
    private suspend fun guestLoop(sess: ConversationSession, connect: suspend () -> FrameChannel) {
        var attempt = 0
        while (scope.isActive && !sess.isEnded) {
            val ch = try {
                connect()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= 12 && sess.peerId == null) throw e // never connected: give up with the real error
                _state.update { it.copy(phase = if (sess.peerId == null) ConvPhase.Connecting else ConvPhase.Reconnecting(attempt)) }
                delay(backoff(attempt))
                continue
            }
            attempt = 0
            when (val r = sess.attach(ch)) {
                is ConversationSession.AttachResult.Rejected -> { fail("auth_failed"); return }
                ConversationSession.AttachResult.SessionEnded -> return
                ConversationSession.AttachResult.ChannelClosed -> _state.update { it.copy(phase = ConvPhase.Reconnecting(1)) }
            }
        }
    }

    private fun backoff(attempt: Int) = minOf(10_000L, 500L shl minOf(attempt, 5))

    private fun bind(sess: ConversationSession) {
        session = sess
        eventsJob?.cancel()
        eventsJob = scope.launch {
            launch {
                sess.state.collect { ls ->
                    when (ls) {
                        is LinkState.Connected -> _state.update { st ->
                            val confirmed = (st.phase as? ConvPhase.Connected)?.sasConfirmed ?: false
                            st.copy(phase = ConvPhase.Connected(ls.sas, ls.transport, confirmed), peer = ls.peer)
                        }
                        is LinkState.Ended -> { onEnded(ls.byPeer) }
                        else -> Unit
                    }
                }
            }
            sess.events.collect { ev -> handle(ev) }
        }
    }

    private suspend fun handle(ev: SessionEvent) {
        when (ev) {
            is SessionEvent.PeerJoined -> {
                _state.update { it.copy(peer = ev.profile) }
                if (ev.rejoin) {
                    _state.update { st ->
                        val p = st.phase
                        st.copy(phase = ConvPhase.Connected(ev.sas, (p as? ConvPhase.Connected)?.transport ?: "", sasConfirmed = true))
                    }
                }
                restartMic()
            }
            is SessionEvent.Received -> onIncoming(ev.message)
            is SessionEvent.Delivered -> _state.update { st ->
                st.copy(messages = st.messages.map { if (it.id == ev.messageId) it.copy(delivery = Delivery.DELIVERED) else it })
            }
            is SessionEvent.LatencyMeasured -> _state.update { it.copy(latencyMs = ev.rttMs / 2) }
            is SessionEvent.PeerLanguagesChanged -> _state.update { it.copy(peer = ev.profile) }
            is SessionEvent.AuthenticationFailed -> _state.update { it.copy(notice = "auth_failed") }
            is SessionEvent.ProtocolViolation -> Unit // logged in diagnostics only
        }
    }

    private suspend fun onIncoming(m: com.modulex.translator.core.protocol.WireMessage) {
        val me = _state.value.me
        var text = m.translatedText!!
        var lang = m.targetLang!!
        // If the sender translated into a language I no longer read, translate locally from the original.
        if (lang != me.wants && m.originalText != null) {
            val src = LanguageRegistry.get(m.sourceLang!!)
            val tgt = LanguageRegistry.get(me.wants)
            if (src != null && tgt != null) {
                (hub.translate(m.originalText!!, src, tgt) as? TranslationResult.Success)?.let { text = it.text; lang = tgt.code }
            }
        }
        val msg = ChatMessage(m.messageId, false, m.originalText, text, m.sourceLang!!, lang, m.timestamp, Delivery.RECEIVED)
        _state.update { it.copy(messages = (it.messages + msg).takeLast(500)) }
        save(msg)
        if (_state.value.speakAloud) {
            // Half-duplex: pause listening while the translation is spoken so the mic doesn't capture it.
            playbackActive = true
            _state.update { it.copy(mic = if (micJob?.isActive == true) MicState.PAUSED_FOR_PLAYBACK else it.mic) }
            try { speech.speak(text, lang) } finally { playbackActive = false }
            if (micJob?.isActive == true) _state.update { it.copy(mic = MicState.LISTENING) }
        }
    }

    // ------------------------------------------------------------------ sending

    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        scope.launch { sendMine(t, System.nanoTime() / 1_000_000) }
    }

    private suspend fun sendMine(text: String, startedAt: Long) {
        val sess = session ?: return
        val st = _state.value
        val peer = st.peer ?: return
        val src = LanguageRegistry.get(st.me.speaks) ?: return
        val tgt = LanguageRegistry.get(peer.wants) ?: return
        val translated = if (src.code == tgt.code) text else {
            when (val r = hub.translate(text, src, tgt)) {
                is TranslationResult.Success -> r.text
                else -> { _state.update { it.copy(notice = "translation_failed") }; return }
            }
        }
        val id = sess.sendUtterance(translated, src.code, tgt.code, if (st.shareOriginal) text else null)
        val msg = ChatMessage(id ?: SecureIds.newId(), true, text, translated, src.code, tgt.code, System.currentTimeMillis(),
            if (id == null) Delivery.FAILED else Delivery.PENDING, System.nanoTime() / 1_000_000 - startedAt)
        _state.update { it.copy(messages = (it.messages + msg).takeLast(500)) }
        save(msg)
    }

    private fun save(m: ChatMessage) {
        if (!settings.settings.value.saveConversationHistory) return
        scope.launch {
            history.add(HistoryEntry(timestamp = m.timestamp, kind = HistoryKind.CONVERSATION, sourceLang = m.sourceLang, targetLang = m.targetLang,
                sourceText = m.original ?: "", translatedText = m.translated, sessionId = session?.sessionId, incoming = !m.mine))
        }
    }

    // ------------------------------------------------------------------ microphone

    fun setInputMode(mode: InputMode) { _state.update { it.copy(inputMode = mode) }; restartMic() }
    fun setMuted(muted: Boolean) { _state.update { it.copy(muted = muted) }; restartMic() }
    fun setPushToTalk(held: Boolean) { talking.value = held }
    fun setSpeakAloud(on: Boolean) { _state.update { it.copy(speakAloud = on) }; if (!on) speech.stop() }
    fun setShowOriginal(on: Boolean) = _state.update { it.copy(showOriginal = on) }
    fun setShareOriginal(on: Boolean) = _state.update { it.copy(shareOriginal = on) }

    private fun restartMic() {
        micJob?.cancel()
        val st = _state.value
        if (st.muted || st.inputMode == InputMode.TEXT || st.phase !is ConvPhase.Connected || !capture.hasPermission()) {
            _state.update { it.copy(mic = MicState.OFF, partial = null) }
            return
        }
        val whisper = LanguageRegistry.get(st.me.speaks)?.whisperCode ?: run { _state.update { it.copy(mic = MicState.OFF) }; return }
        val mode = if (st.inputMode == InputMode.PUSH_TO_TALK) ListenMode.PUSH_TO_TALK else ListenMode.AUTO
        micJob = scope.launch {
            _state.update { it.copy(mic = MicState.LISTENING) }
            voice.listen(whisper, mode, if (mode == ListenMode.PUSH_TO_TALK) talking else null).collect { ev ->
                if (playbackActive) return@collect
                when (ev) {
                    VoiceEvent.SpeechStarted -> _state.update { it.copy(mic = MicState.HEARING_SPEECH) }
                    is VoiceEvent.Partial -> _state.update { it.copy(partial = ev.text) }
                    is VoiceEvent.Final -> {
                        _state.update { it.copy(mic = MicState.PROCESSING, partial = ev.text) }
                        sendMine(ev.text, ev.phraseEndAt)
                        _state.update { it.copy(mic = MicState.LISTENING, partial = null) }
                    }
                    VoiceEvent.NoSpeech -> _state.update { it.copy(mic = MicState.LISTENING, partial = null) }
                    is VoiceEvent.Failed -> _state.update { it.copy(notice = ev.reason) }
                    is VoiceEvent.Level, is VoiceEvent.Recording -> Unit
                }
            }
        }
    }

    // ------------------------------------------------------------------ control

    fun updateMyLanguages(speaks: String, wants: String) {
        val me = _state.value.me.copy(speaks = speaks, wants = wants)
        _state.update { it.copy(me = me) }
        scope.launch { session?.updateLanguages(me) }
        restartMic()
    }

    fun confirmSas(match: Boolean) {
        if (match) {
            _state.update { st -> (st.phase as? ConvPhase.Connected)?.let { st.copy(phase = it.copy(sasConfirmed = true)) } ?: st }
        } else {
            _state.update { it.copy(notice = "sas_mismatch") }
            end()
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun end() {
        val sess = session
        scope.launch {
            withContext(NonCancellable) { runCatching { sess?.end() } }
            onEnded(byPeer = false)
        }
    }

    private fun onEnded(byPeer: Boolean) {
        micJob?.cancel()
        speech.stop()
        linkJob?.cancel()
        resources.forEach { runCatching { it.close() } }
        resources.clear()
        _state.update { it.copy(phase = ConvPhase.Ended(byPeer), mic = MicState.OFF, partial = null) }
        stopForeground()
    }

    private fun fail(code: String) {
        micJob?.cancel()
        resources.forEach { runCatching { it.close() } }
        resources.clear()
        _state.update { it.copy(phase = ConvPhase.Failed(code), mic = MicState.OFF) }
        stopForeground()
    }

    /** Clears a finished conversation from the screen. */
    fun reset() {
        micJob?.cancel(); linkJob?.cancel(); eventsJob?.cancel()
        resources.forEach { runCatching { it.close() } }
        resources.clear()
        session = null
        _state.value = ConvUiState()
    }

    private fun errorCode(e: Exception, t: TransportKind): String = when (e.message) {
        "no_network", "p2p_unavailable", "bt_unavailable", "bt_off", "net_not_configured", "p2p_missing" -> e.message!!
        else -> when {
            e.message?.startsWith("signaling") == true -> "signaling_failed"
            e is SecurityException -> "permission_denied"
            t == TransportKind.INTERNET -> "signaling_failed"
            else -> "connect_failed"
        }
    }

    private fun startForeground() {
        ContextCompat.startForegroundService(context, Intent(context, ConversationService::class.java))
    }

    private fun stopForeground() {
        context.stopService(Intent(context, ConversationService::class.java))
    }
}
