package com.modulex.translator.ui.text

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.modulex.translator.BuildConfig
import com.modulex.translator.R
import com.modulex.translator.container
import com.modulex.translator.core.language.Language
import com.modulex.translator.core.language.LanguageRegistry
import com.modulex.translator.core.language.ScriptDetector
import com.modulex.translator.core.translation.TranslationError
import com.modulex.translator.core.translation.TranslationMetrics
import com.modulex.translator.core.translation.TranslationResult
import com.modulex.translator.data.HistoryEntry
import com.modulex.translator.data.HistoryKind
import com.modulex.translator.ui.components.Banner
import com.modulex.translator.ui.components.BannerKind
import com.modulex.translator.ui.components.LanguageBar
import com.modulex.translator.ui.components.LanguagePickerDialog
import com.modulex.translator.ui.components.LanguageText
import com.modulex.translator.ui.components.LoadingBlock
import com.modulex.translator.ui.components.ScreenScaffold
import com.modulex.translator.ui.components.messageRes
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_INPUT_CHARS = 5_000

sealed interface TextStatus {
    data object Idle : TextStatus
    data object Translating : TextStatus
    data class Done(val metrics: TranslationMetrics) : TextStatus
    data class Error(val error: TranslationError?) : TextStatus
}

data class TextUiState(
    val input: String = "",
    /** null = detect */
    val source: Language? = null,
    val target: Language = LanguageRegistry.require("en"),
    val output: String = "",
    val status: TextStatus = TextStatus.Idle,
    val detected: Language? = null,
    val detectionAmbiguous: Boolean = false,
    val speechUnavailable: Boolean = false,
)

class TextTranslatorViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val _state = MutableStateFlow(
        c.settings.settings.value.let {
            TextUiState(source = LanguageRegistry.get(it.lastSource), target = LanguageRegistry.get(it.lastTarget) ?: LanguageRegistry.require("en"))
        },
    )
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun setInput(text: String) = _state.update { it.copy(input = text.take(MAX_INPUT_CHARS)) }
    fun setSource(l: Language?) { _state.update { it.copy(source = l, detected = null) }; persist() }
    fun setTarget(l: Language) { _state.update { it.copy(target = l) }; persist() }

    fun swap() {
        val s = _state.value
        val src = s.source ?: s.detected ?: return
        _state.update { it.copy(source = s.target, target = src, input = s.output.ifEmpty { s.input }, output = "", status = TextStatus.Idle) }
        persist()
    }

    private fun persist() {
        val s = _state.value
        c.settings.update { it.copy(lastSource = s.source?.code ?: it.lastSource, lastTarget = s.target.code) }
    }

    fun clear() {
        job?.cancel(); c.engines.cancelTranslation()
        _state.update { it.copy(input = "", output = "", status = TextStatus.Idle, detected = null, detectionAmbiguous = false) }
    }

    /** Starts a translation, cancelling any running one so results never interleave. */
    fun translate() {
        val s = _state.value
        val text = s.input.trim()
        if (text.isEmpty()) return
        job?.cancel()
        c.engines.cancelTranslation()
        var source = s.source
        var ambiguous = false
        if (source == null) {
            source = ScriptDetector.detectUnique(text)
                ?: LanguageRegistry.get(c.settings.settings.value.lastSource)?.takeIf { ScriptDetector.histogram(text).dominant == it.script }
            if (source == null) {
                ambiguous = true
                source = LanguageRegistry.get(c.settings.settings.value.lastSource) ?: LanguageRegistry.require("en")
            }
        }
        val src = source
        _state.update { it.copy(status = TextStatus.Translating, output = "", detected = if (s.source == null) src else null, detectionAmbiguous = ambiguous) }
        job = viewModelScope.launch {
            val r = c.engines.translate(text, src, s.target) { partial -> _state.update { it.copy(output = partial) } }
            when (r) {
                is TranslationResult.Success -> {
                    _state.update { it.copy(output = r.text, status = TextStatus.Done(r.metrics)) }
                    if (c.settings.settings.value.saveHistory) {
                        c.history.add(HistoryEntry(timestamp = System.currentTimeMillis(), kind = HistoryKind.TEXT,
                            sourceLang = src.code, targetLang = s.target.code, sourceText = text, translatedText = r.text))
                    }
                }
                is TranslationResult.Failure -> _state.update { it.copy(status = TextStatus.Error(r.error), output = "") }
                TranslationResult.Cancelled -> _state.update { it.copy(status = TextStatus.Idle) }
            }
        }
    }

    fun speak() {
        val s = _state.value
        if (s.output.isBlank()) return
        viewModelScope.launch {
            val ok = c.speech.speak(s.output, s.target.code)
            _state.update { it.copy(speechUnavailable = !ok) }
        }
    }

    override fun onCleared() {
        c.engines.cancelTranslation()
        c.speech.stop()
    }
}

@Composable
fun TextTranslatorScreen(initialText: String?, onBack: () -> Unit, onManagePacks: () -> Unit, vm: TextTranslatorViewModel = viewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val packs by context.container.packs.states.collectAsStateWithLifecycle()
    val readiness = remember(packs) { context.container.packs.readiness() }
    var picker by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialText) { if (initialText != null) { vm.setInput(initialText); vm.translate() } }

    ScreenScaffold(stringResource(R.string.home_text), onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LanguageBar(st.source, st.target, { picker = "source" }, { picker = "target" }, onSwap = vm::swap)

            OutlinedTextField(
                value = st.input, onValueChange = vm::setInput,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                label = { Text(stringResource(R.string.text_hint)) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    textDirection = when { st.source == null -> TextDirection.Content; st.source!!.rtl -> TextDirection.Rtl; else -> TextDirection.Ltr },
                ),
                supportingText = { Text(stringResource(R.string.chars_count, st.input.length, MAX_INPUT_CHARS)) },
                trailingIcon = {
                    if (st.input.isNotEmpty()) IconButton(onClick = vm::clear) { Icon(Icons.Filled.Clear, stringResource(R.string.action_clear)) }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                    clip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(vm::setInput)
                }) { Icon(Icons.Filled.ContentPaste, null); Spacer(Modifier.padding(4.dp)); Text(stringResource(R.string.action_paste)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = vm::translate, enabled = st.input.isNotBlank() && st.status != TextStatus.Translating) {
                    Text(stringResource(R.string.translation))
                }
            }

            if (st.detected != null) {
                Text(stringResource(R.string.text_detected, st.detected!!.nativeName), style = MaterialTheme.typography.bodyMedium)
            }
            if (st.detectionAmbiguous) Banner(stringResource(R.string.text_direction_ambiguous), BannerKind.WARNING)

            when (val status = st.status) {
                TextStatus.Translating -> if (st.output.isEmpty()) LoadingBlock(stringResource(R.string.translating))
                is TextStatus.Error -> {
                    val err = status.error
                    if (err == TranslationError.MODEL_NOT_INSTALLED) {
                        Banner(stringResource(R.string.err_model_missing), BannerKind.INFO, stringResource(R.string.action_install), onManagePacks)
                    } else {
                        Banner(stringResource(err?.messageRes() ?: R.string.err_generic), BannerKind.ERROR, stringResource(R.string.action_retry), vm::translate)
                    }
                }
                else -> Unit
            }

            if (st.output.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.translation), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer { LanguageText(st.output, st.target) }
                        Row {
                            IconButton(onClick = { copy(context, st.output) }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.action_copy)) }
                            IconButton(onClick = { share(context, st.output) }) { Icon(Icons.Filled.Share, stringResource(R.string.action_share)) }
                            IconButton(onClick = vm::speak) { Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.action_speak)) }
                        }
                        if (BuildConfig.DIAGNOSTICS) (st.status as? TextStatus.Done)?.metrics?.let { m ->
                            Text("${m.totalMs} ms · prompt ${m.promptTokens} · gen ${m.generatedTokens} · prefill ${m.prefillMs} ms · decode ${m.decodeMs} ms",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (st.speechUnavailable) Banner(stringResource(R.string.err_speech_unavailable, st.target.nativeName), BannerKind.WARNING)
            } else if (st.status == TextStatus.Idle) {
                Text(stringResource(R.string.text_empty_state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    picker?.let { which ->
        LanguagePickerDialog(
            options = readiness.all().filter { it.language.translation },
            allowDetect = which == "source",
            onPick = { l -> if (which == "source") vm.setSource(l) else l?.let(vm::setTarget); picker = null },
            onDismiss = { picker = null },
            capability = { it.translation },
        )
    }
}

fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("translation", text))
}

fun share(context: Context, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
}
