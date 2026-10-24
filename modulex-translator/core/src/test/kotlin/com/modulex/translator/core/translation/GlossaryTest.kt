package com.modulex.translator.core.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlossaryTest {
    private val g = Glossary(
        Glossary.parseTsv(
            """
            # comment
            الصدقة الجارية	ongoing charity (sadaqah jariyah)
            صدقة	charity (sadaqah)
            جنيه	Egyptian pound
            العيش	bread
            فول	fava beans
            """.trimIndent(), "ar", "en",
        ) + Glossary.parseTsv("Egyptian pound\tجنيه مصري\npound sterling\tجنيه إسترليني", "en", "ar"),
    )

    private fun ar(text: String) = g.hintsFor(text, "ar", "en").map { it.source }

    @Test
    fun matchesThroughDiacriticsCliticsAndSuffixes() {
        assertEquals(listOf("جنيه"), ar("دفعت خمسين جنيهاً"))
        assertEquals(listOf("جنيه"), ar("الأسعار بالجنيهات"))
        assertEquals(listOf("جنيه"), ar("وجنيه واحد"))
        assertEquals(listOf("العيش"), ar("العيش خلص"))
        assertEquals(listOf("العيش"), ar("هات عيش وانت جاي"))
        assertEquals(listOf("العيش"), ar("بالعيش"))
    }

    @Test
    fun longestTermWinsAndNoOverlap() {
        assertEquals(listOf("الصدقة الجارية"), ar("الصَّدَقَةُ الجارية من أفضل الأعمال"))
        assertEquals(listOf("صدقة", "جنيه"), ar("أعطيته صدقة بعشرة جنيه"))
    }

    @Test
    fun neverMatchesInsideAnotherWord() {
        assertTrue(ar("عيشة هادية").isEmpty(), "عيشة (life) is not عيش (bread)")
        assertTrue(ar("الفولت عالي").isEmpty(), "فولت (volt) is not فول")
        assertTrue(ar("ما فيش حاجة").isEmpty())
    }

    @Test
    fun englishMatchingIsCaseInsensitiveWithPlurals() {
        assertEquals(listOf("Egyptian pound"), g.hintsFor("It costs fifty Egyptian Pounds.", "en", "ar").map { it.source })
        assertTrue(g.hintsFor("Five pounds of flour", "en", "ar").isEmpty(), "weight 'pound' is not a currency entry")
        assertTrue(g.hintsFor("hello", "en", "fr").isEmpty())
    }
}
