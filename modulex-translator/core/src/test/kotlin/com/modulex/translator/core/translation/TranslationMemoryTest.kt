package com.modulex.translator.core.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslationMemoryTest {
    private fun e(id: String, dialect: String, source: String, en: String, msa: String = "") =
        TranslationMemory.Entry(id, dialect, source, buildMap { put("en", en); if (msa.isNotEmpty()) put("ar", msa) })

    private val tm = TranslationMemory(listOf(
        e("1", "ar-EG", "أنا عايز أروح البيت دلوقتي", "I want to go home now", "أريد أن أذهب إلى البيت الآن"),
        e("2", "ar-EG", "إزيك يا صاحبي عامل إيه النهارده", "How are you my friend, how are you doing today"),
        e("3", "ar-EG", "الأكل ده حلو قوي يا ماما", "This food is really good, mum"),
        e("4", "ar-LB", "شو بدك تاكل هلق يا حبيبي", "What do you want to eat now, dear"),
        e("5", "ar-LB", "كيفك اليوم إن شاء الله منيح", "How are you today, well I hope"),
        e("6", "ar-MA", "بغيت نمشي للدار دابا", "I want to go home now"),
    ))

    @Test
    fun exactMatchIgnoresDiacriticsSpellingAndPunctuation() {
        assertEquals("1", tm.exactMatch("انا عايز اروح البيت دلوقتى!", "ar", "en")?.id)
        assertEquals("1", tm.exactMatch("أنا عَايِز أروح البيت دلوقتي", "ar", "ar")?.id)
        assertNull(tm.exactMatch("أنا عايز أروح البيت", "ar", "en"), "a shorter sentence is not an exact match")
        assertNull(tm.exactMatch("إزيك يا صاحبي عامل إيه النهارده", "ar", "ar"), "no MSA stored for this pair")
    }

    @Test
    fun retrievalFindsTheClosestPairsAndRespectsTheThreshold() {
        val m = tm.similar("أنا عايز أروح الشغل دلوقتي", "ar", "en")
        assertEquals("1", m.first().entry.id)
        assertTrue(m.all { it.score >= 0.3 })
        assertTrue(tm.similar("السيارة الحمراء في الشارع الكبير", "ar", "en").isEmpty(), "unrelated text gets no references")
        assertTrue(tm.similar("anything", "fr", "en").isEmpty())
    }

    @Test
    fun dialectDetectionNarrowsTheSearchOnlyWhenSure() {
        assertEquals("ar-LB", tm.detectDialect("شو بدك هلق", "ar"))
        assertEquals("ar-EG", tm.detectDialect("عايز أروح دلوقتي", "ar"))
        assertNull(tm.detectDialect("السلام", "ar"))
    }

    @Test
    fun referencesUseTheTargetSideAndAreCapped() {
        val refs = tm.references("الأكل ده حلو قوي", "ar", "en")
        assertEquals("This food is really good, mum", refs.first().target)
        assertTrue(tm.references("الأكل ده حلو قوي", "ar", "en", maxChars = 10).isEmpty())
        assertTrue(tm.references("الأكل ده حلو قوي", "ar", "en", k = 1).size <= 1)
    }

    @Test
    fun normalFormMatchesTheBuildTool() {
        // Same cases as tools/test_tm_build.py
        assertEquals("انا عايز اروح البيت دلوقتي", TranslationMemory.normalise("أنا عَايِز، أروح البيت دلوقتى!!", "ar"))
        assertEquals("cafe naive strasse", TranslationMemory.normalise("Café, NAÏVE  Straße", "en"))
    }

    @Test
    fun parsesTheShippedFormat() {
        val tsv = "id\tdialect\tsource\tstandard\ten\tterms\tdomain\tprovenance\tlicense\n" +
            "x1\tar-EG\tأنا عايز أروح البيت\tأريد الذهاب\tI want to go home\tعايز\tdaily\tTatoeba #1\tCC-BY-2.0-FR\n" +
            "x2\tar-EG\tمفيش مشكلة خالص يا عم\t\tNo problem at all\t\tdaily\tTatoeba #2\tCC-BY-2.0-FR\n"
        val list = TranslationMemory.parseTsv(tsv, "ar")
        assertEquals(2, list.size)
        assertEquals(mapOf("ar" to "أريد الذهاب", "en" to "I want to go home"), list[0].translations)
        assertEquals(mapOf("en" to "No problem at all"), list[1].translations)
    }

    @Test
    fun retrievalOverTenThousandPairsIsFast() {
        val words = listOf("عايز", "البيت", "دلوقتي", "النهارده", "بكرة", "الشغل", "قوي", "خالص", "ليه", "إمتى", "فين", "مين")
        val big = TranslationMemory((0 until 10_000).map { i ->
            val s = (0 until 6).joinToString(" ") { j -> words[(i * 7 + j * 13 + i / (j + 1)) % words.size] } + " $i"
            e("b$i", if (i % 2 == 0) "ar-EG" else "ar-LB", s, "en $i")
        })
        repeat(5) { big.similar("أنا عايز أروح البيت دلوقتي", "ar", "en") } // warm-up
        val t0 = System.nanoTime()
        repeat(20) { big.similar("أنا عايز أروح البيت دلوقتي", "ar", "en") }
        val ms = (System.nanoTime() - t0) / 20 / 1_000_000.0
        println("TranslationMemory.similar over 10k pairs: %.1f ms per query (JVM)".format(ms))
        assertTrue(ms < 30, "took $ms ms")
    }
}
