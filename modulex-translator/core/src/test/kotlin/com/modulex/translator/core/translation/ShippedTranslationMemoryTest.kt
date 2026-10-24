package com.modulex.translator.core.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The dialect corpora shipped in the APK (assets/tm, built by tools/tm_build.py) obey the corpus rules:
 * schema, >= 5 words, no duplicate or near-duplicate (word-3-gram Jaccard >= 0.8) sources, every listed
 * term really occurs (same matcher as the glossary), manifest hashes and counts match, and no held-out
 * evaluation pair is shipped.
 */
class ShippedTranslationMemoryTest {
    private val dir = File("../app/src/main/assets/tm")
    private val heldoutDir = File("../engines/src/test/resources/tm-heldout")
    private val header = listOf("id", "dialect", "source", "standard", "en", "terms", "domain", "provenance", "license")

    private fun corpora(): Map<String, List<List<String>>> =
        dir.listFiles { f -> f.name.endsWith(".tsv") }.orEmpty().associate { f ->
            val lines = f.readLines().filter { it.isNotEmpty() }
            assertEquals(header, lines.first().split('\t'), "${f.name} header")
            f.name.removeSuffix(".tsv") to lines.drop(1).map { it.split('\t') }
        }

    @Test
    fun manifestMatchesTheFiles() {
        val files = dir.listFiles { f -> f.name.endsWith(".tsv") }.orEmpty()
        if (files.isEmpty()) return // no corpus built yet
        val manifest = Json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonObject["corpora"]!!.jsonObject
        assertEquals(files.map { it.name.removeSuffix(".tsv") }.toSet(), manifest.keys)
        for (f in files) {
            val m = manifest.getValue(f.name.removeSuffix(".tsv")).jsonObject
            val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(m["sha256"]!!.jsonPrimitive.content, sha, "${f.name} sha256")
            assertEquals(m["pairs"]!!.jsonPrimitive.int, f.readLines().count { it.isNotEmpty() } - 1, "${f.name} pairs")
        }
    }

    @Test
    fun everyPairFollowsTheCorpusRules() {
        for ((dialect, rows) in corpora()) {
            val lang = TranslationMemory.baseLang(dialect)
            val seen = HashSet<String>()
            val grams = ArrayList<Set<List<String>>>()
            val index = HashMap<List<String>, MutableList<Int>>()
            for (r in rows) {
                assertEquals(header.size, r.size, "$dialect: column count in ${r.firstOrNull()}")
                val (id, d, source, _, en, terms) = r
                assertEquals(dialect, d, id)
                assertTrue(en.isNotBlank(), "$id has no translation")
                assertTrue(r[7].isNotBlank() && r[8].isNotBlank(), "$id has no provenance/licence")
                val norm = TranslationMemory.normalise(source, lang)
                val words = norm.split(' ').filter { w -> w.any { it.isLetterOrDigit() } }
                assertTrue(words.size >= 5, "$id has ${words.size} words: $source")
                assertTrue(seen.add(norm), "$id duplicates another source after normalisation")
                val g = words.windowed(3).toSet()
                val cand = g.flatMap { index[it].orEmpty() }.toSet()
                for (i in cand) {
                    val j = (g intersect grams[i]).size.toDouble() / (g union grams[i]).size
                    if (j >= 0.8) fail("$id is a near-duplicate (Jaccard %.2f) of another source".format(j))
                }
                g.forEach { index.getOrPut(it) { ArrayList() }.add(grams.size) }
                grams += g
                for (t in terms.split(';').filter { it.isNotEmpty() }) {
                    val hit = Glossary(listOf(Glossary.Entry(lang, "en", t, "x"))).hintsFor(source, lang, "en")
                    assertTrue(hit.isNotEmpty(), "$id lists term «$t» that does not occur in the source")
                }
            }
        }
    }

    @Test
    fun heldOutPairsAreNeverShipped() {
        for ((dialect, rows) in corpora()) {
            val lang = TranslationMemory.baseLang(dialect)
            val f = File(heldoutDir, "$dialect.tsv")
            if (!f.exists()) continue
            val shipped = rows.map { TranslationMemory.normalise(it[2], lang) }.toSet()
            f.readLines().drop(1).filter { it.isNotEmpty() }.forEach { line ->
                val src = line.split('\t')[2]
                assertTrue(TranslationMemory.normalise(src, lang) !in shipped, "held-out pair shipped: $src")
            }
        }
    }

    /** Multi-parallel sources: a held-out item's English must not be shipped in any other dialect either. */
    @Test
    fun heldOutEnglishIsNotShippedInAnyDialect() {
        val shipped = corpora().filterKeys { it.startsWith("ar") }.values.flatten().map { TranslationMemory.normalise(it[4], "en") }.toSet()
        val leaks = heldoutDir.listFiles { f -> f.name.startsWith("ar") && f.name.endsWith(".tsv") }.orEmpty().flatMap { f ->
            f.readLines().drop(1).filter { it.isNotEmpty() }.map { TranslationMemory.normalise(it.split('\t')[4], "en") }.filter { it in shipped }
        }
        assertTrue(leaks.isEmpty(), "${leaks.size} held-out English sentences are shipped in some dialect, e.g. ${leaks.take(3)}")
    }

    private operator fun <T> List<T>.component6(): T = this[5]
}
