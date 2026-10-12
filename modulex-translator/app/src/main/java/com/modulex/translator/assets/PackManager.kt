package com.modulex.translator.assets

import android.content.Context
import android.net.Uri
import android.system.Os
import com.modulex.translator.core.assets.AssetManifest
import com.modulex.translator.core.assets.AssetPack
import com.modulex.translator.core.assets.AssetStore
import com.modulex.translator.core.assets.HttpByteSource
import com.modulex.translator.core.assets.InstallFailure
import com.modulex.translator.core.assets.InstallOutcome
import com.modulex.translator.core.assets.InstallProgress
import com.modulex.translator.core.assets.PackKind
import com.modulex.translator.core.assets.PackStatus
import com.modulex.translator.core.language.LanguageReadiness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class PackState(
    val pack: AssetPack,
    val status: PackStatus,
    val progress: InstallProgress? = null,
    val lastFailure: InstallFailure? = null,
    val installedBytes: Long = 0,
) {
    val busy: Boolean get() = progress != null
    val usable: Boolean get() = status == PackStatus.INSTALLED_VERIFIED || status == PackStatus.INSTALLED_USER_IMPORT
}

/**
 * Language packs: what is installed, verified, downloading. Nothing is downloaded without an
 * explicit call from a user action; every file is size- and SHA-256-checked before use.
 */
class PackManager(private val context: Context, private val scope: CoroutineScope) {
    val manifest: AssetManifest = context.assets.open("asset_manifest.json").use { AssetManifest.parse(it.reader().readText()) }
    // noBackupFilesDir: large, re-downloadable data that must never go to cloud backup.
    val store = AssetStore(File(context.noBackupFilesDir, "packs"))
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _states = MutableStateFlow(manifest.packs.associate { it.id to stateOf(it) })
    val states: StateFlow<Map<String, PackState>> = _states.asStateFlow()

    private fun stateOf(p: AssetPack, progress: InstallProgress? = null, failure: InstallFailure? = null): PackState {
        val rec = store.record(p.id)
        return PackState(p, store.status(p), progress, failure, rec?.sizes?.values?.sum() ?: 0)
    }

    private fun refresh(id: String, progress: InstallProgress? = null, failure: InstallFailure? = null) {
        val p = manifest.pack(id) ?: return
        _states.update { it + (id to stateOf(p, progress, failure)) }
    }

    fun refreshAll() { _states.value = manifest.packs.associate { it.id to stateOf(it) } }

    fun readiness(externalTts: (String) -> Boolean = { false }) =
        LanguageReadiness(manifest, { p -> _states.value[p.id]?.status ?: store.status(p) }, externalTts)

    /** Copies packs shipped inside the APK into place (verified against their pinned hashes). */
    suspend fun installBundled() = withContext(Dispatchers.IO) {
        for (p in manifest.packs.filter { it.bundled }) {
            if (store.status(p) == PackStatus.INSTALLED_VERIFIED) continue
            val streams = p.files.associate { f -> f.name to context.assets.open("tessdata/${f.name}") }
            try {
                store.importFiles(p, streams)
            } finally {
                streams.values.forEach { runCatching { it.close() } }
            }
            refresh(p.id)
        }
        rebuildTessdataView()
    }

    fun requiredBytes(id: String): Long? = manifest.pack(id)?.let(store::requiredFreeBytes)
    fun hasRoomFor(id: String): Boolean = manifest.pack(id)?.let(store::hasRoomFor) ?: false

    /** Starts (or resumes) a download. The UI must have obtained the user's consent first. */
    fun download(id: String) {
        val p = manifest.pack(id) ?: return
        if (jobs[id]?.isActive == true) return
        jobs[id] = scope.launch {
            refresh(id, InstallProgress.Checking(id))
            val outcome = store.install(p, HttpByteSource()) { prog -> refresh(id, prog) }
            when (outcome) {
                is InstallOutcome.Installed -> refresh(id)
                is InstallOutcome.Failed -> refresh(id, failure = outcome.reason)
            }
            if (p.kind == PackKind.OCR_LANGUAGE) rebuildTessdataView()
            jobs.remove(id)
        }
    }

    fun cancelDownload(id: String) {
        jobs.remove(id)?.cancel()
        refresh(id, failure = InstallFailure.CANCELLED)
    }

    /** Imports user-chosen files (one per pack file, in order). */
    suspend fun import(id: String, uris: List<Uri>): InstallOutcome {
        val p = manifest.pack(id) ?: return InstallOutcome.Failed(InstallFailure.IO, "unknown pack")
        if (uris.size != p.files.size) return InstallOutcome.Failed(InstallFailure.IO, "expected ${p.files.size} files")
        refresh(id, InstallProgress.Committing)
        val streams = p.files.mapIndexed { i, f -> f.name to context.contentResolver.openInputStream(uris[i])!! }.toMap()
        val outcome = try {
            store.importFiles(p, streams)
        } finally {
            streams.values.forEach { runCatching { it.close() } }
        }
        refresh(id, failure = (outcome as? InstallOutcome.Failed)?.reason)
        if (p.kind == PackKind.OCR_LANGUAGE) rebuildTessdataView()
        return outcome
    }

    fun delete(id: String): Boolean {
        val ok = store.delete(id)
        refresh(id)
        if (manifest.pack(id)?.kind == PackKind.OCR_LANGUAGE) rebuildTessdataView()
        return ok
    }

    suspend fun verify(id: String): Boolean {
        val p = manifest.pack(id) ?: return false
        val ok = store.verify(p)
        refresh(id)
        return ok
    }

    fun usable(id: String) = _states.value[id]?.usable == true

    fun firstUsable(kind: PackKind): AssetPack? =
        manifest.packs.firstOrNull { it.kind == kind && usable(it.id) }

    fun file(id: String, name: String): File = store.file(id, name)

    /** Tesseract wants one data directory: expose installed traineddata files via symlinks. */
    val tessdataDir: File get() = File(context.noBackupFilesDir, "tessdata-view")

    fun installedOcrModels(): Set<String> =
        manifest.packs.filter { it.kind == PackKind.OCR_LANGUAGE && usable(it.id) }.mapNotNull { it.meta["traineddata"] }.toSet()

    @Synchronized
    fun rebuildTessdataView() {
        val dir = tessdataDir
        dir.deleteRecursively()
        dir.mkdirs()
        for (p in manifest.packs.filter { it.kind == PackKind.OCR_LANGUAGE }) {
            if (store.status(p) != PackStatus.INSTALLED_VERIFIED && store.status(p) != PackStatus.INSTALLED_USER_IMPORT) continue
            for (f in p.files) {
                runCatching { Os.symlink(store.file(p.id, f.name).absolutePath, File(dir, f.name).absolutePath) }
            }
        }
    }

    /** Marks a pack in use so it cannot be deleted while an engine has it open. */
    fun acquire(id: String) = store.acquire(id)
    fun release(id: String) = store.release(id)
}
