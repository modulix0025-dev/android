package com.modulex.translator.engines

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechEngineTest {

    @Test
    fun missingModelFailsCleanly() = runBlocking<Unit> {
        assumeTrue("host native library not built", NativeLibraries.tryLoad("mx_ai").isSuccess)
        val r = LocalSpeechRecognizer()
        val e = r.load("/nonexistent/ggml-base.bin").exceptionOrNull() as NativeEngineException
        assertEquals("MODEL_NOT_INSTALLED", e.code)
        val notAModel = File.createTempFile("bogus", ".bin").apply { writeText("not a whisper model") }
        val e2 = r.load(notAModel.absolutePath).exceptionOrNull() as NativeEngineException
        assertEquals("MODEL_LOAD_FAILED", e2.code)
    }

    /**
     * Runs only with a real Whisper ggml model: -Pmodulex.testSpeechModel=/path/ggml-base.bin
     * Uses whisper.cpp's public-domain JFK sample (16 kHz mono PCM16).
     */
    @Test
    fun realModelTranscribesSample() = runBlocking<Unit> {
        val model = System.getProperty("modulex.testSpeechModel")
        assumeTrue("no real speech model provided (-Pmodulex.testSpeechModel)", model != null && File(model).isFile)
        val wav = File(System.getProperty("modulex.testAudio") ?: "../third_party/whisper.cpp/samples/jfk.wav")
        assumeTrue("sample audio missing", wav.isFile)
        val pcm = readPcm16Mono(wav)
        val r = LocalSpeechRecognizer()
        val t0 = System.nanoTime()
        r.load(model!!).getOrThrow()
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        val en = r.transcribe(pcm, "en", threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4))
        val auto = r.transcribe(pcm, "auto", threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4))
        println("SPEECH model load ms=$loadMs audio=${pcm.size / 16} ms recognition=${en.elapsedMs} ms text=${en.text.trim()}")
        println("SPEECH auto-detect language=${auto.language} recognition=${auto.elapsedMs} ms")
        val words = en.text.lowercase()
        assertTrue("country" in words && "ask" in words, "unexpected transcript: ${en.text}")
        assertEquals("en", auto.language)
        r.unload()
    }

    private fun readPcm16Mono(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        // Walk RIFF chunks to "data".
        var pos = 12
        while (pos + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(pos + it) }, Charsets.US_ASCII)
            val size = b.getInt(pos + 4)
            if (id == "data") {
                val n = size / 2
                return FloatArray(n) { b.getShort(pos + 8 + it * 2) / 32768f }
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk")
    }
}
