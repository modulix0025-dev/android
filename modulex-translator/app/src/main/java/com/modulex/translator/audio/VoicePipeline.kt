package com.modulex.translator.audio

import com.modulex.translator.core.speech.TranscriptMerger
import com.modulex.translator.core.speech.UtteranceSegmenter
import com.modulex.translator.engine.EngineHub
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface VoiceEvent {
    data class Level(val rms: Float) : VoiceEvent
    data object SpeechStarted : VoiceEvent
    /** Live preview of the utterance in progress (re-decoded, replaces the previous partial). */
    data class Partial(val text: String) : VoiceEvent
    /**
     * A finished phrase. [phraseEndAt] is when the speaker stopped (elapsedRealtime-style clock of
     * the pipeline), so callers can measure end-of-phrase to translation latency.
     */
    data class Final(val text: String, val language: String, val audioMs: Long, val recognitionMs: Long, val phraseEndAt: Long) : VoiceEvent
    data object NoSpeech : VoiceEvent
    data class Failed(val reason: String) : VoiceEvent
    /** The whole session's audio (16 kHz mono PCM16, the last [SESSION_CAP_MS] at most), sent once when a finishable session ends. */
    data class Recording(val pcm: ShortArray) : VoiceEvent
}

/** Longest session audio kept for saving and for the fallback transcription (5 min, about 9.6 MB). */
const val SESSION_CAP_MS = 5 * 60 * 1000

enum class ListenMode { AUTO, PUSH_TO_TALK }

/**
 * Microphone -> utterance segmentation -> speech recognition. Each utterance is transcribed exactly
 * once as a final result; partial previews re-decode the growing utterance and are dropped when the
 * engine is busy, so audio is never queued up or transcribed twice.
 */
class VoicePipeline(private val hub: EngineHub, private val capture: AudioCapture) {

    /**
     * @param whisperLanguage Whisper code or "auto".
     * @param talking Only for push-to-talk: true while the button is held.
     * @param finish When given, setting it to true ends the session *gracefully*: the microphone closes, the
     *   phrase still being spoken is not dropped but transcribed, every queued phrase is finished, and the
     *   session audio is sent as [VoiceEvent.Recording]. If no phrase was detected at all (quiet speaker,
     *   steady background noise), the last 30 s of the session are transcribed so nothing said is lost.
     *   Cancelling the collector instead stops everything at once.
     */
    fun listen(
        whisperLanguage: String, mode: ListenMode, talking: StateFlow<Boolean>? = null, partials: Boolean = true,
        finish: StateFlow<Boolean>? = null,
    ): Flow<VoiceEvent> =
        channelFlow {
            if (!hub.ensureSpeech()) { send(VoiceEvent.Failed("speech_unavailable")); return@channelFlow }
            val segmenter = if (mode == ListenMode.AUTO) UtteranceSegmenter() else UtteranceSegmenter(hangoverMs = Int.MAX_VALUE / 2, startFrames = 1)
            val engineBusy = Mutex()
            var partialJob: Job? = null
            var lastPartialAt = 0L
            val clock = { System.nanoTime() / 1_000_000 }
            val session = if (finish != null) SessionAudio(SAMPLE_RATE.toLong() * SESSION_CAP_MS / 1000) else null
            var phrases = 0

            suspend fun finalize(u: UtteranceSegmenter.Event.Utterance) {
                partialJob?.cancel()
                hub.cancelSpeech() // stop any partial decode still running natively
                val end = clock()
                engineBusy.withLock {
                    val t0 = clock()
                    val r = runCatching { hub.transcribe(u.samples, whisperLanguage, partial = false) }
                    r.onFailure { send(VoiceEvent.Failed("recognition_failed")) }
                    r.onSuccess { t ->
                        phrases++
                        val text = t.text.trim()
                        if (t.probablyNoSpeech || TranscriptMerger.isNonSpeech(text)) send(VoiceEvent.NoSpeech)
                        else send(VoiceEvent.Final(text, t.language, u.durationMs, clock() - t0, end))
                    }
                }
            }

            // Finals are processed off the capture path so no audio is lost while recognising.
            val utterances = Channel<UtteranceSegmenter.Event.Utterance>(capacity = 4)
            val consumer = launch { for (u in utterances) finalize(u) }
            fun submit(u: UtteranceSegmenter.Event.Utterance) {
                if (utterances.trySend(u).isFailure) trySend(VoiceEvent.Failed("busy"))
            }

            if (talking != null) {
                launch {
                    talking.collectLatest { held ->
                        if (!held) (synchronized(segmenter) { segmenter.flush() } as? UtteranceSegmenter.Event.Utterance)?.let { submit(it) }
                    }
                }
            }

            capture.frames().takeWhile { finish?.value != true }.collect { frame ->
                session?.add(frame)
                if (mode == ListenMode.PUSH_TO_TALK && talking?.value != true) {
                    synchronized(segmenter) { segmenter.reset() }
                    return@collect
                }
                // The segmenter is shared with the push-to-talk collector; guard it.
                val (ev, rms) = synchronized(segmenter) { segmenter.accept(frame) to segmenter.lastRms }
                send(VoiceEvent.Level(rms))
                when (ev) {
                    UtteranceSegmenter.Event.SpeechStarted -> send(VoiceEvent.SpeechStarted)
                    is UtteranceSegmenter.Event.Utterance -> submit(ev)
                    UtteranceSegmenter.Event.Discarded -> Unit
                    else -> Unit
                }
                // Partial preview roughly every 1.2 s while speaking, only if the engine is idle.
                val now = clock()
                if (partials && now - lastPartialAt > 1_200 && !engineBusy.isLocked) {
                    val ongoing = synchronized(segmenter) { segmenter.ongoing() }
                    if (ongoing != null && ongoing.durationMs > 800) {
                        lastPartialAt = now
                        partialJob = launch {
                            if (!engineBusy.tryLock()) return@launch
                            try {
                                // Bound partial decoding cost: only the last 10 s.
                                val s = ongoing.samples
                                val tail = if (s.size > SAMPLE_RATE * 10) s.copyOfRange(s.size - SAMPLE_RATE * 10, s.size) else s
                                runCatching { hub.transcribe(tail, whisperLanguage, partial = true) }.getOrNull()?.let { t ->
                                    val text = t.text.trim()
                                    if (text.isNotEmpty() && !TranscriptMerger.isNonSpeech(text)) send(VoiceEvent.Partial(text))
                                }
                            } finally {
                                engineBusy.unlock()
                            }
                        }
                    }
                }
            }
            if (finish == null) return@channelFlow
            // Graceful end: the phrase in progress is transcribed, not dropped.
            partialJob?.cancel()
            (synchronized(segmenter) { segmenter.flush() } as? UtteranceSegmenter.Event.Utterance)?.let { submit(it) }
            utterances.close()
            consumer.join()
            val pcm = session!!.toPcm16()
            if (phrases == 0 && session.voiced) {
                val tail = minOf(pcm.size, SAMPLE_RATE * 30)
                val samples = FloatArray(tail) { pcm[pcm.size - tail + it] / 32768f }
                finalize(UtteranceSegmenter.Event.Utterance(samples, tail * 1000L / SAMPLE_RATE, forced = true))
            }
            send(VoiceEvent.Recording(pcm))
        }
}

/** Bounded session recorder: keeps the most recent [cap] samples as PCM16 and whether anything was voiced. */
private class SessionAudio(private val cap: Long) {
    private val chunks = ArrayDeque<ShortArray>()
    private var size = 0L
    var voiced = false
        private set

    fun add(frame: FloatArray) {
        if (!voiced && UtteranceSegmenter.rms(frame) >= 0.01f) voiced = true
        chunks.addLast(ShortArray(frame.size) { (frame[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() })
        size += frame.size
        while (size > cap) size -= chunks.removeFirst().size
    }

    fun toPcm16(): ShortArray {
        val out = ShortArray(size.toInt())
        var o = 0
        for (c in chunks) { System.arraycopy(c, 0, out, o, c.size); o += c.size }
        return out
    }
}
