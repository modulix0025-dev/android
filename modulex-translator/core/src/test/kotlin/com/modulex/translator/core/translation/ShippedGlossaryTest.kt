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
}
