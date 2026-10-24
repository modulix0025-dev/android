package com.modulex.translator.engines

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepares images for the text recognizer and compensates for a measured Tesseract weakness:
 * page layout analysis can drop whole text lines (observed with Arabic lines of ~45 px glyph
 * height: the line vanished in automatic layout mode but was read perfectly in single-line mode or
 * at half scale). The pipeline therefore produces several candidates and keeps the best:
 *  1. automatic layout at the normalised scale,
 *  2. automatic layout at half scale,
 *  3. projection-based line bands, each recognised in single-line mode at several normalised
 *     glyph heights (horizontal text only).
 * Boxes are mapped back to the caller's coordinates. Invisible bidi marks are removed from text.
 */
class OcrPipeline(
    private val recognizer: LocalTextRecognizer,
    private val maxDimension: Int = 2000,
    private val scales: List<Double> = listOf(1.0, 0.5),
    private val minHeightForExtraScale: Int = 60,
    /** Glyph (ink) heights in pixels tried for each detected line. */
    private val lineTargets: List<Int> = listOf(12, 18, 26),
) {
    data class Result(val ocr: OcrResult, val scaleUsed: Double, val passes: Int, val totalMs: Long, val candidates: List<String> = emptyList())

    suspend fun recognize(pixels: ByteArray, width: Int, height: Int, bytesPerPixel: Int, bytesPerLine: Int = width * bytesPerPixel,
                          layout: PageLayout = PageLayout.AUTO): Result {
        val start = System.nanoTime()
        val gray = toGray(pixels, width, height, bytesPerPixel, bytesPerLine)
        val base = minOf(1.0, maxDimension.toDouble() / max(width, height))
        var best: Pair<OcrResult, Double>? = null
        var bestScore = -1.0
        var passes = 0
        val diag = ArrayList<String>()
        for (s in scales) {
            val scale = base * s
            val w = (width * scale).roundToInt().coerceAtLeast(1)
            val h = (height * scale).roundToInt().coerceAtLeast(1)
            if (s != 1.0 && minOf(w, h) < minHeightForExtraScale) continue
            val img = if (scale == 1.0) gray else resizeGray(gray, width, height, w, h)
            val r = recognizer.recognize(img, w, h, 1, w, layout)
            passes++
            val score = score(r)
            diag += "layout@%.2f score=%.1f: %s".format(scale, score, r.text.replace("\n", " | "))
            if (score > bestScore) { bestScore = score; best = r to scale }
        }
        if (layout == PageLayout.AUTO) {
            val scale = base
            val w = (width * scale).roundToInt().coerceAtLeast(1)
            val h = (height * scale).roundToInt().coerceAtLeast(1)
            val img = if (scale == 1.0) gray else resizeGray(gray, width, height, w, h)
            val bands = lineBandsWithCore(img, w, h)
            if (bands.size in 1..80) {
                val lines = ArrayList<OcrLine>()
                var elapsed = 0L
                for ((i, band) in bands.withIndex()) {
                    val (core, padded) = band
                    val crop = img.copyOfRange(padded.first * w, padded.last.plus(1) * w)
                    val bh = padded.last - padded.first + 1
                    val inkHeight = core.last - core.first + 1
                    // Tesseract's LSTM is sensitive to glyph height (measured: a line unreadable at
                    // 25-45 px glyph height was read perfectly at ~11-16 px). Try several normalised
                    // heights per line and keep the best-scoring reading.
                    var bestLine: List<OcrLine>? = null
                    var bestLineScore = -1.0
                    for (target in lineTargets) {
                        val f = (target.toDouble() / inkHeight).coerceIn(0.1, 1.5)
                        val cw = (w * f).roundToInt().coerceAtLeast(1)
                        val ch = (bh * f).roundToInt().coerceAtLeast(1)
                        val scaled = if (f == 1.0) crop else resizeGray(crop, w, bh, cw, ch)
                        val r = recognizer.recognize(scaled, cw, ch, 1, cw, PageLayout.SINGLE_LINE)
                        elapsed += r.elapsedMs
                        val sc = score(r)
                        if (sc > bestLineScore) {
                            bestLineScore = sc
                            bestLine = r.lines.map { l ->
                                l.copy(x = (l.x / f).roundToInt(), y = (l.y / f).roundToInt() + padded.first,
                                    w = (l.w / f).roundToInt(), h = (l.h / f).roundToInt(), block = 0, para = i)
                            }
                        }
                    }
                    bestLine?.let { lines += it }
                }
                passes++
                val text = lines.joinToString("\n") { it.text }
                val conf = if (lines.isEmpty()) 0f else lines.map { it.conf }.average().toFloat()
                val candidate = OcrResult(text, conf, elapsed, lines)
                val sc = score(candidate)
                diag += "bands(${bands.size})@%.2f score=%.1f: %s".format(scale, sc, text.replace("\n", " | "))
                if (sc > bestScore) { bestScore = sc; best = candidate to scale }
            }
        }
        val (r, scale) = best!!
        val mapped = r.copy(text = stripBidiMarks(r.text), lines = r.lines.map {
            it.copy(text = stripBidiMarks(it.text), x = (it.x / scale).roundToInt(), y = (it.y / scale).roundToInt(), w = (it.w / scale).roundToInt(), h = (it.h / scale).roundToInt())
        })
        return Result(mapped, scale, passes, (System.nanoTime() - start) / 1_000_000, diag)
    }

    companion object {
        private val bidiMarks = Regex("[\u200E\u200F\u061C\u202A-\u202E\u2066-\u2069]")

        fun stripBidiMarks(s: String) = s.replace(bidiMarks, "")

        /**
         * Horizontal text line bands from a row ink profile (Otsu threshold). Returns padded row
         * ranges; empty for images without clear horizontal lines.
         */
        fun lineBands(gray: ByteArray, w: Int, h: Int): List<IntRange> = lineBandsWithCore(gray, w, h).map { it.second }

        /** Line bands as (ink rows, padded rows). */
        fun lineBandsWithCore(gray: ByteArray, w: Int, h: Int): List<Pair<IntRange, IntRange>> {
            val t = otsu(gray)
            val rows = IntArray(h)
            for (y in 0 until h) {
                var c = 0
                val o = y * w
                for (x in 0 until w) if ((gray[o + x].toInt() and 0xff) < t) c++
                rows[y] = c
            }
            val minInk = maxOf(2, w / 500)
            val raw = ArrayList<IntRange>()
            var start = -1
            for (y in 0..h) {
                val ink = y < h && rows[y] >= minInk
                if (ink && start < 0) start = y
                if (!ink && start >= 0) { raw += start until y; start = -1 }
            }
            // Merge bands separated by tiny gaps (dots/diacritics above or below a line).
            val merged = ArrayList<IntRange>()
            for (b in raw) {
                val last = merged.lastOrNull()
                val gap = if (last == null) Int.MAX_VALUE else b.first - last.last
                val ref = maxOf(last?.let { it.last - it.first + 1 } ?: 0, b.last - b.first + 1)
                if (last != null && gap <= maxOf(2, ref / 4)) merged[merged.lastIndex] = last.first..b.last else merged += b
            }
            return merged.filter { it.last - it.first + 1 >= 8 }.map {
                val pad = maxOf(4, (it.last - it.first + 1) / 4)
                it to (maxOf(0, it.first - pad)..minOf(h - 1, it.last + pad))
            }
        }

        private fun otsu(gray: ByteArray): Int {
            val hist = IntArray(256)
            for (b in gray) hist[b.toInt() and 0xff]++
            val total = gray.size.toDouble()
            var sum = 0.0
            for (i in 0 until 256) sum += i * hist[i].toDouble()
            var sumB = 0.0; var wB = 0.0; var best = 0.0; var thr = 128
            for (i in 0 until 256) {
                wB += hist[i]
                if (wB == 0.0) continue
                val wF = total - wB
                if (wF == 0.0) break
                sumB += i * hist[i].toDouble()
                val mB = sumB / wB
                val mF = (sum - sumB) / wF
                val between = wB * wF * (mB - mF) * (mB - mF)
                if (between > best) { best = between; thr = i }
            }
            return thr + 1
        }

        /** Recognised characters weighted by confidence. */
        fun score(r: OcrResult): Double = r.lines.sumOf { l -> l.text.count { !it.isWhitespace() } * (l.conf / 100.0) }

        fun toGray(px: ByteArray, w: Int, h: Int, bpp: Int, bpl: Int): ByteArray {
            if (bpp == 1 && bpl == w) return px
            val out = ByteArray(w * h)
            for (y in 0 until h) {
                var i = y * bpl
                for (x in 0 until w) {
                    out[y * w + x] = if (bpp == 1) px[i] else {
                        val r = px[i].toInt() and 0xff
                        val g = px[i + 1].toInt() and 0xff
                        val b = px[i + 2].toInt() and 0xff
                        ((r * 299 + g * 587 + b * 114) / 1000).toByte()
                    }
                    i += bpp
                }
            }
            return out
        }

        /** Area-averaging resize for downscaling (bilinear-like for upscaling). */
        fun resizeGray(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
            val out = ByteArray(dw * dh)
            val fx = sw.toDouble() / dw
            val fy = sh.toDouble() / dh
            for (y in 0 until dh) {
                val y0 = (y * fy).toInt()
                val y1 = minOf(sh, max(y0 + 1, ((y + 1) * fy).toInt()))
                for (x in 0 until dw) {
                    val x0 = (x * fx).toInt()
                    val x1 = minOf(sw, max(x0 + 1, ((x + 1) * fx).toInt()))
                    var sum = 0
                    for (yy in y0 until y1) for (xx in x0 until x1) sum += src[yy * sw + xx].toInt() and 0xff
                    out[y * dw + x] = (sum / ((y1 - y0) * (x1 - x0))).toByte()
                }
            }
            return out
        }
    }
}
