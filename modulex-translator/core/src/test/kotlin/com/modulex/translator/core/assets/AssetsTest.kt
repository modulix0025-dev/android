package com.modulex.translator.core.assets

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AssetsTest {
    private lateinit var dir: File
    private lateinit var server: HttpServer
    private val payload = ByteArray(3_000_000) { (it * 31 % 253).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val requests = AtomicInteger()
    /** When > 0, the server closes the connection after this many bytes (simulated network drop). */
    @Volatile private var cutAfter = 0
    @Volatile private var corrupt = false

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("mx-assets").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model.bin") { ex ->
            requests.incrementAndGet()
            val range = ex.requestHeaders.getFirst("Range")
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            val body = payload.copyOfRange(start, payload.size).also { if (corrupt) it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
            ex.responseHeaders.add("Content-Length", body.size.toString())
            ex.sendResponseHeaders(if (range != null) 206 else 200, body.size.toLong())
            try {
                val n = if (cutAfter > 0) minOf(cutAfter, body.size) else body.size
                ex.responseBody.write(body, 0, n)
                if (n < body.size) { ex.close(); return@createContext }
                ex.responseBody.close()
            } catch (_: IOException) {}
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0); dir.deleteRecursively()
    }

    private fun pack(sha256: String? = sha, size: Long? = payload.size.toLong(), url: String? = "https://127.0.0.1:${server.address.port}/model.bin") =
        AssetPack(
            id = "translation-test", kind = PackKind.TRANSLATION_MODEL, title = "Test", version = "1",
            files = listOf(AssetFile("model.bin", url, size, sha256)), upstream = "test", license = "test", attribution = "test",
        )

    /** Test server is plain HTTP; production code refuses http:// URLs. */
    private val source = object : ByteSource {
        val http = HttpByteSource(allowPlainHttpForLoopback = true)
        override fun open(url: String, offset: Long) = http.open(url.replace("https://", "http://"), offset)
    }

    @Test
    fun installsVerifiesAndReportsStatus() = runBlocking {
        val store = AssetStore(dir)
        val p = pack()
        assertEquals(PackStatus.NOT_INSTALLED, store.status(p))
        val progress = ArrayList<InstallProgress>()
        val r = store.install(p, source) { progress += it }
        assertIs<InstallOutcome.Installed>(r)
        assertEquals(PackStatus.INSTALLED_VERIFIED, store.status(p))
        assertTrue(store.verify(p))
        assertTrue(progress.any { it is InstallProgress.Verifying })
        assertEquals(sha, AssetStore.sha256(store.file(p.id, "model.bin")))
    }

    @Test
    fun interruptedDownloadResumesWithRange() = runBlocking {
        val store = AssetStore(dir)
        cutAfter = 1_000_000
        val first = store.install(pack(), source)
        assertIs<InstallOutcome.Failed>(first)
        assertEquals(PackStatus.PARTIAL_DOWNLOAD, store.status(pack()))
        cutAfter = 0
        val second = store.install(pack(), source)
        assertIs<InstallOutcome.Installed>(second)
        assertTrue(store.verify(pack()))
        assertEquals(2, requests.get())
    }

    @Test
    fun corruptedDownloadIsRejectedAndNotMarkedInstalled() = runBlocking {
        val store = AssetStore(dir)
        corrupt = true
        val r = store.install(pack(), source)
        assertIs<InstallOutcome.Failed>(r)
        assertEquals(InstallFailure.HASH_MISMATCH, r.reason)
        assertEquals(PackStatus.NOT_INSTALLED, store.status(pack()))
    }

    @Test
    fun corruptionAfterInstallIsDetected() = runBlocking {
        val store = AssetStore(dir)
        store.install(pack(), source)
        val f = store.file(pack().id, "model.bin")
        f.writeBytes(f.readBytes().also { it[10] = (it[10] + 1).toByte() })
        assertFalse(store.verify(pack()))
        f.writeBytes(f.readBytes().copyOf(100))
        assertEquals(PackStatus.CORRUPT, store.status(pack()))
    }

    @Test
    fun unpinnedPacksAreNeverDownloaded() = runBlocking {
        val r = AssetStore(dir).install(pack(sha256 = null), source)
        assertIs<InstallOutcome.Failed>(r)
        assertEquals(InstallFailure.NOT_PINNED, r.reason)
        assertEquals(0, requests.get())
    }

    @Test
    fun insufficientStorageIsReportedBeforeDownloading() = runBlocking {
        val r = AssetStore(dir, freeBytes = { 10L }).install(pack(), source)
        assertIs<InstallOutcome.Failed>(r)
        assertEquals(InstallFailure.INSUFFICIENT_STORAGE, r.reason)
        assertEquals(0, requests.get())
    }

    @Test
    fun packsInUseCannotBeDeletedOrReplaced() = runBlocking {
        val store = AssetStore(dir)
        store.install(pack(), source)
        store.acquire(pack().id)
        assertFalse(store.delete(pack().id))
        assertIs<InstallOutcome.Failed>(store.install(pack(), source))
        store.release(pack().id)
        assertTrue(store.delete(pack().id))
        assertEquals(PackStatus.NOT_INSTALLED, store.status(pack()))
    }

    @Test
    fun importChecksPinnedHashOrRecordsUserImport() = runBlocking {
        val store = AssetStore(dir)
        val wrong = store.importFiles(pack(), mapOf("model.bin" to ByteArrayInputStream(ByteArray(10))))
        assertIs<InstallOutcome.Failed>(wrong)
        val ok = store.importFiles(pack(), mapOf("model.bin" to ByteArrayInputStream(payload)))
        assertIs<InstallOutcome.Installed>(ok)
        assertEquals(PackStatus.INSTALLED_VERIFIED, store.status(pack()))

        val unpinned = pack(sha256 = null, size = null, url = null).copy(id = "user-model")
        val imp = store.importFiles(unpinned, mapOf("model.bin" to ByteArrayInputStream(payload)))
        assertIs<InstallOutcome.Installed>(imp)
        assertEquals(PackStatus.INSTALLED_USER_IMPORT, store.status(unpinned))
        assertEquals(sha, imp.record.files["model.bin"])
    }

    @Test
    fun productionSourceRefusesPlainHttp() {
        assertFailsWith<IOException> { HttpByteSource().open("http://example.com/x", 0) }
    }

    @Test
    fun manifestValidationRejectsUnsafeEntries() {
        val good = """{"schema":1,"packs":[{"id":"a","kind":"ocr_language","title":"t","version":"1","files":[{"name":"x.traineddata","url":"https://h/x","sizeBytes":1,"sha256":"${"0".repeat(64)}"}],"upstream":"u","license":"l","attribution":"a"}]}"""
        assertEquals(1, AssetManifest.parse(good).packs.size)
        assertFailsWith<IllegalArgumentException> { AssetManifest.parse(good.replace("x.traineddata", "../x")) }
        assertFailsWith<IllegalArgumentException> { AssetManifest.parse(good.replace("https://h/x", "http://h/x")) }
        assertFailsWith<IllegalArgumentException> { AssetManifest.parse(good.replace("0".repeat(64), "abc")) }
        assertFailsWith<IllegalArgumentException> { AssetManifest.parse(good.replace("\"schema\":1", "\"schema\":2")) }
    }

    @Test
    fun ggufHeaderIsParsed() {
        val bytes = syntheticGguf(
            "general.architecture" to "hunyuan-dense",
            "general.name" to "Test Model",
            "hunyuan-dense.context_length" to 8192u,
            "tokenizer.chat_template" to "{{ messages }}",
        )
        val info = GgufInfo.read(ByteArrayInputStream(bytes))
        assertEquals(3, info.version)
        assertEquals("hunyuan-dense", info.architecture)
        assertEquals("Test Model", info.name)
        assertEquals(8192L, info.contextLength())
        assertTrue(info.hasChatTemplate)
        assertFailsWith<IOException> { GgufInfo.read(ByteArrayInputStream("GGML....".toByteArray())) }
    }

    /** Builds a tiny GGUF v3 header with string / uint32 / string-array values. */
    private fun syntheticGguf(vararg kv: Pair<String, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        fun i32(v: Int) = out.write(le(4).putInt(v).array())
        fun i64(v: Long) = out.write(le(8).putLong(v).array())
        fun str(s: String) { val b = s.toByteArray(); i64(b.size.toLong()); out.write(b) }
        i32(0x46554747); i32(3); i64(0); i64(kv.size + 1L)
        for ((k, v) in kv) {
            str(k)
            when (v) {
                is String -> { i32(8); str(v) }
                is UInt -> { i32(4); i32(v.toInt()) }
                else -> error("unsupported")
            }
        }
        str("tokenizer.ggml.tokens"); i32(9); i32(8); i64(3); str("a"); str("b"); str("c")
        return out.toByteArray()
    }
}
