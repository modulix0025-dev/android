package com.modulex.translator.engines

import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.TextAttribute
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs real text recognition through the JNI library built for the host, with the same bundled
 * Arabic/English data the app ships. Images are rendered with Java2D (HarfBuzz shaping, so Arabic
 * letters are joined as in real signage).
 */
class OcrEngineTest {
    private val tessdata = System.getProperty("modulex.tessdata.dir")

    @BeforeTest
    fun check() {
        System.setProperty("java.awt.headless", "true")
        org.junit.Assume.assumeTrue("host native library not built", NativeLibraries.tryLoad("mx_ocr").isSuccess)
        assertTrue(File(tessdata, "ara.traineddata").isFile, "bundled tessdata missing: $tessdata")
    }

    private fun render(lines: List<String>, rtl: List<Boolean>, size: Int = 44): Triple<ByteArray, Int, Int> {
        val w = 1400
        val lineH = (size * 1.8).toInt()
        val h = lineH * lines.size + 60
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE; g.fillRect(0, 0, w, h)
        g.color = Color.BLACK
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val font = Font("DejaVu Sans", Font.PLAIN, size).deriveFont(mapOf(TextAttribute.RUN_DIRECTION to TextAttribute.RUN_DIRECTION_LTR))
        lines.forEachIndexed { i, s ->
            val f = if (rtl[i]) font.deriveFont(mapOf(TextAttribute.RUN_DIRECTION to TextAttribute.RUN_DIRECTION_RTL)) else font
            g.font = f
            val width = g.fontMetrics.stringWidth(s)
            val x = if (rtl[i]) w - 40 - width else 40
            g.drawString(s, x, 40 + lineH * i + size)
        }
        g.dispose()
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val p = img.getRGB(x, y)
            val o = (y * w + x) * 4
            rgba[o] = (p shr 16).toByte(); rgba[o + 1] = (p shr 8).toByte(); rgba[o + 2] = p.toByte(); rgba[o + 3] = 0xff.toByte()
        }
        return Triple(rgba, w, h)
    }

    /** Character error rate via Levenshtein distance, ignoring whitespace differences. */
    private fun cer(expected: String, actual: String): Double {
        val a = expected.filterNot { it.isWhitespace() }
        val b = actual.filterNot { it.isWhitespace() }
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length].toDouble() / a.length
    }

    @Test
    fun recognisesEnglishText() = runBlocking<Unit> {
        val ocr = LocalTextRecognizer()
        ocr.init(tessdata, "eng").getOrThrow()
        val expected = listOf("The quick brown fox jumps over the lazy dog.", "Platform 4 departs at 10:35 for Cairo.")
        val (px, w, h) = render(expected, listOf(false, false))
        val r = ocr.recognize(px, w, h, 4)
        val c = cer(expected.joinToString("\n"), r.text)
        println("OCR eng: cer=%.3f conf=%.1f time=%dms text=%s".format(c, r.meanConfidence, r.elapsedMs, r.text.replace("\n", " | ")))
        assertTrue(c < 0.05, "English CER too high: $c")
        assertTrue(r.lines.size == 2 && r.lines.all { it.w > 0 && it.h > 0 })
        ocr.close()
    }

    @Test
    fun recognisesArabicText() = runBlocking<Unit> {
        val ocr = LocalTextRecognizer()
        ocr.init(tessdata, "ara").getOrThrow()
        val expected = listOf("مرحبا بكم في المطعم", "الرجاء الانتظار حتى يتم تقديم الطعام")
        val (px, w, h) = render(expected, listOf(true, true))
        val raw = ocr.recognize(px, w, h, 4)
        val p = OcrPipeline(ocr).recognize(px, w, h, 4)
        val r = p.ocr
        val c = cer(expected.joinToString("\n"), r.text)
        println("OCR ara: raw-cer=%.3f pipeline-cer=%.3f scale=%.2f passes=%d conf=%.1f time=%dms text=%s".format(
            cer(expected.joinToString("\n"), raw.text), c, p.scaleUsed, p.passes, r.meanConfidence, p.totalMs, r.text.replace("\n", " | ")))
        // Logical (reading) order must be preserved: the first words must come first.
        assertTrue(r.text.trimStart().startsWith("مرحبا"), "Arabic reading order broken: ${r.text}")
        assertTrue(c < 0.05, "Arabic CER too high: $c")
        // Word boundaries matter for translation: the words must come out separated.
        assertEquals(expected.joinToString(" ").split(" ").filter { it.isNotBlank() },
            r.text.split(Regex("\\s+")).filter { it.isNotBlank() })
        ocr.close()
    }

    @Test
    fun recognisesMixedArabicEnglishPage() = runBlocking<Unit> {
        val ocr = LocalTextRecognizer()
        ocr.init(tessdata, "ara+eng").getOrThrow()
        val expected = listOf("Exit", "مخرج الطوارئ", "Pharmacy open 24 hours", "صيدلية مفتوحة")
        val (px, w, h) = render(expected, listOf(false, true, false, true))
        val p = OcrPipeline(ocr).recognize(px, w, h, 4)
        val r = p.ocr
        p.candidates.forEach { println("  candidate $it") }
        println("OCR mixed: cer=%.3f scale=%.2f conf=%.1f time=%dms text=%s".format(
            cer(expected.joinToString("\n"), r.text), p.scaleUsed, r.meanConfidence, p.totalMs, r.text.replace("\n", " | ")))
        val idxExit = r.text.indexOf("Exit")
        val idxPharmacy = r.text.indexOf("Pharmacy")
        assertTrue(idxExit >= 0 && idxPharmacy > idxExit, "top-to-bottom order lost")
        // Both Arabic lines, word-separated. "صيدلية مفتوحة" was unreadable before per-line height
        // normalisation (docs/BENCHMARKS.md).
        assertTrue(r.text.contains("مخرج الطوارئ") && r.text.contains("صيدلية مفتوحة"), "Arabic line missing: ${r.text}")
        assertEquals(4, r.lines.size)
        assertTrue(r.lines.all { it.x >= 0 && it.x + it.w <= w + 2 }, "boxes must be in original coordinates")
        assertTrue(cer(expected.joinToString("\n"), r.text) < 0.1)
        ocr.close()
    }

    @Test
    fun blankImageYieldsNoText() = runBlocking<Unit> {
        val ocr = LocalTextRecognizer()
        ocr.init(tessdata, "eng").getOrThrow()
        val r = ocr.recognize(ByteArray(400 * 300) { 0xff.toByte() }, 400, 300, 1)
        assertTrue(r.text.isBlank())
        ocr.close()
    }

    @Test
    fun invalidInputsAreRejectedWithoutCrashing() = runBlocking<Unit> {
        val ocr = LocalTextRecognizer()
        assertTrue(ocr.init(tessdata, "xyz").isFailure, "missing language pack must fail cleanly")
        assertTrue(ocr.init(tessdata, "../etc").isFailure)
        ocr.init(tessdata, "eng").getOrThrow()
        assertFailsWith<NativeEngineException> { ocr.recognize(ByteArray(10), 100, 100, 4) }
        assertFailsWith<NativeEngineException> { ocr.recognize(ByteArray(100 * 100 * 2), 100, 100, 2) }
        ocr.close()
        assertFailsWith<NativeEngineException> { ocr.recognize(ByteArray(4), 1, 1, 4) }
    }

    /** Photo-like lighting: a dark shadow across one side and a gradient, as from a phone held over a page. */
    private fun shade(rgba: ByteArray, w: Int, h: Int, invert: Boolean = false): ByteArray {
        val out = rgba.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val light = (0.3 + 0.7 * x / w) * (if (y > h * 0.6) 0.6 else 1.0) // left shadow, darker bottom band
            val o = (y * w + x) * 4
            for (k in 0..2) {
                var v = rgba[o + k].toInt() and 0xff
                if (invert) v = 255 - v
                out[o + k] = (v * light).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    @Test
    fun readsUnevenlyLitAndInvertedPhotos() = runBlocking<Unit> {
        val ocr = LocalTextRecognizer()
        ocr.init(tessdata, "ara+eng").getOrThrow()
        val expected = listOf("The quick brown fox jumps over the lazy dog.", "Platform 4 departs at 10:35 for Cairo.", "Pharmacy open until midnight")
        val (px, w, h) = render(expected, listOf(false, false, false))
        for (inverted in listOf(false, true)) {
            val photo = shade(px, w, h, inverted)
            val plain = ocr.recognize(photo, w, h, 4)
            val p = OcrPipeline(ocr).recognize(photo, w, h, 4)
            val before = cer(expected.joinToString("\n"), plain.text)
            val after = cer(expected.joinToString("\n"), p.ocr.text)
            println("OCR photo inverted=$inverted: cer plain=%.3f pipeline=%.3f text=%s".format(before, after, p.ocr.text.replace("\n", " | ")))
            p.candidates.forEach { println("   $it") }
            assertTrue(after < 0.1, "uneven/inverted photo CER too high: $after")
            assertTrue(after <= before + 1e-9, "pipeline must not be worse than plain recognition")
        }
        ocr.close()
    }

    @Test
    fun readsArabicInShadow() = runBlocking<Unit> {
        val ocr = LocalTextRecognizer()
        ocr.init(tessdata, "ara+eng").getOrThrow()
        val expected = listOf("مخرج الطوارئ في نهاية الممر", "صيدلية مفتوحة طوال الليل")
        val (px, w, h) = render(expected, listOf(true, true))
        val photo = shade(px, w, h)
        val before = cer(expected.joinToString("\n"), ocr.recognize(photo, w, h, 4).text)
        val p = OcrPipeline(ocr).recognize(photo, w, h, 4)
        val after = cer(expected.joinToString("\n"), p.ocr.text)
        println("OCR arabic photo: cer plain=%.3f pipeline=%.3f".format(before, after))
        p.candidates.forEach { println("   $it") }
        // Measured 2026-10-24: plain 0.477 -> pipeline 0.273. The remaining errors come from the hard shadow
        // edge crossing the second line, the worst case of this synthetic photo.
        assertTrue(after < 0.3, "Arabic photo CER too high: $after")
        assertTrue(after < before, "pipeline must improve the shaded Arabic photo")
        ocr.close()
    }

    @Test
    fun flatteningLeavesEvenPagesAlone() {
        val even = ByteArray(300 * 200) { if (it % 300 in 100..140) 0 else 0xf0.toByte() }
        assertTrue(OcrPipeline.prepare(even, 300, 200) == null, "an evenly lit page needs no extra pass")
        val dark = ByteArray(300 * 200) { if (it % 300 in 100..140) 0xf0.toByte() else 20 }
        assertTrue(OcrPipeline.prepare(dark, 300, 200) != null, "light text on a dark page is inverted")
    }
}
