package com.modulex.translator

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.modulex.translator.core.assets.PackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/** On-device smoke tests: startup, navigation, bundled OCR data verification. */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun homeShowsAllFeatures() {
        rule.onNodeWithText(str(R.string.app_name)).assertIsDisplayed()
        for (id in listOf(R.string.home_conversation, R.string.home_voice, R.string.home_text, R.string.home_camera)) {
            rule.onNodeWithText(str(id)).assertIsDisplayed()
        }
    }

    @Test
    fun textTranslatorOpens() {
        rule.onNodeWithText(str(R.string.home_text)).performClick()
        rule.onNodeWithText(str(R.string.text_hint)).assertIsDisplayed()
    }

    @Test
    fun bundledOcrPacksInstallAndVerify() = runBlocking {
        val packs = rule.activity.container.packs
        packs.installBundled()
        for (id in listOf("ocr-ara", "ocr-eng")) {
            assertEquals(PackStatus.INSTALLED_VERIFIED, packs.store.status(packs.manifest.pack(id)!!))
            assertTrue(packs.verify(id))
        }
    }
}
