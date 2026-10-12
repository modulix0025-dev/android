package com.modulex.translator.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.modulex.translator.BuildConfig
import com.modulex.translator.assets.PackManager
import com.modulex.translator.audio.PcmPlayer
import com.modulex.translator.core.assets.PackKind
import com.modulex.translator.data.SettingsRepository
import com.modulex.translator.engines.AudioListener
import com.modulex.translator.engines.LocalVoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

enum class VoiceSource { OFFLINE_VOICE_PACK, SYSTEM_OFFLINE, NONE }

/**
 * Speaks translations without the network. Order of preference:
 *  1. an installed offline voice pack (Piper; only in builds that include it),
 *  2. a system voice that is installed on the phone and does not need a network connection.
 * If neither exists for a language the caller is told so; there is no online fallback.
 */
class SpeechOutput(
    private val context: Context,
    private val packs: PackManager,
    private val settings: SettingsRepository,
) {
    private val player = PcmPlayer()
    private val lock = Mutex()
    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()
    private val piper = LocalVoice()
    private var piperVoiceId: String? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status -> ttsReady.complete(status == TextToSpeech.SUCCESS) }
    }

    private fun voicePack(lang: String) = packs.manifest.packs.firstOrNull {
        it.kind == PackKind.TTS_VOICE && lang in it.languages && packs.usable(it.id)
    }

    private fun runtimePack() = packs.manifest.packs.firstOrNull { it.kind == PackKind.TTS_RUNTIME_DATA && packs.usable(it.id) }

    /** Offline system voice for [lang], if one is installed. */
    private fun systemVoice(lang: String): Voice? {
        if (!settings.settings.value.useSystemVoices || !ttsReady.isCompleted) return null
        val want = Locale.forLanguageTag(lang).language
        return runCatching { tts?.voices }.getOrNull().orEmpty()
            .filter { v ->
                v.locale.language == want && !v.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS !in v.features &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features
            }
            .maxByOrNull { it.quality }
    }

    fun source(lang: String): VoiceSource = when {
        BuildConfig.WITH_PIPER && LocalVoice.available && voicePack(lang) != null && runtimePack() != null -> VoiceSource.OFFLINE_VOICE_PACK
        systemVoice(lang) != null -> VoiceSource.SYSTEM_OFFLINE
        else -> VoiceSource.NONE
    }

    /** Speaks [text]; returns false (without speaking) when no offline voice exists. */
    suspend fun speak(text: String, lang: String): Boolean {
        ttsReady.await()
        stop()
        return lock.withLock {
            when (source(lang)) {
                VoiceSource.OFFLINE_VOICE_PACK -> speakPiper(text, lang)
                VoiceSource.SYSTEM_OFFLINE -> speakSystem(text, systemVoice(lang)!!)
                VoiceSource.NONE -> false
            }
        }
    }

    fun stop() {
        player.stop()
        piper.cancel()
        tts?.stop()
    }

    private suspend fun speakSystem(text: String, voice: Voice): Boolean {
        val engine = tts ?: return false
        val done = CompletableDeferred<Boolean>()
        val id = "mx-" + System.nanoTime()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(true) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { if (utteranceId == id) done.complete(false) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id) done.complete(false) }
        })
        engine.voice = voice
        engine.setSpeechRate(settings.settings.value.speechRate)
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) return false
        return done.await()
    }

    private suspend fun speakPiper(text: String, lang: String): Boolean {
        val pack = voicePack(lang) ?: return false
        val data = ensureEspeakData() ?: return false
        if (piperVoiceId != pack.id) {
            val ok = piper.load(
                packs.file(pack.id, "voice.onnx").absolutePath,
                packs.file(pack.id, "voice.onnx.json").absolutePath,
                data.absolutePath,
            ).isSuccess
            if (!ok) return false
            piperVoiceId = pack.id
        }
        player.play { sink ->
            piper.synthesize(text, settings.settings.value.speechRate, AudioListener { samples, rate, _ -> sink(samples, rate) })
        }
        return true
    }

    /** Extracts the verified espeak-ng data zip once (with path traversal protection). */
    private suspend fun ensureEspeakData(): File? = withContext(Dispatchers.IO) {
        val pack = runtimePack() ?: return@withContext null
        val dir = File(context.noBackupFilesDir, "espeak-${pack.version}")
        val marker = File(dir, ".complete")
        if (marker.isFile) return@withContext File(dir, "espeak-ng-data")
        dir.deleteRecursively(); dir.mkdirs()
        val root = dir.canonicalPath + File.separator
        ZipInputStream(packs.file(pack.id, pack.files.first().name).inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(dir, e.name)
                if (!out.canonicalPath.startsWith(root)) error("unsafe zip entry")
                if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
            }
        }
        marker.writeText("ok")
        File(dir, "espeak-ng-data")
    }

    suspend fun shutdown() {
        stop()
        piper.close()
        tts?.shutdown()
        tts = null
    }
}
