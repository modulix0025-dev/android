package com.modulex.translator.engines

import java.io.File

/** Raised by the native layer. [code] is a stable machine-readable error code. */
class NativeEngineException(val code: String, message: String) : Exception(message)

/**
 * Loads the JNI libraries. On Android they come from the APK (System.loadLibrary); host tests point
 * `modulex.native.dir` at the host CMake build.
 */
object NativeLibraries {
    private val loaded = HashSet<String>()

    @Synchronized
    fun load(name: String) {
        if (name in loaded) return
        val dir = System.getProperty("modulex.native.dir")
        if (dir != null) {
            System.load(File(dir, System.mapLibraryName(name)).absolutePath)
        } else {
            System.loadLibrary(name)
        }
        loaded += name
    }

    /** True when the library can be loaded on this device (e.g. CPU features present). */
    fun tryLoad(name: String): Result<Unit> = runCatching { load(name) }
}

/** Receives generated text increments; return false to stop. */
fun interface TextListener {
    fun onText(piece: String): Boolean
}

/** Receives synthesized audio; return false to stop. */
fun interface AudioListener {
    fun onAudio(samples: FloatArray, sampleRate: Int, isLast: Boolean): Boolean
}

internal object NativeTranslator {
    init { NativeLibraries.load("mx_ai") }

    @JvmStatic external fun nativeLoad(path: String, threads: Int, nCtx: Int): Long
    @JvmStatic external fun nativeDescribe(handle: Long): String
    @JvmStatic external fun nativeFormatPrompt(handle: Long, user: String): String
    @JvmStatic external fun nativeGenerate(
        handle: Long, prompt: String, temperature: Float, topP: Float, topK: Int, repetitionPenalty: Float,
        maxTokens: Int, seed: Int, listener: TextListener?, metricsOut: LongArray,
    ): String
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeFree(handle: Long)
}

internal object NativeSpeech {
    init { NativeLibraries.load("mx_ai") }

    @JvmStatic external fun nativeLoad(path: String): Long
    @JvmStatic external fun nativeIsMultilingual(handle: Long): Boolean
    @JvmStatic external fun nativeTranscribe(handle: Long, pcm: FloatArray, language: String, threads: Int, partial: Boolean, prompt: String): String
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeFree(handle: Long)
}

internal object NativeOcr {
    init { NativeLibraries.load("mx_ocr") }

    @JvmStatic external fun nativeInit(tessdataDir: String, languages: String): Long
    @JvmStatic external fun nativeRecognize(handle: Long, pixels: ByteArray, width: Int, height: Int, bytesPerPixel: Int, bytesPerLine: Int, pageSegMode: Int): String
    @JvmStatic external fun nativeFree(handle: Long)
}

internal object NativeVoice {
    init { NativeLibraries.load("mx_tts") }

    @JvmStatic external fun nativeLoad(model: String, config: String, espeakData: String): Long
    @JvmStatic external fun nativeSynthesize(handle: Long, text: String, rate: Float, listener: AudioListener)
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeFree(handle: Long)
}
