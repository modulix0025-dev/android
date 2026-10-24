package com.modulex.translator

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.modulex.translator.data.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Captures every screen of the real app (English/Arabic x light/dark) for UI review.
 * Not an assertion test: it is run on its own by CI, which pulls the PNGs from
 * Android/data/<app id>/files/screenshots. A screen that cannot be reached is listed in errors.txt.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33) // per-app locales (LocaleManager)
class ScreenshotTour {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val outDir = File(context.getExternalFilesDir(null), "screenshots").apply { deleteRecursively(); mkdirs() }
    private val errors = mutableListOf<String>()

    /** Strings come from the running activity so they follow the per-app locale being captured. */
    private var activity: MainActivity? = null
    private fun str(id: Int) = (activity ?: context).getString(id)

    private fun open(textId: Int) {
        val text = str(textId)
        runCatching { rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text)) }
        rule.onAllNodesWithText(text).onFirst().performClick()
        rule.waitForIdle()
    }

    private fun scrollDown() {
        rule.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp() }
        rule.waitForIdle()
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        Thread.sleep(900) // let images, ripples and the keyboard settle
        val bmp: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("no screenshot")
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Each shot starts from a fresh activity on the home screen. */
    private fun shot(prefix: String, name: String, steps: () -> Unit) {
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity = it }
                rule.waitForIdle()
                try {
                    steps()
                    capture("$prefix-$name")
                } catch (t: Throwable) {
                    runCatching { capture("$prefix-$name-FAILED") } // shows where navigation stopped
                    throw t
                }
            }
        } catch (t: Throwable) {
            errors += "$prefix-$name: ${t::class.simpleName}: ${t.message?.lineSequence()?.firstOrNull()}"
        } finally {
            activity = null
        }
    }

    @Test
    fun captureAllScreens() {
        val combos = listOf("en" to ThemeMode.LIGHT, "ar" to ThemeMode.LIGHT, "ar" to ThemeMode.DARK, "en" to ThemeMode.DARK)
        for ((lang, theme) in combos) {
            instrumentation.runOnMainSync {
                context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(lang)
            }
            context.container.settings.update { it.copy(theme = theme) }
            val p = "${lang}-${theme.name.lowercase()}"
            var n = 0
            fun name(s: String) = "%02d-%s".format(++n, s)

            shot(p, name("home")) {}
            shot(p, name("home-scrolled")) { scrollDown() }
            shot(p, name("text")) { open(R.string.home_text) }
            shot(p, name("text-typed")) {
                open(R.string.home_text)
                rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("مرحبا، أين أقرب صيدلية؟ Where is the nearest pharmacy?")
            }
            shot(p, name("voice")) { open(R.string.home_voice) }
            shot(p, name("camera")) { open(R.string.home_camera) }
            shot(p, name("conversation-create")) { open(R.string.home_conversation) }
            shot(p, name("conversation-create-scrolled")) { open(R.string.home_conversation); scrollDown() }
            shot(p, name("conversation-join")) { open(R.string.home_conversation); rule.onAllNodesWithText(str(R.string.conv_join)).onFirst().performClick() }
            shot(p, name("conversation-language-picker")) {
                open(R.string.home_conversation)
                rule.onNodeWithText(str(R.string.conv_i_speak), substring = true).performClick()
            }
            shot(p, name("language-packs")) { open(R.string.home_packs) }
            shot(p, name("language-packs-scrolled")) { open(R.string.home_packs); scrollDown() }
            shot(p, name("history")) { open(R.string.home_history) }
            shot(p, name("settings")) { open(R.string.home_settings) }
            shot(p, name("settings-scrolled")) { open(R.string.home_settings); scrollDown() }
            shot(p, name("privacy")) { open(R.string.home_privacy) }
            shot(p, name("notices")) { open(R.string.home_privacy); open(R.string.notices_title) }
            if (BuildConfig.DIAGNOSTICS) shot(p, name("diagnostics")) { open(R.string.home_settings); open(R.string.settings_diagnostics) }
            // Hosting a local (LAN) session shows the pairing QR code on the live screen.
            shot(p, name("conversation-live-qr")) {
                open(R.string.home_conversation)
                rule.onAllNodesWithText(str(R.string.conv_create)).onLast().performClick()
                rule.waitForIdle()
                Thread.sleep(2500)
            }
            runCatching { context.container.conversation.end() }
            Thread.sleep(500)
        }
        File(outDir, "errors.txt").writeText(errors.joinToString("\n"))
    }
}
