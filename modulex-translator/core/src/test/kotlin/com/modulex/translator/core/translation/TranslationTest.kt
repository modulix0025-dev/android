package com.modulex.translator.core.translation

import com.modulex.translator.core.language.LanguageRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslationTest {
    private val ar = LanguageRegistry.require("ar")
    private val en = LanguageRegistry.require("en")
    private val zh = LanguageRegistry.require("zh")

    @Test
    fun promptUsesDocumentedTemplates() {
        assertEquals(
            "Translate the following text into English. Note that you should only output the translated result without any additional explanation:\n\nمرحبا",
            PromptBuilder.build("مرحبا", ar, en),
        )
        assertEquals("将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\nHello", PromptBuilder.build("Hello", en, zh))
        assertTrue(PromptBuilder.build("你好", zh, ar).startsWith("将以下文本翻译为阿拉伯语"))
    }

    private fun reconstruct(p: List<TextSegmenter.Piece>) = p.joinToString("") { it.text + it.separatorAfter }

    @Test
    fun shortTextIsOnePiece() {
        val p = TextSegmenter.split("Hello world.")
        assertEquals(1, p.size)
        assertEquals("Hello world.", p[0].text)
    }

    @Test
    fun paragraphsAndLayoutArePreserved() {
        val text = "First paragraph.\n\nSecond one\nwith a line break."
        val p = TextSegmenter.split(text, maxChars = 50)
        assertEquals(text, reconstruct(p))
        assertEquals(3, p.size)
    }

    @Test
    fun longParagraphSplitsAtSentenceBoundariesWithinLimit() {
        val s = (1..40).joinToString(" ") { "Sentence number $it is here." }
        val p = TextSegmenter.split(s, maxChars = 200)
        assertTrue(p.size > 1)
        assertTrue(p.all { it.text.length <= 200 })
        assertTrue(p.all { it.text.endsWith(".") }, "cuts happen after sentence punctuation")
        assertEquals(s, reconstruct(p))
    }

    @Test
    fun arabicAndCjkPunctuationAreBoundaries() {
        val arabic = (1..30).joinToString(" ") { "هذه جملة رقم $it؟" }
        val p = TextSegmenter.split(arabic, maxChars = 100)
        assertTrue(p.all { it.text.length <= 100 && it.text.endsWith("؟") })
        assertEquals(arabic, reconstruct(p))
        val cjk = (1..60).joinToString(" ") { "这是第${it}句。" }
        val q = TextSegmenter.split(cjk, maxChars = 60)
        assertTrue(q.all { it.text.length <= 60 })
        assertEquals(cjk, reconstruct(q))
    }

    @Test
    fun unpunctuatedTextFallsBackToWordsWithoutBreakingSurrogates() {
        val words = (1..300).joinToString(" ") { "word$it" }
        val p = TextSegmenter.split(words, maxChars = 100)
        assertTrue(p.all { it.text.length <= 100 })
        assertEquals(words, reconstruct(p))
        val emoji = "😀".repeat(200) // no spaces at all
        val e = TextSegmenter.split(emoji, maxChars = 51)
        assertTrue(e.all { !Character.isLowSurrogate(it.text.first()) })
        assertEquals(emoji, reconstruct(e))
    }

    @Test
    fun joinUsesTranslatedPiecesAndOriginalSeparators() {
        val p = TextSegmenter.split("A.\n\nB.", maxChars = 50)
        assertEquals("X\n\nY", TextSegmenter.join(listOf(" X ", "Y\n"), p))
    }

    @Test
    fun blankInputHasNoPieces() {
        assertEquals(0, TextSegmenter.split("  \n ").size)
    }

    @Test
    fun sanitizerStripsThinkingBlocksOnly() {
        assertEquals("Hello", OutputSanitizer.clean("<think>\nreasoning\n</think>\n Hello \n"))
        assertEquals("a < b", OutputSanitizer.clean("a < b"))
    }
}
