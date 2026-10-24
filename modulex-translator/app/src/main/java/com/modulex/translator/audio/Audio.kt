package com.modulex.translator.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import com.modulex.translator.core.speech.UtteranceSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

const val SAMPLE_RATE = 16_000
const val FRAME_SAMPLES = SAMPLE_RATE * 30 / 1000

/**
 * 16 kHz mono microphone capture in 30 ms frames (normalised floats), as a cold Flow: the
 * microphone is open exactly while the flow is collected and is always released afterwards.
 * The buffer between capture and consumers is bounded; when processing falls behind, the oldest
 * frames are dropped rather than growing memory.
 */
class AudioCapture(private val context: Context) {

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by hasPermission() before collecting
    fun frames(): Flow<FloatArray> = callbackFlow {
        check(hasPermission()) { "microphone permission missing" }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME_SAMPLES * 2 * 8),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null
        record.startRecording()
        val reader = Thread({
            val buf = ShortArray(FRAME_SAMPLES)
            while (!Thread.currentThread().isInterrupted) {
                var off = 0
                while (off < FRAME_SAMPLES) {
                    val n = record.read(buf, off, FRAME_SAMPLES - off)
                    if (n <= 0) return@Thread
                    off += n
                }
                if (trySendBlocking(UtteranceSegmenter.pcm16ToFloat(buf)).isFailure) return@Thread
            }
        }, "mx-audio").apply { priority = Thread.MAX_PRIORITY; start() }
        awaitClose {
            reader.interrupt()
            runCatching { record.stop() }
            ns?.release(); aec?.release()
            record.release()
        }
    }.buffer(capacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST).flowOn(Dispatchers.IO)
}

/** Streams float PCM to the speaker. One stream at a time; [stop] interrupts playback. */
class PcmPlayer {
    private val lock = Mutex()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopped = false
    private var framesWritten = 0L

    suspend fun play(chunks: suspend ((FloatArray, Int) -> Boolean) -> Unit) = lock.withLock {
        stopped = false
        try {
            chunks { samples, sampleRate ->
                if (stopped) return@chunks false
                val t = track?.takeIf { it.sampleRate == sampleRate } ?: newTrack(sampleRate)
                val n = t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                if (n > 0) framesWritten += n
                !stopped
            }
            // Let queued audio finish (bounded wait).
            track?.let { t ->
                withContext(Dispatchers.IO) {
                    var waited = 0
                    while (!stopped && isActive && (t.playbackHeadPosition.toLong() and 0xffffffffL) < framesWritten && waited < 30_000) {
                        delay(20); waited += 20
                    }
                }
            }
        } finally {
            releaseTrack()
        }
    }

    private fun newTrack(sampleRate: Int): AudioTrack {
        releaseTrack()
        framesWritten = 0
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), sampleRate))
            .build()
        t.play()
        track = t
        return t
    }

    fun stop() {
        stopped = true
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    private fun releaseTrack() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}

/**
 * Session recordings of the voice screen: 16 kHz mono PCM16 WAV files in app-private storage
 * (`files/recordings`), never uploaded. Only the newest [KEEP] are kept.
 */
object Recordings {
    private const val KEEP = 20

    fun save(filesDir: java.io.File, pcm: ShortArray): java.io.File {
        val dir = java.io.File(filesDir, "recordings").apply { mkdirs() }
        val f = java.io.File(dir, "voice-${System.currentTimeMillis()}.wav")
        val data = java.nio.ByteBuffer.allocate(44 + pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2)
            .putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(pcm.size * 2)
        pcm.forEach { data.putShort(it) }
        f.writeBytes(data.array())
        dir.listFiles { x -> x.name.endsWith(".wav") }.orEmpty().sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
        return f
    }

    /** PCM16 samples of a file written by [save]. */
    fun read(f: java.io.File): ShortArray {
        val b = java.nio.ByteBuffer.wrap(f.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.position(44)
        return ShortArray(b.remaining() / 2) { b.short }
    }
}
