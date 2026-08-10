package com.qwen.tts.studio.batch

import com.qwen.tts.studio.engine.QwenEngine
import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import kotlin.math.ceil
import kotlin.math.min

data class BatchMemorySnapshot(
    val backend: QwenEngine.BackendMemory?,
    val freeRamBytes: Long?,
    val totalRamBytes: Long?
)

data class BatchMemoryPlan(
    val maxCharacters: Int?,
    val observedBudgetBytes: Long?,
    val reason: String? = null
) {
    val hasCapacity: Boolean get() = maxCharacters != null
}

/** Conservative, platform-neutral limits derived from currently observable memory. */
object BatchMemoryPolicy {
    private const val MIN_CHARACTERS = 800
    private const val UNKNOWN_MEMORY_CHARACTERS = 1_600
    private const val MAX_SAFE_CHARACTERS = 5_000
    private const val MAX_AUDIO_TOKENS = 4_096
    private const val AUDIO_SAMPLES_PER_TOKEN = 1_920
    private const val FLOAT_BYTES = 4L
    private const val PCM_BYTES_PER_SAMPLE = 2L
    private const val GENERATED_BUFFERS_AT_PEAK = 2L
    private const val PERSISTENCE_BUFFERS_AT_PEAK = 2L
    private const val FIXED_PEAK_OVERHEAD = 128L * 1024L * 1024L
    private const val AVAILABLE_MEMORY_FRACTION = 0.60

    fun snapshot(backend: QwenEngine.BackendMemory?): BatchMemorySnapshot {
        val os = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
        return BatchMemorySnapshot(backend, os?.freeMemorySize, os?.totalMemorySize)
    }

    fun maxCharacters(memory: BatchMemorySnapshot): Int {
        return plan(memory).maxCharacters ?: 0
    }

    fun plan(memory: BatchMemorySnapshot): BatchMemoryPlan {
        val observations = buildList {
            memory.freeRamBytes?.let { add(saneFreeBytes(it, memory.totalRamBytes)) }
            memory.backend?.let { add(saneFreeBytes(it.freeBytes, it.totalBytes)) }
        }.filter { it > 0L }
        if (observations.isEmpty()) {
            return BatchMemoryPlan(
                maxCharacters = UNKNOWN_MEMORY_CHARACTERS,
                observedBudgetBytes = null,
                reason = "No memory telemetry is available; using the conservative fallback."
            )
        }

        // The native generator and the persistence executor overlap by design:
        // one FloatArray can be waiting for WAV persistence while the next one
        // is being generated. Reserve only a fraction of observed free memory
        // so unrelated desktop activity and allocator fragmentation retain room.
        val peakBudget = (observations.minOrNull()!! * AVAILABLE_MEMORY_FRACTION).toLong()
        val minimumEstimate = estimatedPeakBytes(MIN_CHARACTERS)
        if (peakBudget < minimumEstimate) {
            return BatchMemoryPlan(
                maxCharacters = null,
                observedBudgetBytes = peakBudget,
                reason = "Observed memory budget cannot safely hold the minimum ${MIN_CHARACTERS}-character chunk."
            )
        }
        var low = MIN_CHARACTERS
        var high = MAX_SAFE_CHARACTERS
        while (low < high) {
            val candidate = (low + high + 1) / 2
            if (estimatedPeakBytes(candidate) <= peakBudget) low = candidate else high = candidate - 1
        }
        return BatchMemoryPlan(
            maxCharacters = low.coerceIn(MIN_CHARACTERS, MAX_SAFE_CHARACTERS),
            observedBudgetBytes = peakBudget
        )
    }

    /** Leaves enough headroom for natural speech expansion without reserving 4096 for every chunk. */
    fun maxAudioTokens(text: String): Int =
        ceil(text.length * MAX_AUDIO_TOKENS * 2.25 / 5_000.0)
            .toInt().coerceIn(512, MAX_AUDIO_TOKENS)

    /**
     * Estimates the peak host-side bytes for one generation while the previous
     * item is being persisted. This intentionally includes both generated
     * FloatArrays, the PCM conversion buffer, and the complete WAV buffer.
     */
    fun estimatedPeakBytes(characterCount: Int): Long {
        require(characterCount >= 0) { "characterCount must not be negative" }
        val audioTokens = ceil(characterCount * MAX_AUDIO_TOKENS * 2.25 / 5_000.0)
            .toLong().coerceIn(512L, MAX_AUDIO_TOKENS.toLong())
        val samples = audioTokens * AUDIO_SAMPLES_PER_TOKEN
        val generatedBytes = samples * FLOAT_BYTES * GENERATED_BUFFERS_AT_PEAK
        val persistenceBytes = samples * PCM_BYTES_PER_SAMPLE * PERSISTENCE_BUFFERS_AT_PEAK
        val textBytes = characterCount.toLong() * 4L
        return FIXED_PEAK_OVERHEAD + generatedBytes + persistenceBytes + textBytes
    }

    private fun saneFreeBytes(free: Long, total: Long?): Long {
        if (free <= 0L) return 0L
        return total?.takeIf { it > 0L }?.let { min(free, it) } ?: free
    }
}
