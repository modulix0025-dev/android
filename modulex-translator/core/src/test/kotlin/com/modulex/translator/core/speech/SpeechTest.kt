package com.modulex.translator.core.speech

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpeechTest {
    private val sr = 16_000
    private val rnd = Random(7)

    private fun noise(ms: Int, amp: Float = 0.003f) = FloatArray(sr * ms / 1000) { (rnd.nextFloat() * 2 - 1) * amp }
    private fun voice(ms: Int, amp: Float = 0.2f) = FloatArray(sr * ms / 1000) { i ->
        // A crude voiced signal: harmonics of 140 Hz with amplitude modulation.
        val t = i.toFloat() / sr
        (amp * (sin(2 * PI * 140 * t) + 0.5 * sin(2 * PI * 280 * t)) * (0.6 + 0.4 * sin(2 * PI * 4 * t))).toFloat()
    }

    private fun feed(seg: UtteranceSegmenter, audio: FloatArray): List<UtteranceSegmenter.Event> {
        val out = ArrayList<UtteranceSegmenter.Event>()
        var i = 0
        while (i + seg.frameSamples <= audio.size) {
            seg.accept(audio.copyOfRange(i, i + seg.frameSamples))?.let { out += it }
            i += seg.frameSamples
        }
        return out
    }

    @Test
    fun detectsOneUtteranceSurroundedBySilence() {
        val seg = UtteranceSegmenter()
        val events = feed(seg, noise(1000) + voice(1500) + noise(1200))
        assertIs<UtteranceSegmenter.Event.SpeechStarted>(events.first())
        val u = events.filterIsInstance<UtteranceSegmenter.Event.Utterance>().single()
        assertTrue(u.durationMs in 1400..2400, "duration ${u.durationMs}")
        assertTrue(!u.forced)
    }

    @Test
    fun ignoresBackgroundNoiseAndShortClicks() {
        val seg = UtteranceSegmenter()
        val events = feed(seg, noise(2000, 0.008f) + voice(120) + noise(1500, 0.008f))
        assertTrue(events.none { it is UtteranceSegmenter.Event.Utterance })
    }

    @Test
    fun twoPhrasesBecomeTwoUtterances() {
        val seg = UtteranceSegmenter()
        val events = feed(seg, noise(500) + voice(800) + noise(1000) + voice(900) + noise(1000))
        assertEquals(2, events.filterIsInstance<UtteranceSegmenter.Event.Utterance>().size)
    }

    @Test
    fun longSpeechIsForceSplitAndBounded() {
        val seg = UtteranceSegmenter(maxUtteranceMs = 3_000)
        val us = feed(seg, noise(300) + voice(7_000) + noise(1000)).filterIsInstance<UtteranceSegmenter.Event.Utterance>()
        assertTrue(us.size >= 2)
        assertTrue(us.first().forced)
        assertTrue(us.all { it.samples.size <= sr * 3.5 })
    }

    @Test
    fun pushToTalkFlushEndsUtterance() {
        val seg = UtteranceSegmenter()
        feed(seg, noise(300) + voice(600))
        assertTrue(seg.ongoing() != null)
        assertIs<UtteranceSegmenter.Event.Utterance>(seg.flush())
    }

    @Test
    fun overlapMergeRemovesDuplicatedBoundaryWords() {
        assertEquals("I would like to book a table for two", TranscriptMerger.mergeOverlap("I would like to book a", "book a table for two"))
        assertEquals("hello world again", TranscriptMerger.mergeOverlap("hello world", "World, again".replace(",", "")))
        assertEquals("a b c d", TranscriptMerger.mergeOverlap("a b", "c d"))
        assertEquals("مرحبا بك في المطعم", TranscriptMerger.mergeOverlap("مرحبا بك", "بك في المطعم"))
    }

    @Test
    fun nonSpeechMarkersAreDetected() {
        assertTrue(TranscriptMerger.isNonSpeech("[BLANK_AUDIO]"))
        assertTrue(TranscriptMerger.isNonSpeech(" (music) "))
        assertTrue(TranscriptMerger.isNonSpeech("..."))
        assertTrue(!TranscriptMerger.isNonSpeech("Hello (there)"))
    }
}
