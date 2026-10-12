package com.modulex.translator.engines

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val json = Json { ignoreUnknownKeys = true }

// ------------------------------------------------------------------ speech recognition

@Serializable
data class TranscriptSegment(val t0: Long, val t1: Long, val text: String, val noSpeech: Float)

@Serializable
data class Transcript(val text: String, val language: String, val elapsedMs: Long, val segments: List<TranscriptSegment>) {
    /** True when every segment is most likely silence/noise. */
    val probablyNoSpeech: Boolean get() = segments.isEmpty() || segments.all { it.noSpeech > 0.6f }
}

/** Offline speech recognition (whisper.cpp, 16 kHz mono float PCM). One utterance at a time. */
class LocalSpeechRecognizer {
    private val mutex = Mutex()
    @Volatile private var handle = 0L

    val isLoaded get() = handle != 0L

    suspend fun load(modelPath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                if (!File(modelPath).isFile) throw NativeEngineException("MODEL_NOT_INSTALLED", "speech model missing")
                freeLocked()
                handle = NativeSpeech.nativeLoad(modelPath)
                NativeSpeech.nativeIsMultilingual(handle)
            }
        }
    }

    /**
     * @param language Whisper code, or "auto" to detect (multilingual models only).
     * @param partial Faster single-segment decoding for live previews.
     */
    suspend fun transcribe(pcm: FloatArray, language: String, threads: Int, partial: Boolean = false, prompt: String = ""): Transcript =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (handle == 0L) throw NativeEngineException("MODEL_NOT_LOADED", "speech model not loaded")
                json.decodeFromString(Transcript.serializer(), NativeSpeech.nativeTranscribe(handle, pcm, language, threads, partial, prompt))
            }
        }

    fun cancel() { handle.takeIf { it != 0L }?.let(NativeSpeech::nativeCancel) }

    suspend fun unload() {
        cancel()
        mutex.withLock { freeLocked() }
    }

    private fun freeLocked() {
        val h = handle
        handle = 0
        if (h != 0L) NativeSpeech.nativeFree(h)
    }
}

// ------------------------------------------------------------------ OCR

@Serializable
data class OcrLine(val text: String, val conf: Float, val x: Int, val y: Int, val w: Int, val h: Int, val block: Int, val para: Int)

@Serializable
data class OcrResult(val text: String, val meanConfidence: Float, val elapsedMs: Long, val lines: List<OcrLine>)

/** Tesseract page segmentation modes used by the app. */
enum class PageLayout(val psm: Int) { AUTO(3), SINGLE_BLOCK(6), SINGLE_LINE(7), SPARSE(11) }

/** Local text recognition (Tesseract LSTM). Initialised per language set, e.g. "ara+eng". */
class LocalTextRecognizer {
    private val mutex = Mutex()
    @Volatile private var handle = 0L
    @Volatile var languages: String? = null
        private set

    suspend fun init(tessdataDir: String, languages: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                require(Regex("^[a-z_]+(\\+[a-z_]+)*$").matches(languages)) { "bad language list" }
                for (l in languages.split('+')) {
                    if (!File(tessdataDir, "$l.traineddata").isFile) throw NativeEngineException("MODEL_NOT_INSTALLED", "missing $l")
                }
                freeLocked()
                handle = NativeOcr.nativeInit(tessdataDir, languages)
                this@LocalTextRecognizer.languages = languages
            }
        }
    }

    /** [pixels]: gray (1), RGB (3) or RGBA (4) bytes per pixel. */
    suspend fun recognize(pixels: ByteArray, width: Int, height: Int, bytesPerPixel: Int, bytesPerLine: Int = width * bytesPerPixel,
                          layout: PageLayout = PageLayout.AUTO): OcrResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (handle == 0L) throw NativeEngineException("MODEL_NOT_LOADED", "text recognition not initialised")
            json.decodeFromString(OcrResult.serializer(), NativeOcr.nativeRecognize(handle, pixels, width, height, bytesPerPixel, bytesPerLine, layout.psm))
        }
    }

    suspend fun close() = mutex.withLock { freeLocked() }

    private fun freeLocked() {
        val h = handle
        handle = 0
        languages = null
        if (h != 0L) NativeOcr.nativeFree(h)
    }
}

// ------------------------------------------------------------------ voices

/** Offline neural voice (Piper). Only available in builds that include the GPL voice engine. */
class LocalVoice {
    private val mutex = Mutex()
    @Volatile private var handle = 0L

    companion object {
        /** False in builds without libmx_tts.so. */
        val available: Boolean by lazy { NativeLibraries.tryLoad("mx_tts").isSuccess }
    }

    suspend fun load(model: String, config: String, espeakData: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                if (!available) throw NativeEngineException("ENGINE_UNAVAILABLE", "voice engine not included in this build")
                freeLocked()
                handle = NativeVoice.nativeLoad(model, config, espeakData)
            }
        }
    }

    suspend fun synthesize(text: String, rate: Float, listener: AudioListener) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (handle == 0L) throw NativeEngineException("MODEL_NOT_LOADED", "voice not loaded")
            NativeVoice.nativeSynthesize(handle, text, rate, listener)
        }
    }

    fun cancel() { handle.takeIf { it != 0L }?.let(NativeVoice::nativeCancel) }

    suspend fun close() = mutex.withLock {
        val h = handle
        handle = 0
        if (h != 0L) NativeVoice.nativeFree(h)
    }

    private fun freeLocked() {
        val h = handle
        handle = 0
        if (h != 0L) NativeVoice.nativeFree(h)
    }
}
