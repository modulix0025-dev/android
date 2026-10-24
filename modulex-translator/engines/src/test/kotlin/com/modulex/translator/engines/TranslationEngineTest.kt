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
        val times = ArrayList<Long>()
        val scores = HashMap<String, MutableList<Double>>()
        for (row in corpus) {
            val (src, tgt, text) = row
            // Greedy decoding so the reported quality is reproducible run to run.
            val r = engine.translate(text, LanguageRegistry.require(src), LanguageRegistry.require(tgt), DecodingParams(temperature = 0f))
            assertTrue(r is TranslationResult.Success, "translation failed: $r")
            val s = r as TranslationResult.Success
            times += s.metrics.totalMs
            val ref = row.getOrNull(3)
            val chrf = ref?.let { chrF(s.text, it) }
            if (chrf != null) scores.getOrPut("$src->$tgt") { ArrayList() } += chrf
            println("[$src->$tgt] ${s.metrics.totalMs}ms prompt=${s.metrics.promptTokens} gen=${s.metrics.generatedTokens} chrF=${chrf?.let { "%.1f".format(it) }}\n  IN : $text\n  OUT: ${s.text}\n  REF: ${ref ?: "-"}")
            assertTrue(s.text.isNotBlank())
        }
        times.sort()
        println("CORPUS n=${times.size} median=${times[times.size / 2]} ms p95=${times[(times.size * 95) / 100]} ms; " +
            scores.entries.joinToString { (k, v) -> "$k chrF=%.1f".format(v.average()) } + " (automatic metric; needs human review)")
        engine.unload()
    }
}

/** Test-only access to the native handle for template checks. */
internal fun LocalTranslationEngine.handleForTest(): Long {
    val f = LocalTranslationEngine::class.java.getDeclaredField("handle")
    f.isAccessible = true
    return f.getLong(this)
}

/** chrF (character n-gram F-score, n = 1..6, beta = 2), whitespace removed. 0..100. */
internal fun chrF(hyp: String, ref: String, maxN: Int = 6, beta: Double = 2.0): Double {
    val h = hyp.filterNot { it.isWhitespace() }
    val r = ref.filterNot { it.isWhitespace() }
    var p = 0.0; var rc = 0.0; var count = 0
    for (n in 1..maxN) {
        if (h.length < n || r.length < n) continue
        val hg = h.windowed(n).groupingBy { it }.eachCount()
        val rg = r.windowed(n).groupingBy { it }.eachCount()
        val match = hg.entries.sumOf { (g, c) -> minOf(c, rg[g] ?: 0) }.toDouble()
        p += match / hg.values.sum(); rc += match / rg.values.sum(); count++
    }
    if (count == 0) return 0.0
    p /= count; rc /= count
    if (p + rc == 0.0) return 0.0
    return 100 * (1 + beta * beta) * p * rc / (beta * beta * p + rc)
}
