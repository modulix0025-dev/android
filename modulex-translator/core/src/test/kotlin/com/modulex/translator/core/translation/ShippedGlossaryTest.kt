package com.modulex.translator.core.translation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The glossaries shipped in the APK parse cleanly and fire on the cases they exist for, and only those. */
class ShippedGlossaryTest {
    private val dir = File("../app/src/main/assets/glossary")
    private val g = Glossary(
        Glossary.parseTsv(File(dir, "ar-en.tsv").readText(), "ar", "en") +
            Glossary.parseTsv(File(dir, "en-ar.tsv").readText(), "en", "ar"),
    )

    private fun hints(text: String, from: String = "ar", to: String = "en") = g.hintsFor(text, from, to).map { it.source to it.target }

    @Test
    fun everyLineIsAnEntry() {
        for (name in listOf("ar-en.tsv", "en-ar.tsv")) {
            val lines = File(dir, name).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            lines.forEach { assertEquals(2, it.split('\t').size, "$name: '$it'") }
            assertEquals(lines.size, lines.map { it.substringBefore('\t') }.toSet().size, "$name has duplicate terms")
        }
        assertTrue(g.size > 100)
    }

    @Test
    fun reportedCases() {
        assertEquals(listOf("الصدقة الجارية" to "ongoing charity"), hints("الصدقة الجارية من أفضل الأعمال"))
        assertEquals(listOf("جنيه" to "pound"), hints("الكيلو بعشرين جنيه"))
        assertEquals(listOf("جنيه مصري" to "Egyptian pound"), hints("سعره مية جنيه مصري"))
        assertEquals(listOf("Egyptian pound" to "جنيه مصري"), hints("It costs 50 Egyptian pounds", "en", "ar"))
    }

    @Test
    fun ambiguousWordsAreNotForced() {
        assertTrue(hints("أعلنوا وقف إطلاق النار").isEmpty(), "وقف = stop, not endowment")
        assertTrue(hints("شاهدنا سمك القرش").isEmpty(), "قرش = shark here")
        assertTrue(hints("العيش المشترك مهم").isEmpty(), "العيش = living/coexistence")
        assertTrue(hints("He lifted five pounds", "en", "ar").isEmpty(), "weight, not money")
    }

    /** The Wiktionary-derived dialect list (tools/tm_build.py) obeys the same rules as the curated one. */
    @Test
    fun generatedDialectListIsUnambiguous() {
        val f = File(dir, "ar-en.wiktionary.tsv")
        if (!f.exists()) return
        val entries = Glossary.parseTsv(f.readText(), "ar", "en")
        val lines = f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        lines.forEach { assertEquals(2, it.split('\t').size, "ar-en.wiktionary.tsv: '$it'") }
        assertEquals(lines.size, entries.size)
        assertEquals(entries.size, entries.map { Glossary.normalise(it.source, "ar") }.toSet().size, "duplicate terms")
        val all = Glossary(Glossary.parseTsv(File(dir, "ar-en.tsv").readText(), "ar", "en") + entries)
        for (word in listOf("وقف", "قرش", "مية", "عيش", "أذان", "آذان", "عين", "بنك")) {
            assertTrue(entries.none { Glossary.normalise(it.source, "ar") == Glossary.normalise(word, "ar") }, "ambiguous «$word» listed")
        }
        assertTrue(all.hintsFor("أعلنوا وقف إطلاق النار", "ar", "en").isEmpty(), "وقف = stop, not endowment")
        assertTrue(all.hintsFor("شاهدنا سمك القرش", "ar", "en").isEmpty(), "قرش = shark here")
        assertTrue(all.hintsFor("العيش المشترك مهم", "ar", "en").isEmpty(), "العيش = living/coexistence")
    }

    /** Islamic expressions into every app language (tools/tm_islamic.py, from Wiktionary). */
    @Test
    fun islamicListsAreWellFormedAndUnambiguous() {
        val files = dir.listFiles { f -> f.name.endsWith(".islamic.tsv") }.orEmpty()
        if (files.isEmpty()) return
        val never = listOf("المغرب", "العشاء", "العصر", "الظهر", "سنة", "أذان", "آذان", "وقف", "عيش").map { Glossary.normalise(it, "ar") }
        for (f in files) {
            val to = f.name.substringBefore(".").substringAfter("-")
            val lines = f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            lines.forEach { assertEquals(2, it.split('\t').size, "${f.name}: '$it'") }
            val entries = Glossary.parseTsv(f.readText(), "ar", to)
            assertTrue(entries.none { Glossary.normalise(it.source, "ar") in never }, "${f.name} lists an ambiguous word")
            // Prayer names only as "صلاة ...": a sentence about Morocco or dinner gets no prayer hint.
            val g = Glossary(entries)
            assertTrue(g.hintsFor("سافرت إلى المغرب الصيف الماضي", "ar", to).isEmpty(), "${f.name}: المغرب is Morocco here")
            assertTrue(g.hintsFor("العشاء جاهز على الطاولة", "ar", to).isEmpty(), "${f.name}: العشاء is dinner here")
        }
        File(dir, "ar-en.islamic.tsv").takeIf { it.exists() }?.let { f ->
            val g = Glossary(Glossary.parseTsv(f.readText(), "ar", "en"))
            assertTrue(g.hintsFor("انشالله نتقابل بكرة", "ar", "en").isNotEmpty(), "colloquial spelling of إن شاء الله")
        }
    }
}
