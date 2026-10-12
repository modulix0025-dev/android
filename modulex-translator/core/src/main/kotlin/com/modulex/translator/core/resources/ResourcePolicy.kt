package com.modulex.translator.core.resources

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Mirrors android.os.PowerManager thermal status constants (0..6). */
enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN;
    companion object { fun fromAndroid(v: Int) = entries.getOrElse(v) { NONE } }
}

enum class HeavyEngine(val estimatedBytes: Long) {
    // Estimates for planning only; replaced at runtime by measured file sizes + overhead.
    TRANSLATION(1_400L shl 20),
    SPEECH(400L shl 20),
    OCR(150L shl 20),
    TTS(150L shl 20),
}

data class MemorySnapshot(val availBytes: Long, val totalBytes: Long, val lowMemory: Boolean)

/** What work is allowed under the current thermal/memory conditions. */
data class WorkBudget(
    val inferenceThreads: Int,
    val allowContinuousCamera: Boolean,
    val allowAutoListening: Boolean,
    val userMessage: String?,
)

object ResourcePolicy {

    /** Threads for llama/whisper: big cores only; reduced when hot. */
    fun budget(thermal: ThermalLevel, cpuCores: Int, batterySaver: Boolean): WorkBudget {
        val base = when {
            cpuCores >= 8 -> 4
            cpuCores >= 6 -> 3
            else -> maxOf(1, cpuCores / 2)
        }
        return when (thermal) {
            ThermalLevel.NONE, ThermalLevel.LIGHT ->
                WorkBudget(if (batterySaver) maxOf(1, base - 1) else base, !batterySaver, true, null)
            ThermalLevel.MODERATE -> WorkBudget(maxOf(1, base - 1), false, true, null)
            ThermalLevel.SEVERE -> WorkBudget(maxOf(1, base / 2), false, false, "device_hot")
            else -> WorkBudget(1, false, false, "device_too_hot")
        }
    }

    /**
     * Which engines must be released before loading [wanted], given what is loaded now.
     * Keeps translation resident when possible because every feature uses it.
     */
    fun evictionsFor(wanted: HeavyEngine, loaded: Map<HeavyEngine, Long>, mem: MemorySnapshot, wantedBytes: Long = wanted.estimatedBytes): List<HeavyEngine> {
        val reserve = maxOf(mem.totalBytes / 10, 512L shl 20)
        var free = mem.availBytes - reserve
        if (free >= wantedBytes && !mem.lowMemory) return emptyList()
        val order = loaded.keys.filter { it != wanted }.sortedBy { if (it == HeavyEngine.TRANSLATION) 1 else 0 }
        val out = ArrayList<HeavyEngine>()
        for (e in order) {
            if (free >= wantedBytes && !mem.lowMemory) break
            out += e
            free += loaded[e] ?: e.estimatedBytes
        }
        return out
    }
}

/**
 * Serialises memory-heavy native work. One permit per engine keeps each native context
 * single-threaded; [global] bounds how many heavy jobs run concurrently across engines.
 */
class InferenceGate(maxConcurrent: Int = 2) {
    private val global = Semaphore(maxConcurrent)
    private val perEngine = HeavyEngine.entries.associateWith { Semaphore(1) }

    suspend fun <T> run(engine: HeavyEngine, block: suspend () -> T): T =
        perEngine.getValue(engine).withPermit { global.withPermit { block() } }
}
