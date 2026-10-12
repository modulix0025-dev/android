package com.modulex.translator.data

import android.content.Context
import com.modulex.translator.security.SecretBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class IceServer(val url: String, val username: String?, val credential: String?)

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val saveHistory: Boolean = true,
    val saveConversationHistory: Boolean = false,
    val useSystemVoices: Boolean = true,
    val speechRate: Float = 1.0f,
    val keepTranslationLoaded: Boolean = true,
    val signalingUrl: String = "",
    val iceServers: List<IceServer> = emptyList(),
    val lastSource: String = "ar",
    val lastTarget: String = "en",
    val displayName: String = "",
    val shareOriginalInConversation: Boolean = true,
    val speakIncoming: Boolean = true,
)

/** Preferences-backed settings. ICE credentials are stored encrypted with a Keystore key. */
class SettingsRepository(context: Context, private val secrets: SecretBox) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun read(): AppSettings {
        val ice = prefs.getString("ice_box", null)?.let { secrets.decrypt(it) }?.let(::parseIce) ?: emptyList()
        return AppSettings(
            theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
            saveHistory = prefs.getBoolean("save_history", true),
            saveConversationHistory = prefs.getBoolean("save_conv_history", false),
            useSystemVoices = prefs.getBoolean("system_voices", true),
            speechRate = prefs.getFloat("speech_rate", 1.0f),
            keepTranslationLoaded = prefs.getBoolean("keep_loaded", true),
            signalingUrl = prefs.getString("signaling_url", "") ?: "",
            iceServers = ice,
            lastSource = prefs.getString("last_source", "ar") ?: "ar",
            lastTarget = prefs.getString("last_target", "en") ?: "en",
            displayName = prefs.getString("display_name", "") ?: "",
            shareOriginalInConversation = prefs.getBoolean("share_original", true),
            speakIncoming = prefs.getBoolean("speak_incoming", true),
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putString("theme", s.theme.name)
            .putBoolean("save_history", s.saveHistory)
            .putBoolean("save_conv_history", s.saveConversationHistory)
            .putBoolean("system_voices", s.useSystemVoices)
            .putFloat("speech_rate", s.speechRate)
            .putBoolean("keep_loaded", s.keepTranslationLoaded)
            .putString("signaling_url", s.signalingUrl)
            .putString("ice_box", if (s.iceServers.isEmpty()) null else secrets.encrypt(formatIce(s.iceServers)))
            .putString("last_source", s.lastSource)
            .putString("last_target", s.lastTarget)
            .putString("display_name", s.displayName)
            .putBoolean("share_original", s.shareOriginalInConversation)
            .putBoolean("speak_incoming", s.speakIncoming)
            .apply()
        _settings.value = s
    }

    companion object {
        /** One server per line: `url|username|credential` (username/credential optional). */
        fun parseIce(text: String): List<IceServer> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val parts = line.split("|")
            val url = parts[0].trim()
            if (!(url.startsWith("stun:") || url.startsWith("turn:") || url.startsWith("turns:"))) return@mapNotNull null
            IceServer(url, parts.getOrNull(1)?.takeIf { it.isNotBlank() }, parts.getOrNull(2)?.takeIf { it.isNotBlank() })
        }

        fun formatIce(list: List<IceServer>): String =
            list.joinToString("\n") { listOfNotNull(it.url, it.username, it.credential).joinToString("|") }
    }
}
