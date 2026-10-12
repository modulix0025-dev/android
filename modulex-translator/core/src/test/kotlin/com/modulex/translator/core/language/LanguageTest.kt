package com.modulex.translator.core.language

import com.modulex.translator.core.assets.AssetFile
import com.modulex.translator.core.assets.AssetManifest
import com.modulex.translator.core.assets.AssetPack
import com.modulex.translator.core.assets.PackKind
import com.modulex.translator.core.assets.PackStatus
import com.modulex.translator.core.resources.HeavyEngine
import com.modulex.translator.core.resources.MemorySnapshot
import com.modulex.translator.core.resources.ResourcePolicy
import com.modulex.translator.core.resources.ThermalLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LanguageTest {
    private val ar = LanguageRegistry.require("ar")
    private val en = LanguageRegistry.require("en")
    private val fr = LanguageRegistry.require("fr")
    private val zh = LanguageRegistry.require("zh")
    private val ja = LanguageRegistry.require("ja")

    @Test
    fun registryIsConsistent() {
        assertEquals(LanguageRegistry.all.size, LanguageRegistry.all.map { it.code }.toSet().size)
        assertTrue(ar.rtl && !en.rtl)
        assertTrue(LanguageRegistry.all.all { it.nativeName.isNotBlank() })
        assertTrue(LanguageRegistry.all.size >= 33)
        assertTrue(LanguageRegistry.get("ug")!!.whisperCode == null, "Uyghur has no Whisper token: speech input unsupported")
    }

    @Test
    fun scriptDetectionPicksSideOfPairOnlyWhenReliable() {
        val d = ScriptDetector.decideWithinPair("مرحبا، كيف حالك؟", ar, en)
        assertIs<ScriptDetector.PairDecision.Detected>(d)
        assertEquals("ar", d.language.code)
        assertEquals("en", (ScriptDetector.decideWithinPair("Hello, how are you?", ar, en) as ScriptDetector.PairDecision.Detected).language.code)
        // Same script: never guess.
        assertIs<ScriptDetector.PairDecision.Ambiguous>(ScriptDetector.decideWithinPair("Bonjour", fr, en))
        // Mixed text with no clear majority.
        assertIs<ScriptDetector.PairDecision.Ambiguous>(ScriptDetector.decideWithinPair("Hello مرحبا", ar, en))
        // A loanword inside an Arabic sentence still reads as Arabic.
        assertEquals("ar", (ScriptDetector.decideWithinPair("OK تمام", ar, en) as ScriptDetector.PairDecision.Detected).language.code)
        assertIs<ScriptDetector.PairDecision.Ambiguous>(ScriptDetector.decideWithinPair("123 !", ar, en))
        // Japanese with kana vs Chinese.
        assertEquals("ja", (ScriptDetector.decideWithinPair("今日はいい天気ですね", zh, ja) as ScriptDetector.PairDecision.Detected).language.code)
        assertEquals("zh", (ScriptDetector.decideWithinPair("今天天气很好", zh, ja) as ScriptDetector.PairDecision.Detected).language.code)
    }

    private val sha = "0".repeat(64)
    private val manifest = AssetManifest(1, listOf(
        AssetPack("mt", PackKind.TRANSLATION_MODEL, "Translation", "1", emptyList(), listOf(AssetFile("m.gguf", "https://x/m", 10, sha)), "u", "l", "a"),
        AssetPack("asr", PackKind.SPEECH_MODEL, "Speech", "1", emptyList(), listOf(AssetFile("s.bin", "https://x/s", 10, null)), "u", "l", "a"),
        AssetPack("ocr-ara", PackKind.OCR_LANGUAGE, "Arabic text", "1", listOf("ar"), listOf(AssetFile("ara.traineddata", null, 10, sha)), "u", "l", "a", bundled = true, meta = mapOf("traineddata" to "ara")),
        AssetPack("ocr-eng", PackKind.OCR_LANGUAGE, "English text", "1", listOf("en"), listOf(AssetFile("eng.traineddata", null, 10, sha)), "u", "l", "a", bundled = true, meta = mapOf("traineddata" to "eng")),
        AssetPack("voice-en", PackKind.TTS_VOICE, "English voice", "1", listOf("en"), listOf(AssetFile("v.onnx", "https://x/v", 10, sha)), "u", "l", "a"),
    ))

    @Test
    fun readinessRequiresInstalledVerifiedPacks() {
        val installed = mutableSetOf("ocr-ara")
        val r = LanguageReadiness(manifest, { if (it.id in installed) PackStatus.INSTALLED_VERIFIED else PackStatus.NOT_INSTALLED })
        val a = r.features(ar)
        assertEquals(FeatureState.NEEDS_PACK, a.translation)
        assertEquals(FeatureState.PACK_UNVERIFIED, a.speechInput, "speech pack without pinned hash cannot be offered as ready")
        assertEquals(FeatureState.READY, a.ocr)
        assertEquals(FeatureState.UNSUPPORTED, a.speechOutput, "no Arabic voice in catalogue")
        assertTrue("mt" in a.missingPacks)

        installed += "mt"
        assertEquals(FeatureState.READY, r.features(ar).translation)
        assertEquals(FeatureState.NEEDS_PACK, r.features(en).speechOutput)
        assertEquals(FeatureState.UNSUPPORTED, r.features(fr).ocr, "no French OCR pack in this catalogue")
        val corrupt = LanguageReadiness(manifest, { PackStatus.CORRUPT })
        assertEquals(FeatureState.NEEDS_PACK, corrupt.features(ar).translation)
    }

    @Test
    fun thermalPolicyReducesWork() {
        val cool = ResourcePolicy.budget(ThermalLevel.NONE, 8, false)
        val hot = ResourcePolicy.budget(ThermalLevel.SEVERE, 8, false)
        assertEquals(4, cool.inferenceThreads)
        assertTrue(cool.allowContinuousCamera && cool.allowAutoListening)
        assertTrue(hot.inferenceThreads < cool.inferenceThreads)
        assertTrue(!hot.allowContinuousCamera && !hot.allowAutoListening && hot.userMessage != null)
        assertEquals(1, ResourcePolicy.budget(ThermalLevel.CRITICAL, 8, false).inferenceThreads)
        assertTrue(ResourcePolicy.budget(ThermalLevel.NONE, 8, true).inferenceThreads < 4)
    }

    @Test
    fun evictionKeepsTranslationResidentWhenPossible() {
        val gb = 1L shl 30
        val loaded = mapOf(HeavyEngine.TRANSLATION to (1400L shl 20), HeavyEngine.SPEECH to (400L shl 20), HeavyEngine.TTS to (150L shl 20))
        assertEquals(emptyList(), ResourcePolicy.evictionsFor(HeavyEngine.OCR, loaded, MemorySnapshot(6 * gb, 12 * gb, false)))
        val tight = ResourcePolicy.evictionsFor(HeavyEngine.OCR, loaded, MemorySnapshot(1_300L shl 20, 12 * gb, false))
        assertTrue(tight.isNotEmpty() && HeavyEngine.TRANSLATION !in tight)
        val critical = ResourcePolicy.evictionsFor(HeavyEngine.OCR, loaded, MemorySnapshot(100L shl 20, 12 * gb, true))
        assertEquals(HeavyEngine.TRANSLATION, critical.last())
    }
}
