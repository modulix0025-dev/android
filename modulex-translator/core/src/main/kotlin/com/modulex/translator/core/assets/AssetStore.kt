package com.modulex.translator.core.assets

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Record written (atomically) after a pack was fully installed and verified. */
@Serializable
data class InstallRecord(
    val packId: String,
    val version: String,
    val files: Map<String, String>, // name -> sha256
    val sizes: Map<String, Long>,
    val installedAt: Long,
    /** "pinned" when every hash matched the manifest; "user-import" when the user imported unpinned files. */
    val trust: String,
)

enum class PackStatus { NOT_INSTALLED, PARTIAL_DOWNLOAD, INSTALLED_VERIFIED, INSTALLED_USER_IMPORT, CORRUPT, UPDATE_AVAILABLE }

sealed interface InstallProgress {
    data class Checking(val packId: String) : InstallProgress
    data class Downloading(val file: String, val bytes: Long, val total: Long?) : InstallProgress
    data class Verifying(val file: String) : InstallProgress
    data object Committing : InstallProgress
}

sealed interface InstallOutcome {
    data class Installed(val record: InstallRecord) : InstallOutcome
    data class Failed(val reason: InstallFailure, val detail: String) : InstallOutcome
}

enum class InstallFailure { NOT_PINNED, INSUFFICIENT_STORAGE, NETWORK, SIZE_MISMATCH, HASH_MISMATCH, IO, IN_USE, CANCELLED }

/** Abstraction over HTTP so tests and the app share the resumable download logic. */
interface ByteSource {
    /** Opens [url] starting at [offset]. Returns the stream and whether the server honoured the range. */
    fun open(url: String, offset: Long): Opened

    class Opened(val stream: InputStream, val rangeHonoured: Boolean, val contentLength: Long?, val closeable: AutoCloseable)
}

class HttpByteSource(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    /** Tests run a local HTTP server; production only accepts HTTPS. */
    private val allowPlainHttpForLoopback: Boolean = false,
) : ByteSource {
    override fun open(url: String, offset: Long): ByteSource.Opened {
        val u = java.net.URI(url).toURL()
        val loopback = u.host == "127.0.0.1" || u.host == "localhost"
        if (u.protocol != "https" && !(allowPlainHttpForLoopback && loopback)) throw IOException("refusing non-HTTPS url")
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
        val code = c.responseCode
        if (code !in 200..299) { c.disconnect(); throw IOException("HTTP $code") }
        val honoured = offset > 0 && code == 206
        val len = c.getHeaderFieldLong("Content-Length", -1).takeIf { it >= 0 }
        return ByteSource.Opened(c.inputStream, honoured, len) { c.disconnect() }
    }
}

/**
 * On-disk layout (all inside app-private storage):
 *   packs/<id>/<files>         installed, verified files
 *   packs/<id>/install.json    install record (written last, atomically)
 *   staging/<id>/<file>.part   resumable partial downloads
 */
class AssetStore(val root: File, private val freeBytes: (File) -> Long = { it.usableSpace }) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val packsDir = File(root, "packs")
    private val stagingDir = File(root, "staging")
    private val inUse = ConcurrentHashMap<String, AtomicInteger>()

    init {
        packsDir.mkdirs(); stagingDir.mkdirs()
    }

    fun packDir(id: String) = File(packsDir, id)
    fun file(id: String, name: String) = File(packDir(id), name)

    fun record(id: String): InstallRecord? {
        val f = File(packDir(id), RECORD)
        if (!f.isFile) return null
        return runCatching { json.decodeFromString(InstallRecord.serializer(), f.readText()) }.getOrNull()
    }

    /** Cheap status check (sizes only). Use [verify] for a full hash check. */
    fun status(pack: AssetPack): PackStatus {
        val rec = record(pack.id)
        if (rec == null) {
            val staging = File(stagingDir, pack.id)
            return if (staging.isDirectory && (staging.listFiles()?.isNotEmpty() == true)) PackStatus.PARTIAL_DOWNLOAD else PackStatus.NOT_INSTALLED
        }
        for ((name, size) in rec.sizes) {
            if (file(pack.id, name).length() != size) return PackStatus.CORRUPT
        }
        if (rec.version != pack.version) return PackStatus.UPDATE_AVAILABLE
        return if (rec.trust == TRUST_PINNED) PackStatus.INSTALLED_VERIFIED else PackStatus.INSTALLED_USER_IMPORT
    }

    /** Full re-hash of installed files against the install record (and manifest, when pinned). */
    suspend fun verify(pack: AssetPack): Boolean = withContext(Dispatchers.IO) {
        val rec = record(pack.id) ?: return@withContext false
        for (f in pack.files) {
            val expected = f.sha256 ?: rec.files[f.name] ?: return@withContext false
            if (rec.files[f.name] != expected) return@withContext false
            val file = file(pack.id, f.name)
            if (!file.isFile || sha256(file) != expected) return@withContext false
        }
        true
    }

    /** Marks a pack as in use (e.g. a model is loaded) so it cannot be deleted. */
    fun acquire(id: String) { inUse.getOrPut(id) { AtomicInteger() }.incrementAndGet() }
    fun release(id: String) { inUse[id]?.decrementAndGet() }
    fun isInUse(id: String) = (inUse[id]?.get() ?: 0) > 0

    fun delete(id: String): Boolean {
        if (isInUse(id)) return false
        File(packDir(id), RECORD).delete() // record first: a crash mid-delete leaves "not installed"
        packDir(id).deleteRecursively()
        File(stagingDir, id).deleteRecursively()
        return true
    }

    fun requiredFreeBytes(pack: AssetPack): Long? {
        val total = pack.totalBytes ?: return null
        val already = pack.files.sumOf { File(File(stagingDir, pack.id), it.name + ".part").length() }
        return (total - already) + SAFETY_MARGIN
    }

    fun hasRoomFor(pack: AssetPack): Boolean {
        val need = requiredFreeBytes(pack) ?: return false
        return freeBytes(root) >= need
    }

    /**
     * Downloads (resuming partial files), verifies size and SHA-256 of every file, then commits the
     * pack atomically. Nothing is visible as installed until all files verified.
     */
    suspend fun install(pack: AssetPack, source: ByteSource, onProgress: (InstallProgress) -> Unit = {}): InstallOutcome =
        withContext(Dispatchers.IO) {
            onProgress(InstallProgress.Checking(pack.id))
            if (!pack.isDownloadable) return@withContext InstallOutcome.Failed(InstallFailure.NOT_PINNED, "pack has no pinned download")
            if (isInUse(pack.id)) return@withContext InstallOutcome.Failed(InstallFailure.IN_USE, "pack is in use")
            if (!hasRoomFor(pack)) return@withContext InstallOutcome.Failed(InstallFailure.INSUFFICIENT_STORAGE, "need ${requiredFreeBytes(pack)} bytes")
            val stage = File(stagingDir, pack.id).apply { mkdirs() }
            try {
                for (f in pack.files) {
                    val part = File(stage, f.name + ".part")
                    val done = File(stage, f.name)
                    if (done.isFile && done.length() == f.sizeBytes) continue
                    download(f, part, source, onProgress)
                    onProgress(InstallProgress.Verifying(f.name))
                    if (part.length() < f.sizeBytes!!) {
                        // Connection ended early: keep the partial file so the next attempt resumes.
                        return@withContext InstallOutcome.Failed(InstallFailure.NETWORK, "incomplete download of ${f.name}")
                    }
                    if (part.length() != f.sizeBytes) {
                        part.delete()
                        return@withContext InstallOutcome.Failed(InstallFailure.SIZE_MISMATCH, f.name)
                    }
                    val hash = sha256(part)
                    if (hash != f.sha256) {
                        part.delete()
                        return@withContext InstallOutcome.Failed(InstallFailure.HASH_MISMATCH, "${f.name}: got $hash")
                    }
                    if (!part.renameTo(done)) throw IOException("rename failed")
                }
                onProgress(InstallProgress.Committing)
                val record = commit(pack, stage, TRUST_PINNED, pack.files.associate { it.name to it.sha256!! })
                InstallOutcome.Installed(record)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                InstallOutcome.Failed(InstallFailure.NETWORK, e.message ?: "I/O error")
            }
        }

    /**
     * Installs user-provided files (e.g. copied from device storage). If the manifest pins hashes,
     * they must match; otherwise the pack is recorded as a user import and the UI says so.
     */
    suspend fun importFiles(pack: AssetPack, files: Map<String, InputStream>): InstallOutcome = withContext(Dispatchers.IO) {
        if (isInUse(pack.id)) return@withContext InstallOutcome.Failed(InstallFailure.IN_USE, "pack is in use")
        val stage = File(stagingDir, pack.id + ".import").apply { deleteRecursively(); mkdirs() }
        val hashes = HashMap<String, String>()
        for (f in pack.files) {
            val input = files[f.name] ?: return@withContext InstallOutcome.Failed(InstallFailure.IO, "missing ${f.name}")
            val out = File(stage, f.name)
            val md = MessageDigest.getInstance("SHA-256")
            FileOutputStream(out).use { o ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n); o.write(buf, 0, n)
                }
                o.fd.sync()
            }
            val h = hex(md.digest())
            if (f.sha256 != null && h != f.sha256) {
                stage.deleteRecursively()
                return@withContext InstallOutcome.Failed(InstallFailure.HASH_MISMATCH, "${f.name}: got $h")
            }
            if (f.sizeBytes != null && out.length() != f.sizeBytes) {
                stage.deleteRecursively()
                return@withContext InstallOutcome.Failed(InstallFailure.SIZE_MISMATCH, f.name)
            }
            hashes[f.name] = h
        }
        val trust = if (pack.isPinned) TRUST_PINNED else TRUST_USER_IMPORT
        InstallOutcome.Installed(commit(pack, stage, trust, hashes))
    }

    private fun commit(pack: AssetPack, stage: File, trust: String, hashes: Map<String, String>): InstallRecord {
        val target = packDir(pack.id)
        // Remove any previous version first; the record goes first so a crash never leaves a stale "installed" record.
        File(target, RECORD).delete()
        target.deleteRecursively()
        if (!stage.renameTo(target)) throw IOException("could not move pack into place")
        File(target, RECORD + ".tmp").delete()
        val record = InstallRecord(
            pack.id, pack.version, hashes,
            pack.files.associate { it.name to File(target, it.name).length() },
            System.currentTimeMillis(), trust,
        )
        val tmp = File(target, "$RECORD.tmp")
        FileOutputStream(tmp).use { it.write(json.encodeToString(InstallRecord.serializer(), record).toByteArray()); it.fd.sync() }
        if (!tmp.renameTo(File(target, RECORD))) throw IOException("could not write install record")
        return record
    }

    private suspend fun download(f: AssetFile, part: File, source: ByteSource, onProgress: (InstallProgress) -> Unit) {
        val total = f.sizeBytes!!
        var offset = if (part.isFile) part.length() else 0L
        if (offset > total) { part.delete(); offset = 0 }
        if (offset == total) return
        val opened = source.open(f.url!!, offset)
        try {
            val append = offset > 0 && opened.rangeHonoured
            if (!append) offset = 0
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(1 shl 16)
                var written = offset
                var lastReport = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = opened.stream.read(buf)
                    if (n < 0) break
                    written += n
                    if (written > total) throw IOException("server sent more data than expected")
                    out.write(buf, 0, n)
                    if (written - lastReport >= (1 shl 20)) {
                        onProgress(InstallProgress.Downloading(f.name, written, total)); lastReport = written
                    }
                }
                out.fd.sync()
                onProgress(InstallProgress.Downloading(f.name, written, total))
            }
        } finally {
            runCatching { opened.stream.close() }
            runCatching { opened.closeable.close() }
        }
    }

    companion object {
        const val RECORD = "install.json"
        const val TRUST_PINNED = "pinned"
        const val TRUST_USER_IMPORT = "user-import"
        const val SAFETY_MARGIN = 200L * 1024 * 1024

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { s ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return hex(md.digest())
        }

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
