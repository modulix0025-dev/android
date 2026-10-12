package com.modulex.translator.engine

import android.content.ComponentCallbacks2
import com.modulex.translator.assets.PackManager
import com.modulex.translator.core.assets.PackKind
import com.modulex.translator.core.language.Language
import com.modulex.translator.core.resources.HeavyEngine
import com.modulex.translator.core.resources.InferenceGate
import com.modulex.translator.core.resources.ResourcePolicy
import com.modulex.translator.core.resources.ThermalLevel
import com.modulex.translator.core.translation.DecodingParams
import com.modulex.translator.core.translation.ModelInfo
import com.modulex.translator.core.translation.TranslationError
import com.modulex.translator.core.translation.TranslationResult
import com.modulex.translator.data.SettingsRepository
import com.modulex.translator.engines.LocalSpeechRecognizer
import com.modulex.translator.engines.LocalTextRecognizer
import com.modulex.translator.engines.LocalTranslationEngine
import com.modulex.translator.engines.NativeEngineException
import com.modulex.translator.engines.OcrPipeline
import com.modulex.translator.engines.PageLayout
import com.modulex.translator.engines.Transcript
import com.modulex.translator.platform.CpuSupport
import com.modulex.translator.platform.DeviceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface EngineState {
    data object NotInstalled : EngineState
    data object Idle : EngineState
    data object Loading : EngineState
    data class Ready(val detail: String) : EngineState
    data class Failed(val code: String) : EngineState
    /** The CPU lacks the instructions the native engines were built for. */
    data object UnsupportedDevice : EngineState
}

/** Thrown when work is refused because the device is too hot. */
class ThermalRefusal : Exception("device too hot")

/**
 * Owns the native engines and decides when they are loaded. Heavy models are loaded on first use,
 * never all at once, and released under memory pressure.
 */
class EngineHub(
    private val packs: PackManager,
    private val device: DeviceStatus,
    private val settings: SettingsRepository,
) {
    private val translator = LocalTranslationEngine(expectedArchitecture = null)
    private val speech = LocalSpeechRecognizer()
    private val ocr = LocalTextRecognizer()
    val gate = InferenceGate(maxConcurrent = 2)

    private val loadLock = Mutex()
    private val loaded = HashMap<HeavyEngine, Long>()
    private var translatorPack: String? = null
    private var speechPack: String? = null

    private val _translatorState = MutableStateFlow<EngineState>(EngineState.Idle)
    val translatorState: StateFlow<EngineState> = _translatorState.asStateFlow()
    private val _speechState = MutableStateFlow<EngineState>(EngineState.Idle)
    val speechState: StateFlow<EngineState> = _speechState.asStateFlow()

    @Volatile var lastModelInfo: ModelInfo? = null
        private set
    @Volatile var lastLoadMs: Long = -1
        private set

    private fun cpuOk(state: MutableStateFlow<EngineState>): Boolean {
        if (!CpuSupport.supported) { state.value = EngineState.UnsupportedDevice; return false }
        return true
    }

    private fun checkThermal() {
        if (device.thermal.value >= ThermalLevel.CRITICAL) throw ThermalRefusal()
    }

    private suspend fun makeRoom(engine: HeavyEngine, bytes: Long) {
        for (e in ResourcePolicy.evictionsFor(engine, loaded, device.memory(), bytes)) release(e)
    }

    private suspend fun release(e: HeavyEngine) {
        when (e) {
            HeavyEngine.TRANSLATION -> { translator.unload(); translatorPack?.let(packs::release); translatorPack = null; _translatorState.value = EngineState.Idle }
            HeavyEngine.SPEECH -> { speech.unload(); speechPack?.let(packs::release); speechPack = null; _speechState.value = EngineState.Idle }
            HeavyEngine.OCR -> ocr.close()
            HeavyEngine.TTS -> Unit
        }
        loaded.remove(e)
    }

    /** Loads the translation model if needed. */
    suspend fun ensureTranslator(): Boolean = loadLock.withLock {
        if (translator.isLoaded) return true
        if (!cpuOk(_translatorState)) return false
        val pack = packs.firstUsable(PackKind.TRANSLATION_MODEL)
        if (pack == null) { _translatorState.value = EngineState.NotInstalled; return false }
        val file = packs.file(pack.id, pack.files.first().name)
        _translatorState.value = EngineState.Loading
        // The manifest states which architecture the pack must contain; anything else is refused.
        val expected = pack.meta["expected_architecture"]
        val actual = runCatching { com.modulex.translator.core.assets.GgufInfo.read(file).architecture }.getOrNull()
        if (expected != null && actual != expected) {
            _translatorState.value = EngineState.Failed("MODEL_INCOMPATIBLE")
            return false
        }
        makeRoom(HeavyEngine.TRANSLATION, file.length() + (300L shl 20))
        val t0 = System.nanoTime()
        val result = translator.load(file.absolutePath, device.budget().inferenceThreads, contextSize = 4096)
        lastLoadMs = (System.nanoTime() - t0) / 1_000_000
        return result.fold(
            onSuccess = { info ->
                lastModelInfo = info
                packs.acquire(pack.id)
                translatorPack = pack.id
                loaded[HeavyEngine.TRANSLATION] = file.length()
                _translatorState.value = EngineState.Ready(info.name)
                true
            },
            onFailure = { e ->
                _translatorState.value = EngineState.Failed((e as? NativeEngineException)?.code ?: "MODEL_LOAD_FAILED")
                false
            },
        )
    }

    suspend fun translate(
        text: String, source: Language, target: Language, onPartial: (String) -> Unit = {},
    ): TranslationResult {
        try { checkThermal() } catch (e: ThermalRefusal) { return TranslationResult.Failure(TranslationError.INFERENCE_FAILED, "device_too_hot") }
        if (!ensureTranslator()) {
            return TranslationResult.Failure(
                if (_translatorState.value == EngineState.NotInstalled) TranslationError.MODEL_NOT_INSTALLED else TranslationError.MODEL_LOAD_FAILED,
            )
        }
        return gate.run(HeavyEngine.TRANSLATION) {
            translator.translateStreaming(text, source, target, DecodingParams(), onPartial)
        }
    }

    fun cancelTranslation() = translator.cancel()

    suspend fun ensureSpeech(): Boolean = loadLock.withLock {
        if (speech.isLoaded) return true
        if (!cpuOk(_speechState)) return false
        val pack = packs.firstUsable(PackKind.SPEECH_MODEL)
        if (pack == null) { _speechState.value = EngineState.NotInstalled; return false }
        val file = packs.file(pack.id, pack.files.first().name)
        _speechState.value = EngineState.Loading
        makeRoom(HeavyEngine.SPEECH, file.length() + (200L shl 20))
        return speech.load(file.absolutePath).fold(
            onSuccess = {
                packs.acquire(pack.id); speechPack = pack.id
                loaded[HeavyEngine.SPEECH] = file.length()
                _speechState.value = EngineState.Ready(pack.version)
                true
            },
            onFailure = { e ->
                _speechState.value = EngineState.Failed((e as? NativeEngineException)?.code ?: "MODEL_LOAD_FAILED")
                false
            },
        )
    }

    suspend fun transcribe(pcm: FloatArray, whisperLanguage: String, partial: Boolean): Transcript {
        checkThermal()
        check(ensureSpeech()) { "speech engine unavailable" }
        val threads = device.budget().inferenceThreads
        return gate.run(HeavyEngine.SPEECH) { speech.transcribe(pcm, whisperLanguage, threads, partial) }
    }

    fun cancelSpeech() = speech.cancel()

    /** Prepares text recognition for the given traineddata names (e.g. setOf("ara", "eng")). */
    suspend fun ensureOcr(models: Set<String>): Boolean = loadLock.withLock {
        if (!cpuOk(MutableStateFlow(EngineState.Idle))) return false
        val installed = packs.installedOcrModels()
        val use = models.filter { it in installed }.sorted()
        if (use.isEmpty()) return false
        val key = use.joinToString("+")
        if (ocr.languages == key) return true
        makeRoom(HeavyEngine.OCR, HeavyEngine.OCR.estimatedBytes)
        return ocr.init(packs.tessdataDir.absolutePath, key).isSuccess.also { if (it) loaded[HeavyEngine.OCR] = HeavyEngine.OCR.estimatedBytes }
    }

    suspend fun recognize(rgba: ByteArray, width: Int, height: Int): OcrPipeline.Result {
        checkThermal()
        return gate.run(HeavyEngine.OCR) { OcrPipeline(ocr).recognize(rgba, width, height, 4, layout = PageLayout.AUTO) }
    }

    /** Called from Application.onTrimMemory. */
    suspend fun onTrimMemory(level: Int) = loadLock.withLock {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            release(HeavyEngine.OCR)
            release(HeavyEngine.SPEECH)
            if (!settings.settings.value.keepTranslationLoaded || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                release(HeavyEngine.TRANSLATION)
            }
        }
    }

    suspend fun releaseAll() = loadLock.withLock { HeavyEngine.entries.forEach { release(it) } }

    fun loadedEngines(): Map<HeavyEngine, Long> = HashMap(loaded)
}
