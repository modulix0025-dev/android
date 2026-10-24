package com.modulex.translator.engines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OcrPipelineHelpersTest {
    /** White page with dark horizontal bars standing in for text lines. */
    private fun page(w: Int, h: Int, bars: List<IntRange>): ByteArray {
        val px = ByteArray(w * h) { 0xff.toByte() }
        for (b in bars) for (y in b) for (x in 50 until w - 50) if ((x / 7) % 2 == 0) px[y * w + x] = 10
        return px
    }

    @Test
    fun lineBandsFindEachLineWithPadding() {
        val bands = OcrPipeline.lineBands(page(800, 300, listOf(40..70, 120..150, 200..230)), 800, 300)
        assertEquals(3, bands.size)
        assertTrue(bands[0].first < 40 && bands[0].last > 70)
        assertTrue(bands.zipWithNext().all { (a, b) -> a.last < b.first || a.last - b.first < 20 })
    }

    @Test
    fun diacriticGapsAreMergedIntoOneLine() {
        // A thin mark 3 px above the line body (like Arabic dots) belongs to the same line.
        val bands = OcrPipeline.lineBands(page(800, 200, listOf(60..63, 67..100)), 800, 200)
        assertEquals(1, bands.size)
    }

    @Test
    fun blankPageHasNoBands() {
        assertEquals(0, OcrPipeline.lineBands(ByteArray(400 * 100) { 0xff.toByte() }, 400, 100).size)
    }

    @Test
    fun resizeAveragesAndGrayConversionWeightsChannels() {
        val src = byteArrayOf(0, 0, -1, -1, 0, 0, -1, -1) // 4x2: 0,0,255,255 / 0,0,255,255
        val out = OcrPipeline.resizeGray(src, 4, 2, 2, 1)
        assertEquals(listOf(0, 255), out.map { it.toInt() and 0xff })
        val rgba = byteArrayOf(-1, 0, 0, -1) // pure red
        assertEquals(76, OcrPipeline.toGray(rgba, 1, 1, 4, 4)[0].toInt() and 0xff)
    }

    @Test
    fun bidiMarksAreRemoved() {
        assertEquals("Exit مخرج", OcrPipeline.stripBidiMarks("‎Exit‏ ⁧مخرج⁩"))
    }
}

class ChrFTest {
    @Test
    fun identicalIsHundredDisjointIsZero() {
        assertEquals(100.0, chrF("Hello world", "Hello world"), 1e-9)
        assertEquals(0.0, chrF("abc", "xyz"), 1e-9)
        assertTrue(chrF("Hello there", "Hello world") in 20.0..80.0)
    }
}
