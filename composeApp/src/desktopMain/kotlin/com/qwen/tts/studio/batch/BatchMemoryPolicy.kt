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

/** Conservative, platform-neutral limits derived from currently observable memory. */
object BatchMemoryPolicy {
    private const val GIB = 1024L * 1024L * 1024L
    private const val MIN_CHARACTERS = 800
    private const val UNKNOWN_MEMORY_CHARACTERS = 1_600
    private const val MAX_SAFE_CHARACTERS = 2_000
    private const val MAX_AUDIO_TOKENS = 4_096

    fun snapshot(backend: QwenEngine.BackendMemory?): BatchMemorySnapshot {
        val os = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
        return BatchMemorySnapshot(backend, os?.freeMemorySize, os?.totalMemorySize)
    }

    fun maxCharacters(memory: BatchMemorySnapshot): Int {
        val ramLimit = memory.freeRamBytes?.let { free ->
            when {
                free < 4 * GIB -> 800
                free < 8 * GIB -> 1_200
                free < 16 * GIB -> 1_600
                else -> MAX_SAFE_CHARACTERS
            }
        } ?: UNKNOWN_MEMORY_CHARACTERS
        val backendLimit = memory.backend?.let { backend ->
            when {
                backend.freeBytes < 2 * GIB -> 800
                backend.freeBytes < 4 * GIB -> 1_200
                backend.freeBytes < 6 * GIB -> 1_600
                else -> MAX_SAFE_CHARACTERS
            }
        } ?: UNKNOWN_MEMORY_CHARACTERS
        return min(ramLimit, backendLimit).coerceIn(MIN_CHARACTERS, MAX_SAFE_CHARACTERS)
    }

    /** Leaves enough headroom for natural speech expansion without reserving 4096 for every chunk. */
    fun maxAudioTokens(text: String): Int =
        ceil(text.length * MAX_AUDIO_TOKENS * 2.25 / 5_000.0)
            .toInt().coerceIn(512, MAX_AUDIO_TOKENS)
}
