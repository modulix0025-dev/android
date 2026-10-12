package com.modulex.translator.core.language

import com.modulex.translator.core.assets.AssetManifest
import com.modulex.translator.core.assets.AssetPack
import com.modulex.translator.core.assets.PackKind
import com.modulex.translator.core.assets.PackStatus

enum class FeatureState {
    /** Engine and assets installed and verified (or explicitly user-imported). */
    READY,
    /** Supported, but a pack must be installed first. */
    NEEDS_PACK,
    /** A pack exists in the catalogue but has no pinned hash yet, so it cannot be downloaded. */
    PACK_UNVERIFIED,
    /** No engine/asset supports this language for this feature. */
    UNSUPPORTED,
}

data class LanguageFeatures(
    val language: Language,
    val translation: FeatureState,
    val speechInput: FeatureState,
    val ocr: FeatureState,
    val speechOutput: FeatureState,
    /** Pack ids needed to make every supported feature ready. */
    val missingPacks: List<String>,
) {
    /** Fully offline voice conversation: recognise, translate and speak. */
    val voiceConversationReady get() = translation == FeatureState.READY && speechInput == FeatureState.READY
}

/**
 * Computes per-language readiness from the manifest and the current install status. A feature is
 * only READY when every pack it depends on is installed and passed verification.
 */
class LanguageReadiness(
    private val manifest: AssetManifest,
    private val status: (AssetPack) -> PackStatus,
    /** Extra availability for speech output provided outside the manifest (e.g. verified offline system voices). */
    private val externalTts: (String) -> Boolean = { false },
) {
    private fun usable(p: AssetPack) = status(p).let { it == PackStatus.INSTALLED_VERIFIED || it == PackStatus.INSTALLED_USER_IMPORT }

    private fun state(packs: List<AssetPack>, missing: MutableList<String>): FeatureState {
        if (packs.isEmpty()) return FeatureState.UNSUPPORTED
        if (packs.any(::usable)) return FeatureState.READY
        val candidate = packs.firstOrNull { it.isDownloadable } ?: packs.first()
        missing += candidate.id
        return if (packs.any { it.isDownloadable || it.bundled }) FeatureState.NEEDS_PACK else FeatureState.PACK_UNVERIFIED
    }

    private fun packsFor(kind: PackKind, code: String) =
        manifest.packs.filter { it.kind == kind && (it.languages.isEmpty() || code in it.languages) }

    fun features(language: Language): LanguageFeatures {
        val missing = ArrayList<String>()
        val translation = if (language.translation) state(packsFor(PackKind.TRANSLATION_MODEL, language.code), missing) else FeatureState.UNSUPPORTED
        val speech = if (language.speechRecognition) state(packsFor(PackKind.SPEECH_MODEL, language.code), missing) else FeatureState.UNSUPPORTED
        val ocr = if (language.ocr) {
            val needed = language.ocrPacks.mapNotNull { name -> manifest.packs.firstOrNull { it.kind == PackKind.OCR_LANGUAGE && it.meta["traineddata"] == name } }
            if (needed.size < language.ocrPacks.size) FeatureState.UNSUPPORTED
            else needed.map { state(listOf(it), missing) }.let { states ->
                when {
                    states.all { it == FeatureState.READY } -> FeatureState.READY
                    states.any { it == FeatureState.PACK_UNVERIFIED } -> FeatureState.PACK_UNVERIFIED
                    else -> FeatureState.NEEDS_PACK
                }
            }
        } else FeatureState.UNSUPPORTED
        val voices = packsFor(PackKind.TTS_VOICE, language.code).filter { it.languages.isNotEmpty() }
        val tts = when {
            externalTts(language.code) -> FeatureState.READY
            voices.isEmpty() -> FeatureState.UNSUPPORTED
            else -> {
                val runtime = manifest.packs.filter { it.kind == PackKind.TTS_RUNTIME_DATA }
                val voiceState = state(voices, missing)
                val runtimeState = if (runtime.isEmpty()) FeatureState.READY else state(runtime, missing)
                if (voiceState == FeatureState.READY && runtimeState == FeatureState.READY) FeatureState.READY
                else if (voiceState == FeatureState.PACK_UNVERIFIED || runtimeState == FeatureState.PACK_UNVERIFIED) FeatureState.PACK_UNVERIFIED
                else FeatureState.NEEDS_PACK
            }
        }
        return LanguageFeatures(language, translation, speech, ocr, tts, missing.distinct())
    }

    fun all(): List<LanguageFeatures> = LanguageRegistry.all.map(::features)
}
