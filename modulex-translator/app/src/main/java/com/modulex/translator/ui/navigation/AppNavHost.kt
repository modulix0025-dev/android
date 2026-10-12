package com.modulex.translator.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.modulex.translator.BuildConfig
import com.modulex.translator.ui.camera.CameraTranslatorScreen
import com.modulex.translator.ui.conversation.ConversationScreen
import com.modulex.translator.ui.conversation.ConversationSetupScreen
import com.modulex.translator.ui.diagnostics.DiagnosticsScreen
import com.modulex.translator.ui.history.HistoryScreen
import com.modulex.translator.ui.home.HomeScreen
import com.modulex.translator.ui.packs.LanguagePacksScreen
import com.modulex.translator.ui.privacy.NoticesScreen
import com.modulex.translator.ui.privacy.PrivacyScreen
import com.modulex.translator.ui.settings.SettingsScreen
import com.modulex.translator.ui.text.TextTranslatorScreen
import com.modulex.translator.ui.voice.VoiceTranslatorScreen
import java.net.URLDecoder
import java.net.URLEncoder

object Routes {
    const val HOME = "home"
    const val TEXT = "text?initial={initial}"
    const val VOICE = "voice"
    const val CAMERA = "camera"
    const val CONVERSATION_SETUP = "conversation"
    const val CONVERSATION = "conversation/live"
    const val PACKS = "packs"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val PRIVACY = "privacy"
    const val NOTICES = "notices"
    const val DIAGNOSTICS = "diagnostics"

    fun text(initial: String? = null) = if (initial == null) "text" else "text?initial=" + URLEncoder.encode(initial, "UTF-8")
}

@Composable
fun AppNavHost(sharedText: String?, onSharedTextConsumed: () -> Unit) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            nav.navigate(Routes.text(sharedText)) { launchSingleTop = true }
            onSharedTextConsumed()
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(navigate = { nav.navigate(it) }) }
        composable(Routes.TEXT, arguments = listOf(navArgument("initial") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
            val initial = entry.arguments?.getString("initial")?.let { URLDecoder.decode(it, "UTF-8") }
            TextTranslatorScreen(initialText = initial, onBack = back, onManagePacks = { nav.navigate(Routes.PACKS) })
        }
        composable(Routes.VOICE) { VoiceTranslatorScreen(onBack = back, onManagePacks = { nav.navigate(Routes.PACKS) }) }
        composable(Routes.CAMERA) { CameraTranslatorScreen(onBack = back, onManagePacks = { nav.navigate(Routes.PACKS) }) }
        composable(Routes.CONVERSATION_SETUP) {
            ConversationSetupScreen(onBack = back, onStarted = { nav.navigate(Routes.CONVERSATION) }, onOpenSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.CONVERSATION) { ConversationScreen(onBack = back) }
        composable(Routes.PACKS) { LanguagePacksScreen(onBack = back) }
        composable(Routes.HISTORY) { HistoryScreen(onBack = back) }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = back, onPrivacy = { nav.navigate(Routes.PRIVACY) },
                onDiagnostics = if (BuildConfig.DIAGNOSTICS) ({ nav.navigate(Routes.DIAGNOSTICS) }) else null)
        }
        composable(Routes.PRIVACY) { PrivacyScreen(onBack = back, onNotices = { nav.navigate(Routes.NOTICES) }) }
        composable(Routes.NOTICES) { NoticesScreen(onBack = back) }
        if (BuildConfig.DIAGNOSTICS) composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = back) }
    }
}
