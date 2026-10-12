package com.modulex.translator.ui.diagnostics

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.modulex.translator.R
import com.modulex.translator.container
import com.modulex.translator.core.language.LanguageRegistry
import com.modulex.translator.core.translation.TranslationResult
import com.modulex.translator.platform.CpuSupport
import com.modulex.translator.ui.components.ScreenScaffold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-build-only diagnostics: device, engines, memory, thermal state and an on-device
 * translation benchmark over a fixed corpus. Results are measured on this device; nothing is sent
 * anywhere, the report can be shared manually.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    fun snapshot(): String {
        val mem = c.device.memory()
        return buildString {
            appendLine("Device: ${c.device.describe()}")
            appendLine("CPU cores: ${c.device.cpuCores}; native engines supported: ${CpuSupport.supported}")
            appendLine("Memory: avail ${mem.availBytes shr 20} MB / total ${mem.totalBytes shr 20} MB, low=${mem.lowMemory}")
            appendLine("Process PSS: ${c.device.processPssKb() / 1024} MB")
            appendLine("Thermal: ${c.device.thermal.value}; battery saver: ${c.device.batterySaver.value}")
            appendLine("Free storage: ${c.device.freeStorageBytes() shr 20} MB")
            appendLine("Translator: ${c.engines.translatorState.value}; last load ${c.engines.lastLoadMs} ms; ${c.engines.lastModelInfo}")
            appendLine("Speech: ${c.engines.speechState.value}")
            appendLine("Loaded engines: ${c.engines.loadedEngines().mapValues { it.value shr 20 }} MB")
            appendLine("Packs: " + c.packs.states.value.values.joinToString { "${it.pack.id}=${it.status}" })
        }
    }

    LaunchedEffect(Unit) { while (true) { if (!running) report = snapshot(); delay(2_000) } }

    ScreenScaffold(stringResource(R.string.diag_title), onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !running, onClick = {
                running = true
                scope.launch {
                    val sb = StringBuilder(snapshot()).appendLine().appendLine("Translation benchmark (recommended sampling, fixed corpus):")
                    val corpus = listOf(
                        Triple("ar", "en", "مرحبا، كيف حالك؟"),
                        Triple("ar", "en", "أريد حجز طاولة لشخصين الساعة الثامنة مساءً."),
                        Triple("en", "ar", "Where is the nearest pharmacy?"),
                        Triple("en", "ar", "Please send the invoice to Sarah Johnson before Friday, June 6."),
                    )
                    val times = ArrayList<Long>()
                    for ((s, t, text) in corpus) {
                        val r = c.engines.translate(text, LanguageRegistry.require(s), LanguageRegistry.require(t))
                        if (r is TranslationResult.Success) {
                            times += r.metrics.totalMs
                            sb.appendLine("[$s→$t] ${r.metrics.totalMs} ms (prefill ${r.metrics.prefillMs}, decode ${r.metrics.decodeMs}, ${r.metrics.generatedTokens} tok): ${r.text}")
                        } else sb.appendLine("[$s→$t] $r")
                        report = sb.toString()
                    }
                    if (times.isNotEmpty()) sb.appendLine("median ${times.sorted()[times.size / 2]} ms over ${times.size}; PSS ${c.device.processPssKb() / 1024} MB")
                    report = sb.toString()
                    running = false
                }
            }) { Text("Run benchmark") }
            OutlinedButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), null))
            }) { Text(stringResource(R.string.action_share)) }
            SelectionContainer { Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
