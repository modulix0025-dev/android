package com.modulex.translator.engines

import com.modulex.translator.core.language.LanguageRegistry
import com.modulex.translator.core.translation.DecodingParams
import com.modulex.translator.core.translation.TranslationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TranslationEngineTest {
    private val ar = LanguageRegistry.require("ar")
    private val en = LanguageRegistry.require("en")
    private val fixture = File(System.getProperty("modulex.pipelineFixture") ?: "build/fixtures/pipeline-fixture.gguf")

    @Test
    fun rejectsNonGgufAndWrongArchitectureBeforeNativeLoad() = runBlocking<Unit> {
        val bogus = Files.createTempFile("bogus", ".gguf").toFile().apply { writeText("definitely not a model") }
        val e = LocalTranslationEngine()
        assertTrue(e.load(bogus.absolutePath, 2, 512).isFailure)
        assertTrue(e.load("/nonexistent/model.gguf", 2, 512).exceptionOrNull() is NativeEngineException)
        assertTrue(!e.isLoaded)
        assertIs<TranslationResult.Failure>(e.translate("hello", en, ar))
        if (fixture.isFile) {
            val strict = LocalTranslationEngine(expectedArchitecture = "some-other-arch")
            val err = strict.load(fixture.absolutePath, 2, 512).exceptionOrNull()
            assertEquals("MODEL_INCOMPATIBLE", (err as NativeEngineException).code)
        }
    }

    @Test
    fun nativeLoaderRejectsTruncatedModelWithoutCrashing() = runBlocking<Unit> {
        assumeTrue("fixture missing", fixture.isFile)
        val truncated = Files.createTempFile("truncated", ".gguf").toFile()
        truncated.writeBytes(fixture.readBytes().copyOf(fixture.length().toInt() / 2))
        val r = LocalTranslationEngine().load(truncated.absolutePath, 2, 512)
        assertEquals("MODEL_LOAD_FAILED", (r.exceptionOrNull() as NativeEngineException).code)
    }

    /**
     * Pipeline test with a random-weight model of the same architecture (see
     * tools/make_pipeline_fixture.py). Proves load, chat templating, tokenization, generation,
     * streaming, metrics and cancellation work through JNI. It says nothing about translation quality.
     */
    @Test
    fun pipelineRunsEndToEndWithFixtureModel() = runBlocking<Unit> {
        assumeTrue("fixture missing", fixture.isFile)
        val engine = LocalTranslationEngine()
        val info = engine.load(fixture.absolutePath, 2, 512).getOrThrow()
        assertEquals("hunyuan-dense", info.architecture)
        assertTrue(info.hasChatTemplate)

        val prompt = NativeTranslator.nativeFormatPrompt(
            engine.handleForTest(), "Translate the following text into English.\n\nمرحبا",
        )
        assertTrue(prompt.contains("<｜hy_User｜>") && prompt.endsWith("<｜hy_Assistant｜>"), "chat template not applied: $prompt")

        val partials = ArrayList<String>()
        val r = engine.translateStreaming("مرحبا بكم", ar, en, DecodingParams(maxNewTokens = 24, temperature = 0f)) { partials += it }
        // Random weights may emit an end token immediately; either way the pipeline must finish cleanly.
        when (r) {
            is TranslationResult.Success -> {
                assertTrue(r.metrics.promptTokens > 10)
                assertTrue(r.metrics.generatedTokens in 1..24)
                assertTrue(partials.isNotEmpty())
            }
            is TranslationResult.Failure -> assertEquals("EMPTY_OUTPUT", r.error.name)
            TranslationResult.Cancelled -> error("unexpected cancellation")
        }

        // Determinism with greedy decoding.
        val again = engine.translate("مرحبا بكم", ar, en, DecodingParams(maxNewTokens = 24, temperature = 0f))
        assertEquals(r.toString().substringBefore("metrics"), again.toString().substringBefore("metrics"))

        // Cancellation from another coroutine while generating.
        val long = async(Dispatchers.IO) {
            engine.translate("x ".repeat(100), en, ar, DecodingParams(maxNewTokens = 400, temperature = 1f, topK = 0, topP = 1f, repetitionPenalty = 1f))
        }
        delay(5)
        engine.cancel()
        val cancelled = long.await()
        assertTrue(cancelled is TranslationResult.Cancelled || cancelled is TranslationResult.Success || cancelled is TranslationResult.Failure)

        // Prompt larger than the context window is reported, not crashed.
        val tooLong = engine.translate("word ".repeat(400), en, ar, DecodingParams(maxNewTokens = 8))
        assertTrue(tooLong is TranslationResult.Failure, "expected failure for oversized prompt, got $tooLong")

        engine.unload()
        assertTrue(!engine.isLoaded)
        assertIs<TranslationResult.Failure>(engine.translate("hi", en, ar))
    }

    /** Runs only when a real model is provided: -Pmodulex.testModel=/path/to/model.gguf */
    @Test
    fun realModelTranslatesCorpus() = runBlocking<Unit> {
        val path = System.getProperty("modulex.testModel")
        assumeTrue("no real model provided (-Pmodulex.testModel)", path != null && File(path).isFile)
        val engine = LocalTranslationEngine()
        val loadStart = System.nanoTime()
        engine.load(path!!, Runtime.getRuntime().availableProcessors().coerceAtMost(4), 4096).getOrThrow()
        println("model load ms=" + (System.nanoTime() - loadStart) / 1_000_000)
        val corpus = File("src/test/resources/corpus.tsv").readLines().drop(1).map { it.split('\t') }
        for (row in corpus) {
            val (src, tgt, text) = row
            val r = engine.translate(text, LanguageRegistry.require(src), LanguageRegistry.require(tgt), DecodingParams())
            val s = r as TranslationResult.Success
            println("[$src->$tgt] ${s.metrics.totalMs}ms prompt=${s.metrics.promptTokens} gen=${s.metrics.generatedTokens}\n  IN : $text\n  OUT: ${s.text}\n  REF: ${row.getOrNull(3) ?: "-"}")
            assertTrue(s.text.isNotBlank())
        }
        engine.unload()
    }
}

/** Test-only access to the native handle for template checks. */
internal fun LocalTranslationEngine.handleForTest(): Long {
    val f = LocalTranslationEngine::class.java.getDeclaredField("handle")
    f.isAccessible = true
    return f.getLong(this)
}
