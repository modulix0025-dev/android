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
}

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
     */
    fun listen(whisperLanguage: String, mode: ListenMode, talking: StateFlow<Boolean>? = null, partials: Boolean = true): Flow<VoiceEvent> =
        channelFlow {
            if (!hub.ensureSpeech()) { send(VoiceEvent.Failed("speech_unavailable")); return@channelFlow }
            val segmenter = if (mode == ListenMode.AUTO) UtteranceSegmenter() else UtteranceSegmenter(hangoverMs = Int.MAX_VALUE / 2, startFrames = 1)
            val engineBusy = Mutex()
            var partialJob: Job? = null
            var lastPartialAt = 0L
            val clock = { System.nanoTime() / 1_000_000 }

            suspend fun finalize(u: UtteranceSegmenter.Event.Utterance) {
                partialJob?.cancel()
                hub.cancelSpeech() // stop any partial decode still running natively
                val end = clock()
                engineBusy.withLock {
                    val t0 = clock()
                    val r = runCatching { hub.transcribe(u.samples, whisperLanguage, partial = false) }
                    r.onFailure { send(VoiceEvent.Failed("recognition_failed")) }
                    r.onSuccess { t ->
                        val text = t.text.trim()
                        if (t.probablyNoSpeech || TranscriptMerger.isNonSpeech(text)) send(VoiceEvent.NoSpeech)
                        else send(VoiceEvent.Final(text, t.language, u.durationMs, clock() - t0, end))
                    }
                }
            }

            // Finals are processed off the capture path so no audio is lost while recognising.
            val utterances = Channel<UtteranceSegmenter.Event.Utterance>(capacity = 4)
            launch { for (u in utterances) finalize(u) }
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

            capture.frames().collect { frame ->
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
        }
}
