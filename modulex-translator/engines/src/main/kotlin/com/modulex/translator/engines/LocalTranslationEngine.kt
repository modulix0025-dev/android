package com.modulex.translator.engines

import com.modulex.translator.core.assets.GgufInfo
import com.modulex.translator.core.language.Language
import com.modulex.translator.core.translation.DecodingParams
import com.modulex.translator.core.translation.ModelInfo
import com.modulex.translator.core.translation.OutputSanitizer
import com.modulex.translator.core.translation.PromptBuilder
import com.modulex.translator.core.translation.TextSegmenter
import com.modulex.translator.core.translation.TranslationEngine
import com.modulex.translator.core.translation.TranslationError
import com.modulex.translator.core.translation.TranslationMetrics
import com.modulex.translator.core.translation.TranslationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/**
 * The offline translation engine: llama.cpp running a local GGUF translation model.
 *
 * Requests are serialised (one native context); a new request never interleaves with a running one.
 * Long inputs are split at safe boundaries and translated piece by piece.
 */
class LocalTranslationEngine(
    /** Expected `general.architecture` of the model, checked before the native load. */
    private val expectedArchitecture: String? = "hunyuan-dense",
    private val maxPieceChars: Int = 1_000,
) : TranslationEngine {
    private val mutex = Mutex()
    @Volatile private var handle = 0L
    @Volatile private var cancelled = false

    override val isLoaded: Boolean get() = handle != 0L

    override suspend fun load(modelPath: String, threads: Int, contextSize: Int): Result<ModelInfo> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val file = File(modelPath)
                if (!file.isFile) throw NativeEngineException("MODEL_NOT_INSTALLED", "model file missing")
                // Reject wrong files before handing them to native code.
                val header = GgufInfo.read(file)
                if (expectedArchitecture != null && header.architecture != expectedArchitecture) {
                    throw NativeEngineException("MODEL_INCOMPATIBLE", "unexpected model architecture ${header.architecture}")
                }
                unloadLocked()
                handle = NativeTranslator.nativeLoad(modelPath, threads, contextSize)
                val d = Json.parseToJsonElement(NativeTranslator.nativeDescribe(handle)).jsonObject
                ModelInfo(
                    architecture = d["architecture"]!!.jsonPrimitive.content,
                    name = d["name"]!!.jsonPrimitive.content,
                    parameterCount = d["parameters"]!!.jsonPrimitive.long,
                    sizeBytes = d["sizeBytes"]!!.jsonPrimitive.long,
                    contextTrain = d["contextTrain"]!!.jsonPrimitive.int,
                    hasChatTemplate = d["hasChatTemplate"]!!.jsonPrimitive.boolean,
                )
            }
        }
    }

    override suspend fun translate(text: String, source: Language, target: Language, params: DecodingParams): TranslationResult =
        translateStreaming(text, source, target, params) { }

    /**
     * Translates [text]; [onPartial] receives the accumulated output so far (for live display).
     */
    suspend fun translateStreaming(
        text: String, source: Language, target: Language, params: DecodingParams = DecodingParams(),
        onPartial: (String) -> Unit,
    ): TranslationResult = withContext(Dispatchers.IO) {
        if (!source.translation || !target.translation) return@withContext TranslationResult.Failure(TranslationError.UNSUPPORTED_LANGUAGE)
        mutex.withLock {
            if (handle == 0L) return@withLock TranslationResult.Failure(TranslationError.MODEL_NOT_INSTALLED)
            cancelled = false
            val pieces = TextSegmenter.split(text, maxPieceChars)
            if (pieces.isEmpty()) return@withLock TranslationResult.Failure(TranslationError.EMPTY_OUTPUT)
            val outputs = ArrayList<String>(pieces.size)
            var prompt = 0; var gen = 0; var prefill = 0L; var decode = 0L
            val start = System.nanoTime()
            for (piece in pieces) {
                if (cancelled) return@withLock TranslationResult.Cancelled
                val metrics = LongArray(4)
                val raw = try {
                    val formatted = NativeTranslator.nativeFormatPrompt(handle, PromptBuilder.build(piece.text, source, target))
                    // Text already translated (with the original separators) precedes the live piece.
                    val prefix = StringBuilder()
                    outputs.forEachIndexed { i, o -> prefix.append(o).append(pieces[i].separatorAfter) }
                    val acc = StringBuilder()
                    NativeTranslator.nativeGenerate(
                        handle, formatted, params.temperature, params.topP, params.topK, params.repetitionPenalty,
                        params.maxNewTokens, params.seed, { p ->
                            acc.append(p)
                            onPartial(prefix.toString() + acc.toString().trimStart())
                            !cancelled
                        }, metrics,
                    )
                } catch (e: NativeEngineException) {
                    return@withLock when (e.code) {
                        "CANCELLED" -> TranslationResult.Cancelled
                        "INPUT_TOO_LONG" -> TranslationResult.Failure(TranslationError.INPUT_TOO_LONG)
                        else -> TranslationResult.Failure(TranslationError.INFERENCE_FAILED, e.message)
                    }
                } catch (e: OutOfMemoryError) {
                    return@withLock TranslationResult.Failure(TranslationError.OUT_OF_MEMORY)
                }
                prompt += metrics[0].toInt(); gen += metrics[1].toInt(); prefill += metrics[2]; decode += metrics[3]
                val cleaned = OutputSanitizer.clean(raw)
                if (cleaned.isEmpty()) return@withLock TranslationResult.Failure(TranslationError.EMPTY_OUTPUT)
                outputs += cleaned
            }
            val result = TextSegmenter.join(outputs, pieces)
            onPartial(result)
            TranslationResult.Success(
                result,
                TranslationMetrics(prompt, gen, prefill / 1000, decode / 1000, (System.nanoTime() - start) / 1_000_000),
            )
        }
    }

    override fun cancel() {
        cancelled = true
        val h = handle
        if (h != 0L) NativeTranslator.nativeCancel(h)
    }

    override fun unload() {
        cancel()
        // Wait for any running generation to observe the cancellation before freeing.
        kotlinx.coroutines.runBlocking { mutex.withLock { unloadLocked() } }
    }

    private fun unloadLocked() {
        val h = handle
        handle = 0
        if (h != 0L) NativeTranslator.nativeFree(h)
    }
}
