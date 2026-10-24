package com.modulex.translator

import android.app.Application
import android.content.Context
import com.modulex.translator.assets.PackManager
import com.modulex.translator.audio.AudioCapture
import com.modulex.translator.audio.VoicePipeline
import com.modulex.translator.conversation.ConversationController
import com.modulex.translator.data.HistoryRepository
import com.modulex.translator.data.SettingsRepository
import com.modulex.translator.engine.EngineHub
import com.modulex.translator.platform.DeviceStatus
import com.modulex.translator.security.DeviceIdentity
import com.modulex.translator.security.SecretBox
import com.modulex.translator.tts.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.modulex.translator.core.translation.Glossary
import com.modulex.translator.core.translation.TranslationMemory
import kotlinx.coroutines.launch

/** Manual dependency container: a handful of app-wide singletons, created lazily. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings by lazy { SettingsRepository(app, SecretBox()) }
    val history by lazy { HistoryRepository(app) }
    val device by lazy { DeviceStatus(app) }
    val packs by lazy { PackManager(app, scope) }
    /** Terminology hints shipped in assets/glossary (<from>-<to>.tsv). */
    val glossary by lazy {
        val files = app.assets.list("glossary").orEmpty().filter { it.endsWith(".tsv") }
        Glossary(files.flatMap { name ->
            val (from, to) = name.substringBefore(".").split("-") // <from>-<to>[.<list>].tsv
            Glossary.parseTsv(app.assets.open("glossary/$name").bufferedReader().use { it.readText() }, from, to)
        })
    }
    /** Verified dialect sentence pairs shipped in assets/tm (<dialect>.tsv, see tools/tm_build.py). */
    val translationMemory by lazy {
        val files = app.assets.list("tm").orEmpty().filter { it.endsWith(".tsv") }
        TranslationMemory(files.flatMap { name ->
            val lang = TranslationMemory.baseLang(name.removeSuffix(".tsv"))
            // The `en` column holds English, except in English-dialect corpora where it holds Arabic.
            TranslationMemory.parseTsv(app.assets.open("tm/$name").bufferedReader().use { it.readText() }, lang,
                if (lang == "en") "ar" else "en")
        })
    }
    val engines by lazy {
        EngineHub(packs, device, settings).also { it.setGlossary(glossary); it.setTranslationMemory(translationMemory) }
    }
    val capture by lazy { AudioCapture(app) }
    val voice by lazy { VoicePipeline(engines, capture) }
    val speech by lazy { SpeechOutput(app, packs, settings) }
    val identity by lazy { DeviceIdentity() }
    val conversation by lazy { ConversationController(app, engines, voice, capture, speech, identity, settings, history) }
}

class ModuleXApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scope.launch {
            // Bundled Arabic/English text recognition data is verified and installed on first start.
            runCatching { container.packs.installBundled() }
            container.history.refresh()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        container.scope.launch { container.engines.onTrimMemory(level) }
    }
}

val Context.container: AppContainer get() = (applicationContext as ModuleXApp).container
