package com.modulex.translator.ui.voice

import androidx.compose.foundation.border
import android.Manifest
import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.modulex.translator.R
import com.modulex.translator.audio.ListenMode
import com.modulex.translator.audio.VoiceEvent
import com.modulex.translator.container
import com.modulex.translator.core.language.Language
import com.modulex.translator.core.language.LanguageRegistry
import com.modulex.translator.core.translation.TranslationError
import com.modulex.translator.core.translation.TranslationResult
import com.modulex.translator.data.HistoryEntry
import com.modulex.translator.data.HistoryKind
import com.modulex.translator.engine.EngineState
import com.modulex.translator.ui.components.Banner
import com.modulex.translator.ui.components.BannerKind
import com.modulex.translator.ui.components.LanguageBar
import com.modulex.translator.ui.components.LanguagePickerDialog
import com.modulex.translator.ui.components.LanguageText
import com.modulex.translator.ui.components.PermissionGate
import com.modulex.translator.ui.components.ScreenScaffold
import com.modulex.translator.ui.components.messageRes
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VoiceTurn(
    val id: Long, val source: Language, val target: Language, val heard: String, val translated: String?,
    val error: TranslationError? = null,
    /** End of the spoken phrase -> translation shown, ms (measured, not estimated). */
    val latencyMs: Long? = null,
)

data class VoiceUiState(
    val source: Language? = LanguageRegistry.require("ar"),
    val target: Language = LanguageRegistry.require("en"),
    val listening: Boolean = false,
    val hearing: Boolean = false,
    val level: Float = 0f,
    val partial: String? = null,
    val turns: List<VoiceTurn> = emptyList(),
    val autoSpeak: Boolean = true,
    val notice: Int? = null,
)

class VoiceViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val _state = MutableStateFlow(VoiceUiState(
        source = LanguageRegistry.get(c.settings.settings.value.lastSource),
        target = LanguageRegistry.get(c.settings.settings.value.lastTarget) ?: LanguageRegistry.require("en"),
    ))
    val state = _state.asStateFlow()
    val speechEngine = c.engines.speechState
    private var job: Job? = null

    fun setSource(l: Language?) = _state.update { it.copy(source = l) }
    fun setTarget(l: Language) = _state.update { it.copy(target = l) }
    fun swap() = _state.update { s -> s.source?.let { s.copy(source = s.target, target = it) } ?: s }
    fun setAutoSpeak(on: Boolean) = _state.update { it.copy(autoSpeak = on) }

    fun toggle() { if (_state.value.listening) stop() else start() }

    private fun start() {
        val s = _state.value
        val whisper = s.source?.whisperCode ?: "auto"
        c.speech.stop()
        _state.update { it.copy(listening = true, notice = null) }
        job = viewModelScope.launch {
            c.voice.listen(whisper, ListenMode.AUTO).collect { ev ->
                when (ev) {
                    is VoiceEvent.Level -> _state.update { it.copy(level = ev.rms) }
                    VoiceEvent.SpeechStarted -> _state.update { it.copy(hearing = true) }
                    is VoiceEvent.Partial -> _state.update { it.copy(partial = ev.text) }
                    VoiceEvent.NoSpeech -> _state.update { it.copy(hearing = false, partial = null, notice = R.string.voice_no_speech) }
                    is VoiceEvent.Failed -> _state.update {
                        it.copy(listening = false, notice = if (ev.reason == "speech_unavailable") R.string.voice_unavailable_body else R.string.err_generic)
                    }
                    is VoiceEvent.Final -> onFinal(ev)
                }
            }
            _state.update { it.copy(listening = false, hearing = false) }
        }
    }

    private suspend fun onFinal(ev: VoiceEvent.Final) {
        val s = _state.value
        val src = s.source ?: LanguageRegistry.all.firstOrNull { it.whisperCode == ev.language }
        _state.update { it.copy(hearing = false, partial = null) }
        if (src == null) { _state.update { it.copy(notice = R.string.err_unsupported_language) }; return }
        val id = System.nanoTime()
        _state.update { it.copy(turns = it.turns + VoiceTurn(id, src, s.target, ev.text, null)) }
        val r = c.engines.translate(ev.text, src, s.target) { p -> updateTurn(id) { t -> t.copy(translated = p) } }
        val latency = System.nanoTime() / 1_000_000 - ev.phraseEndAt
        when (r) {
            is TranslationResult.Success -> {
                updateTurn(id) { it.copy(translated = r.text, latencyMs = latency) }
                if (c.settings.settings.value.saveHistory) c.history.add(HistoryEntry(timestamp = System.currentTimeMillis(), kind = HistoryKind.VOICE,
                    sourceLang = src.code, targetLang = s.target.code, sourceText = ev.text, translatedText = r.text))
                if (_state.value.autoSpeak && !c.speech.speak(r.text, s.target.code)) _state.update { it.copy(notice = R.string.err_speech_unavailable) }
            }
            is TranslationResult.Failure -> updateTurn(id) { it.copy(error = r.error, translated = null) }
            TranslationResult.Cancelled -> Unit
        }
    }

    private fun updateTurn(id: Long, f: (VoiceTurn) -> VoiceTurn) =
        _state.update { s -> s.copy(turns = s.turns.map { if (it.id == id) f(it) else it }) }

    fun speak(turn: VoiceTurn) {
        val text = turn.translated ?: return
        viewModelScope.launch { if (!c.speech.speak(text, turn.target.code)) _state.update { it.copy(notice = R.string.err_speech_unavailable) } }
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(listening = false, hearing = false, partial = null) }
    }

    override fun onCleared() { stop(); c.speech.stop() }
}

@Composable
fun VoiceTranslatorScreen(onBack: () -> Unit, onManagePacks: () -> Unit, vm: VoiceViewModel = viewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val engine by vm.speechEngine.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val packs by context.container.packs.states.collectAsStateWithLifecycle()
    val readiness = remember(packs) { context.container.packs.readiness() }
    var picker by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(st.turns.size) { if (st.turns.isNotEmpty()) listState.animateScrollToItem(st.turns.lastIndex) }

    ScreenScaffold(stringResource(R.string.home_voice), onBack) { pad ->
        PermissionGate(listOf(Manifest.permission.RECORD_AUDIO), stringResource(R.string.err_mic_denied)) {
            Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LanguageBar(st.source, st.target, { picker = "source" }, { picker = "target" }, onSwap = vm::swap)
                if (engine == EngineState.NotInstalled) {
                    Banner(stringResource(R.string.voice_unavailable_body), BannerKind.INFO, stringResource(R.string.action_install), onManagePacks)
                }
                if (engine == EngineState.UnsupportedDevice) Banner(stringResource(R.string.err_cpu_unsupported), BannerKind.ERROR)
                st.notice?.let { n ->
                    Banner(if (n == R.string.err_speech_unavailable) stringResource(n, st.target.nativeName) else stringResource(n), BannerKind.WARNING)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.conv_speak_aloud), Modifier.weight(1f))
                    Switch(st.autoSpeak, vm::setAutoSpeak)
                }
                LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(st.turns, key = { it.id }) { t ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                LanguageText(t.heard, t.source, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.padding(4.dp))
                                when {
                                    t.error != null -> Text(stringResource(t.error.messageRes()), color = MaterialTheme.colorScheme.error)
                                    t.translated != null -> LanguageText(t.translated, t.target, style = MaterialTheme.typography.titleMedium)
                                    else -> Text(stringResource(R.string.translating), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    t.latencyMs?.let { Text(stringResource(R.string.voice_latency, "%.1f s".format(it / 1000.0)), Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } ?: Spacer(Modifier.weight(1f))
                                    if (t.translated != null) IconButton(onClick = { vm.speak(t) }) {
                                        Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.action_speak))
                                    }
                                }
                            }
                        }
                    }
                    st.partial?.let { p -> item { LanguageText("… $p", st.source, style = MaterialTheme.typography.bodyMedium) } }
                }
                MicButton(listening = st.listening, hearing = st.hearing, level = st.level, onClick = vm::toggle)
            }
        }
    }

    picker?.let { which ->
        LanguagePickerDialog(
            options = readiness.all(), allowDetect = which == "source",
            onPick = { l -> if (which == "source") vm.setSource(l) else l?.let(vm::setTarget); picker = null },
            onDismiss = { picker = null },
            capability = { if (which == "source") it.speechInput else it.translation },
        )
    }
}

@Composable
fun MicButton(listening: Boolean, hearing: Boolean, level: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scale by animateFloatAsState(if (hearing) 1f + (level * 6f).coerceAtMost(0.25f) else 1f, label = "mic")
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            // Black-and-white identity: idle is a solid button, listening is the inverted (ringed) one.
            val fg = if (listening) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onPrimary
            FilledIconButton(
                onClick = onClick,
                modifier = Modifier.size(84.dp).scale(scale)
                    .then(if (listening) Modifier.border(4.dp, MaterialTheme.colorScheme.onBackground, CircleShape) else Modifier),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (listening) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.primary,
                    contentColor = fg,
                ),
            ) {
                Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = stringResource(if (listening) R.string.action_stop else R.string.voice_tap_to_speak), Modifier.size(36.dp))
            }
        }
        Text(
            stringResource(when { hearing -> R.string.voice_listening; listening -> R.string.voice_recording_indicator; else -> R.string.voice_tap_to_speak }),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
