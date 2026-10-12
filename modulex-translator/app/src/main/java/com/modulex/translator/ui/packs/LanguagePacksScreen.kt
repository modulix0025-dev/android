package com.modulex.translator.ui.packs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.modulex.translator.BuildConfig
import com.modulex.translator.R
import com.modulex.translator.assets.PackState
import com.modulex.translator.container
import com.modulex.translator.core.assets.AssetPack
import com.modulex.translator.core.assets.InstallFailure
import com.modulex.translator.core.assets.InstallOutcome
import com.modulex.translator.core.assets.InstallProgress
import com.modulex.translator.core.assets.PackKind
import com.modulex.translator.core.assets.PackStatus
import com.modulex.translator.core.language.LanguageRegistry
import com.modulex.translator.ui.components.Banner
import com.modulex.translator.ui.components.BannerKind
import com.modulex.translator.ui.components.ScreenScaffold
import kotlinx.coroutines.launch

@Composable
fun LanguagePacksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val pm = context.container.packs
    val states by pm.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<AssetPack?>(null) }
    var importFor by remember { mutableStateOf<AssetPack?>(null) }
    var unpinnedWarning by remember { mutableStateOf<AssetPack?>(null) }
    var message by remember { mutableStateOf<Pair<Int, BannerKind>?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val pack = importFor ?: return@rememberLauncherForActivityResult
        importFor = null
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val ordered = matchFiles(context, pack, uris)
        if (ordered == null) { message = R.string.err_generic to BannerKind.ERROR; return@rememberLauncherForActivityResult }
        scope.launch {
            message = when (val r = pm.import(pack.id, ordered)) {
                is InstallOutcome.Installed -> R.string.packs_installed to BannerKind.OK
                is InstallOutcome.Failed -> (if (r.reason == InstallFailure.HASH_MISMATCH) R.string.packs_failed_hash else R.string.err_generic) to BannerKind.ERROR
            }
        }
    }

    val engines = states.values.filter { it.pack.kind in setOf(PackKind.TRANSLATION_MODEL, PackKind.SPEECH_MODEL, PackKind.TTS_RUNTIME_DATA) }
    val languages = states.values.filter { it.pack.kind in setOf(PackKind.OCR_LANGUAGE, PackKind.TTS_VOICE) }
        .sortedWith(compareBy({ !it.usable }, { LanguageRegistry.get(it.pack.languages.firstOrNull() ?: "")?.englishName ?: it.pack.id }))

    ScreenScaffold(stringResource(R.string.packs_title), onBack) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            message?.let { (m, k) -> item { Banner(stringResource(m), k, stringResource(R.string.action_done), { message = null }) } }
            item { Text(stringResource(R.string.packs_engines), style = MaterialTheme.typography.titleMedium) }
            items(engines, key = { it.pack.id }) { s ->
                PackRow(s, onInstall = { confirm = s.pack }, onCancel = { pm.cancelDownload(s.pack.id) },
                    onDelete = { if (!pm.delete(s.pack.id)) message = R.string.packs_in_use to BannerKind.WARNING },
                    onVerify = { scope.launch { message = if (pm.verify(s.pack.id)) R.string.packs_verify_ok to BannerKind.OK else R.string.packs_verify_failed to BannerKind.ERROR } },
                    onImport = { if (s.pack.isPinned) { importFor = s.pack; picker.launch(arrayOf("*/*")) } else unpinnedWarning = s.pack })
            }
            item { Text(stringResource(R.string.packs_languages), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(languages, key = { it.pack.id }) { s ->
                PackRow(s, onInstall = { confirm = s.pack }, onCancel = { pm.cancelDownload(s.pack.id) },
                    onDelete = { if (!pm.delete(s.pack.id)) message = R.string.packs_in_use to BannerKind.WARNING },
                    onVerify = { scope.launch { message = if (pm.verify(s.pack.id)) R.string.packs_verify_ok to BannerKind.OK else R.string.packs_verify_failed to BannerKind.ERROR } },
                    onImport = { if (s.pack.isPinned) { importFor = s.pack; picker.launch(arrayOf("*/*")) } else unpinnedWarning = s.pack })
            }
        }
    }

    confirm?.let { p ->
        val need = pm.requiredBytes(p.id) ?: 0
        val room = pm.hasRoomFor(p.id)
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.packs_confirm_title, packTitle(p))) },
            text = {
                Text(if (room) stringResource(R.string.packs_confirm_body, Formatter.formatFileSize(context, p.totalBytes ?: 0))
                     else stringResource(R.string.packs_low_storage, Formatter.formatFileSize(context, need)))
            },
            confirmButton = { if (room) TextButton(onClick = { pm.download(p.id); confirm = null }) { Text(stringResource(R.string.action_install)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    unpinnedWarning?.let { p ->
        AlertDialog(
            onDismissRequest = { unpinnedWarning = null },
            title = { Text(stringResource(R.string.packs_import_unpinned_title)) },
            text = { Text(stringResource(R.string.packs_import_unpinned_body)) },
            confirmButton = { TextButton(onClick = { unpinnedWarning = null; importFor = p; picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.action_continue)) } },
            dismissButton = { TextButton(onClick = { unpinnedWarning = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun packTitle(p: AssetPack): String = when (p.kind) {
    PackKind.TRANSLATION_MODEL -> stringResource(R.string.pack_kind_translation)
    PackKind.SPEECH_MODEL -> stringResource(if (p.id == "speech-small") R.string.pack_kind_speech_hq else R.string.pack_kind_speech)
    PackKind.TTS_RUNTIME_DATA -> stringResource(R.string.pack_kind_voice_data)
    PackKind.OCR_LANGUAGE -> stringResource(R.string.pack_kind_ocr) + " · " + languageNames(p)
    PackKind.TTS_VOICE -> stringResource(R.string.pack_kind_voice) + " · " + languageNames(p)
}

@Composable
private fun languageNames(p: AssetPack) =
    if (p.languages.isEmpty()) stringResource(R.string.pack_all_languages) else p.languages.mapNotNull { LanguageRegistry.get(it)?.nativeName }.joinToString(", ")

@Composable
private fun PackRow(s: PackState, onInstall: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit, onVerify: () -> Unit, onImport: () -> Unit) {
    val context = LocalContext.current
    val p = s.pack
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(packTitle(p), style = MaterialTheme.typography.titleMedium)
            val statusText = when {
                s.progress is InstallProgress.Downloading -> {
                    val d = s.progress
                    stringResource(R.string.packs_downloading, Formatter.formatFileSize(context, d.bytes), Formatter.formatFileSize(context, d.total ?: 0))
                }
                s.progress is InstallProgress.Verifying || s.progress is InstallProgress.Committing -> stringResource(R.string.packs_verifying)
                s.lastFailure == InstallFailure.HASH_MISMATCH -> stringResource(R.string.packs_failed_hash)
                s.lastFailure == InstallFailure.NETWORK -> stringResource(R.string.packs_failed_network)
                else -> stringResource(when (s.status) {
                    PackStatus.INSTALLED_VERIFIED -> R.string.packs_verified
                    PackStatus.INSTALLED_USER_IMPORT -> R.string.packs_user_import
                    PackStatus.PARTIAL_DOWNLOAD -> R.string.packs_partial
                    PackStatus.CORRUPT -> R.string.packs_corrupt
                    PackStatus.UPDATE_AVAILABLE -> R.string.packs_update
                    PackStatus.NOT_INSTALLED -> if (p.isDownloadable || p.bundled) R.string.packs_not_installed else R.string.packs_unverified
                })
            }
            val size = p.totalBytes ?: s.installedBytes.takeIf { it > 0 }
            Text(statusText + (size?.let { " · " + Formatter.formatFileSize(context, it) } ?: "") +
                if (s.usable) " · " + stringResource(R.string.packs_offline_available) else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (s.progress as? InstallProgress.Downloading)?.let { d ->
                LinearProgressIndicator(progress = { if ((d.total ?: 0) > 0) d.bytes.toFloat() / d.total!! else 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
            if (BuildConfig.DIAGNOSTICS) Text("${p.id} · ${p.version} · ${p.upstream}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
            Text(stringResource(R.string.packs_license, p.license), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    s.busy -> TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    s.usable -> {
                        if (!p.bundled) TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
                        TextButton(onClick = onVerify) { Text(stringResource(R.string.packs_verify_now)) }
                    }
                    p.isDownloadable -> TextButton(onClick = onInstall) { Text(stringResource(R.string.action_install)) }
                }
                if (!s.busy && !s.usable && !p.bundled) TextButton(onClick = onImport) { Text(stringResource(R.string.packs_import)) }
            }
        }
    }
}

/** Orders picked files to match the pack's file list by their extensions (e.g. ".onnx" vs ".onnx.json"). */
private fun matchFiles(context: Context, pack: AssetPack, uris: List<Uri>): List<Uri>? {
    val named = uris.map { u ->
        val name = context.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: ""
        u to name.lowercase()
    }
    if (pack.files.size == 1) return if (named.size == 1) listOf(named[0].first) else null
    // Longest extension first, so "voice.onnx.json" claims the .json file before "voice.onnx" matches.
    val used = HashSet<Uri>()
    val byFile = HashMap<String, Uri>()
    for (f in pack.files.sortedByDescending { it.name.length - it.name.indexOf('.') }) {
        val ext = f.name.substring(f.name.indexOf('.')).lowercase()
        val match = named.firstOrNull { (u, n) -> u !in used && n.endsWith(ext) } ?: return null
        used += match.first
        byFile[f.name] = match.first
    }
    return pack.files.map { byFile.getValue(it.name) }
}
