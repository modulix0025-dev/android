package com.modulex.translator.ui.settings

import com.modulex.translator.ui.components.ChoiceChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modulex.translator.R
import com.modulex.translator.container
import com.modulex.translator.data.SettingsRepository
import com.modulex.translator.data.ThemeMode
import com.modulex.translator.ui.components.Banner
import com.modulex.translator.ui.components.BannerKind
import com.modulex.translator.ui.components.ScreenScaffold
import com.modulex.translator.ui.components.SectionCard
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList

@Composable
fun SettingsScreen(onBack: () -> Unit, onPrivacy: () -> Unit, onDiagnostics: (() -> Unit)?) {
    val context = LocalContext.current
    val repo = context.container.settings
    val s by repo.settings.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(s.signalingUrl) }
    var ice by remember { mutableStateOf(SettingsRepository.formatIce(s.iceServers)) }
    var saved by remember { mutableStateOf<Int?>(null) }

    ScreenScaffold(stringResource(R.string.settings_title), onBack) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard {
                Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_theme))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { m ->
                        ChoiceChip(
                            s.theme == m,
                            stringResource(when (m) { ThemeMode.SYSTEM -> R.string.settings_theme_system; ThemeMode.LIGHT -> R.string.settings_theme_light; ThemeMode.DARK -> R.string.settings_theme_dark }),
                        ) { repo.update { it.copy(theme = m) } }
                    }
                }
                Text(stringResource(R.string.settings_language))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val current = remember { currentAppLocaleTag(context) }
                    listOf("" to R.string.settings_language_system, "en" to null, "ar" to null).forEach { (tag, label) ->
                        ChoiceChip(current == tag, label?.let { stringResource(it) } ?: if (tag == "en") "English" else "العربية") {
                            setAppLocale(context, tag)
                        }
                    }
                }
            }
            SectionCard {
                Text(stringResource(R.string.settings_privacy), style = MaterialTheme.typography.titleMedium)
                Toggle(R.string.settings_history, R.string.settings_history_desc, s.saveHistory) { v -> repo.update { it.copy(saveHistory = v) } }
                Toggle(R.string.settings_conv_history, null, s.saveConversationHistory) { v -> repo.update { it.copy(saveConversationHistory = v) } }
                Toggle(R.string.conv_share_original, null, s.shareOriginalInConversation) { v -> repo.update { it.copy(shareOriginalInConversation = v) } }
                TextButton(onClick = onPrivacy) { Text(stringResource(R.string.home_privacy)) }
            }
            SectionCard {
                Text(stringResource(R.string.settings_speech), style = MaterialTheme.typography.titleMedium)
                Toggle(R.string.settings_system_voices, R.string.settings_system_voices_desc, s.useSystemVoices) { v -> repo.update { it.copy(useSystemVoices = v) } }
                Toggle(R.string.conv_speak_aloud, null, s.speakIncoming) { v -> repo.update { it.copy(speakIncoming = v) } }
                Text(stringResource(R.string.settings_speech_rate) + ": %.1f×".format(s.speechRate))
                Slider(s.speechRate, { v -> repo.update { it.copy(speechRate = v) } }, valueRange = 0.6f..1.6f, steps = 9)
            }
            SectionCard {
                Text(stringResource(R.string.settings_performance), style = MaterialTheme.typography.titleMedium)
                Toggle(R.string.settings_keep_loaded, R.string.settings_keep_loaded_desc, s.keepTranslationLoaded) { v -> repo.update { it.copy(keepTranslationLoaded = v) } }
            }
            SectionCard {
                Text(stringResource(R.string.settings_internet), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.settings_signaling_url)) }, singleLine = true,
                    placeholder = { Text("wss://signal.example.com") })
                OutlinedTextField(ice, { ice = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.settings_ice_servers)) }, minLines = 2)
                Text(stringResource(R.string.settings_ice_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                saved?.let { Banner(stringResource(it), if (it == R.string.settings_saved) BannerKind.OK else BannerKind.ERROR) }
                OutlinedButton(onClick = {
                    if (url.isNotEmpty() && !url.startsWith("wss://")) { saved = R.string.settings_invalid_url; return@OutlinedButton }
                    repo.update { it.copy(signalingUrl = url, iceServers = SettingsRepository.parseIce(ice)) }
                    saved = R.string.settings_saved
                }) { Text(stringResource(R.string.action_done)) }
            }
            onDiagnostics?.let { TextButton(onClick = it) { Text(stringResource(R.string.settings_diagnostics)) } }
        }
    }
}

@Composable
private fun Toggle(title: Int, desc: Int?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title))
            desc?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(value, onChange)
    }
}

/** The per-app language tag ("" = follow the system). */
private fun currentAppLocaleTag(context: android.content.Context): String =
    if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language ?: ""
    } else {
        ""
    }

/** Per-app language (Android 13+ uses the system per-app setting; older versions follow the system). */
private fun setAppLocale(context: android.content.Context, tag: String) {
    if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }
}

