package com.modulex.translator.core.speech

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Energy-based voice activity detector with an adaptive noise floor. It turns a stream of 16 kHz
 * mono PCM frames into utterances (phrase-level segments) for the speech recognizer.
 *
 * Buffers are bounded: an utterance is force-closed at [maxUtteranceMs] so memory stays fixed.
 */
class UtteranceSegmenter(
    private val sampleRate: Int = 16_000,
    private val frameMs: Int = 30,
    /** Speech must exceed noise floor by this factor. */
    private val speechRatio: Float = 3.0f,
    /** Absolute minimum RMS (normalised -1..1) treated as speech. */
    private val minSpeechRms: Float = 0.01f,
    private val startFrames: Int = 3,
    private val hangoverMs: Int = 700,
    private val minUtteranceMs: Int = 300,
    private val maxUtteranceMs: Int = 25_000,
    private val preRollMs: Int = 300,
) {
    val frameSamples = sampleRate * frameMs / 1000
    private var noiseFloor = 0.005f
    private var speechRun = 0
    private var silenceRun = 0
    private var voicedFrames = 0
    private var inSpeech = false
    private val maxSamples = sampleRate.toLong() * maxUtteranceMs / 1000
    private val current = FloatRingBuilder((maxSamples + sampleRate).toInt())
    private val preRoll = ArrayDeque<FloatArray>()
    private val preRollFrames = max(1, preRollMs / frameMs)

    sealed interface Event {
        data object SpeechStarted : Event
        /** Audio so far of the ongoing utterance, for partial transcription. */
        data class Ongoing(val samples: FloatArray, val durationMs: Long) : Event
        data class Utterance(val samples: FloatArray, val durationMs: Long, val forced: Boolean) : Event
        /** Speech too short to be worth transcribing (clicks, coughs). */
        data object Discarded : Event
    }

    var lastRms: Float = 0f
        private set

    /** Feed exactly one frame of [frameSamples] samples. */
    fun accept(frame: FloatArray): Event? {
        require(frame.size == frameSamples) { "expected $frameSamples samples" }
        val rms = rms(frame)
        lastRms = rms
        val threshold = max(minSpeechRms, noiseFloor * speechRatio)
        val voiced = rms >= threshold
        if (!inSpeech) {
            // Track noise floor only while not speaking.
            noiseFloor = if (rms < noiseFloor) 0.9f * noiseFloor + 0.1f * rms else 0.995f * noiseFloor + 0.005f * rms
            preRoll.addLast(frame)
            if (preRoll.size > preRollFrames) preRoll.removeFirst()
            speechRun = if (voiced) speechRun + 1 else 0
            if (speechRun >= startFrames) {
                inSpeech = true
                silenceRun = 0
                voicedFrames = speechRun
                current.clear()
                for (f in preRoll) current.add(f)
                preRoll.clear()
                return Event.SpeechStarted
            }
            return null
        }
        current.add(frame)
        silenceRun = if (voiced) 0 else silenceRun + 1
        if (voiced) voicedFrames++
        if (silenceRun * frameMs >= hangoverMs) return finish(forced = false)
        if (current.size >= maxSamples) return finish(forced = true)
        return null
    }

    /** Snapshot of the ongoing utterance (null when silent). */
    fun ongoing(): Event.Ongoing? =
        if (inSpeech) Event.Ongoing(current.toArray(), current.size * 1000L / sampleRate) else null

    /** Ends any ongoing utterance (e.g. push-to-talk released). */
    fun flush(): Event? = if (inSpeech) finish(forced = false) else null

    fun reset() {
        inSpeech = false; speechRun = 0; silenceRun = 0; voicedFrames = 0; current.clear(); preRoll.clear()
    }

    private fun finish(forced: Boolean): Event {
        inSpeech = false
        speechRun = 0
        // Trim trailing silence (keep 200 ms).
        val trailing = max(0, silenceRun * frameSamples - sampleRate / 5)
        val samples = current.toArray(current.size - trailing)
        current.clear()
        silenceRun = 0
        val durMs = samples.size * 1000L / sampleRate
        val voicedMs = voicedFrames.toLong() * frameMs
        voicedFrames = 0
        // Judge by voiced time, not by pre-roll/hangover padding.
        return if (voicedMs < minUtteranceMs) Event.Discarded else Event.Utterance(samples, durMs, forced)
    }

    companion object {
        fun rms(f: FloatArray): Float {
            var s = 0.0
            for (x in f) s += x * x
            return sqrt(s / f.size).toFloat()
        }

        /** 16-bit little-endian PCM to normalised floats. */
        fun pcm16ToFloat(src: ShortArray, n: Int = src.size): FloatArray = FloatArray(n) { src[it] / 32768f }
    }
}

/** Growable float buffer with a hard cap. */
private class FloatRingBuilder(private val cap: Int) {
    private var data = FloatArray(16_000)
    var size = 0
        private set

    fun add(f: FloatArray) {
        val n = minOf(f.size, cap - size)
        if (n <= 0) return
        if (size + n > data.size) data = data.copyOf(minOf(cap, max(data.size * 2, size + n)))
        System.arraycopy(f, 0, data, size, n)
        size += n
    }

    fun clear() { size = 0 }
    fun toArray(n: Int = size): FloatArray = data.copyOf(n.coerceIn(0, size))
}

/**
 * Keeps partial transcripts stable: partial results for the same utterance are re-decoded over the
 * whole utterance audio, so they *replace* each other instead of being appended. When a very long
 * utterance has to be split, consecutive transcripts are merged removing the duplicated words at
 * the boundary.
 */
object TranscriptMerger {
    private val ws = Regex("\\s+")

    /** Joins [previous] and [next], dropping the longest suffix of previous repeated at the start of next. */
    fun mergeOverlap(previous: String, next: String, maxOverlapWords: Int = 8): String {
        val a = previous.trim().split(ws).filter { it.isNotEmpty() }
        val b = next.trim().split(ws).filter { it.isNotEmpty() }
        if (a.isEmpty()) return b.joinToString(" ")
        if (b.isEmpty()) return a.joinToString(" ")
        var best = 0
        for (k in minOf(maxOverlapWords, a.size, b.size) downTo 1) {
            var ok = true
            for (i in 0 until k) {
                if (normalise(a[a.size - k + i]) != normalise(b[i])) { ok = false; break }
            }
            if (ok) { best = k; break }
        }
        return (a + b.drop(best)).joinToString(" ")
    }

    private fun normalise(w: String) = w.lowercase().trim { !it.isLetterOrDigit() }

    /** Whisper emits these for silence/noise; they must never be translated or sent. */
    private val nonSpeech = Regex("^\\s*([\\[(（【].*[\\])）】]|\\.+|…)\\s*$")

    fun isNonSpeech(text: String): Boolean = text.isBlank() || nonSpeech.matches(text)
}
