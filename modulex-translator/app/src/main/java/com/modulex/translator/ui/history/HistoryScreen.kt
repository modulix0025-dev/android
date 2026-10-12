package com.modulex.translator.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modulex.translator.R
import com.modulex.translator.container
import com.modulex.translator.core.language.LanguageRegistry
import com.modulex.translator.ui.components.Banner
import com.modulex.translator.ui.components.BannerKind
import com.modulex.translator.ui.components.EmptyState
import com.modulex.translator.ui.components.LanguageText
import com.modulex.translator.ui.components.ScreenScaffold
import com.modulex.translator.ui.text.copy
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val c = LocalContext.current.container
    val context = LocalContext.current
    val entries by c.history.entries.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { c.history.refresh() }

    ScreenScaffold(stringResource(R.string.history_title), onBack, actions = {
        if (entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.history_clear_all)) }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!settings.saveHistory) item { Banner(stringResource(R.string.history_disabled), BannerKind.INFO) }
            if (entries.isEmpty()) item { EmptyState(Icons.Filled.History, stringResource(R.string.history_empty)) }
            items(entries, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        val src = LanguageRegistry.get(e.sourceLang)
                        val tgt = LanguageRegistry.get(e.targetLang)
                        Text("${src?.nativeName ?: e.sourceLang} → ${tgt?.nativeName ?: e.targetLang} · " +
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.timestamp)),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (e.sourceText.isNotBlank()) LanguageText(e.sourceText, src, style = MaterialTheme.typography.bodyMedium)
                        LanguageText(e.translatedText, tgt, style = MaterialTheme.typography.bodyLarge)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { copy(context, e.translatedText) }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.action_copy)) }
                            IconButton(onClick = { scope.launch { c.history.delete(e.id) } }) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        text = { Text(stringResource(R.string.history_clear_confirm)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch { c.history.clear() } }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
