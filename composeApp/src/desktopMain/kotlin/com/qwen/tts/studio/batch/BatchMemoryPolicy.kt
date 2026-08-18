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

data class BatchAudioBudget(
    val durationTokens: Int,
    val contextTokens: Int,
    val maxAudioTokens: Int
) {
    /** True when this piece can be joined to a short neighbor without using the native floor. */
    val hasNativeHeadroom: Boolean
        get() = contextTokens >= BatchMemoryPolicy.MIN_AUDIO_CONTEXT_TOKENS &&
            durationTokens.toLong() * 10L < BatchMemoryPolicy.NATIVE_AUDIO_TOKEN_FLOOR * 9L

    /** True when the text/context budget leaves too little headroom for safe generation. */
    val requiresRechunk: Boolean
        get() = contextTokens < BatchMemoryPolicy.MIN_AUDIO_CONTEXT_TOKENS ||
            durationTokens.toLong() * 10L >= contextTokens.toLong() * 9L ||
            durationTokens.toLong() * 10L >= BatchMemoryPolicy.AUDIO_REPLAN_REFERENCE_TOKENS * 9L
}

/** Conservative, platform-neutral limits derived from currently observable memory. */
object BatchMemoryPolicy {
    private const val MIN_CHARACTERS = 800
    private const val UNKNOWN_MEMORY_CHARACTERS = 1_600
    private const val MAX_SAFE_CHARACTERS = 5_000
    private const val SAFE_BATCH_CHARACTERS = 4_000
    private const val MAX_AUDIO_TOKENS = 4_096
    private const val CONTEXT_RESERVE_TOKENS = 8
    private const val SAFE_AUDIO_TOKENS = 3_584
    private const val AUDIO_SAMPLES_PER_TOKEN = 1_920
    private const val FLOAT_BYTES = 4L
    private const val PCM_BYTES_PER_SAMPLE = 2L
    private const val GENERATED_BUFFERS_AT_PEAK = 2L
    private const val PERSISTENCE_BUFFERS_AT_PEAK = 2L
    private const val FIXED_PEAK_OVERHEAD = 128L * 1024L * 1024L
    private const val AVAILABLE_MEMORY_FRACTION = 0.60
    const val MIN_AUDIO_CONTEXT_TOKENS = 569
    const val NATIVE_AUDIO_TOKEN_FLOOR = 512L
    /** Conservative target that leaves room below the native 512-token floor. */
    const val AUDIO_REPLAN_REFERENCE_TOKENS = 384L

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
        var high = minOf(MAX_SAFE_CHARACTERS, SAFE_BATCH_CHARACTERS)
        while (low < high) {
            val candidate = (low + high + 1) / 2
            if (estimatedPeakBytes(candidate) <= peakBudget) low = candidate else high = candidate - 1
        }
        return BatchMemoryPlan(
            maxCharacters = low.coerceIn(MIN_CHARACTERS, high),
            observedBudgetBytes = peakBudget
        )
    }

    /**
     * Computes the output-frame budget for the actual voice mode. CustomVoice
     * speakers can be substantially slower than Base/VoiceDesign, so applying
     * the normal 3584-frame ceiling to them falsely treats a valid long result
     * as clipped. The native ceiling remains the hard upper bound.
     */
    fun maxAudioTokens(
        text: String,
        customVoice: Boolean = false,
        textTokenCount: Int? = null,
        instruction: String? = null
    ): Int = audioBudget(text, customVoice, textTokenCount, instruction).maxAudioTokens

    fun audioBudget(
        text: String,
        customVoice: Boolean = false,
        textTokenCount: Int? = null,
        instruction: String? = null
    ): BatchAudioBudget {
        val safeCeiling = if (customVoice) MAX_AUDIO_TOKENS else SAFE_AUDIO_TOKENS
        val durationBudget = ceil(text.length * safeCeiling / SAFE_BATCH_CHARACTERS.toDouble()).toInt()
        // The native talker cache has one shared context for the formatted
        // text prefix and generated audio frames. Passing 4096 output frames
        // does not make 4096 frames available when the text already occupies
        // part of that context; native generation otherwise stops mid-suffix.
        val measuredTextTokens = textTokenCount?.takeIf { it > 0 }
            ?: ceil(text.length * 0.25).toInt() + CONTEXT_RESERVE_TOKENS
        val instructionTokens = instruction?.takeIf { it.isNotBlank() }?.let {
            ceil(it.length * 0.25).toInt() + CONTEXT_RESERVE_TOKENS
        } ?: 0
        val contextBudget = MAX_AUDIO_TOKENS - measuredTextTokens - instructionTokens - CONTEXT_RESERVE_TOKENS
        return BatchAudioBudget(
            durationTokens = durationBudget,
            contextTokens = contextBudget,
            maxAudioTokens = min(durationBudget, contextBudget.coerceAtLeast(NATIVE_AUDIO_TOKEN_FLOOR.toInt()))
                .coerceIn(NATIVE_AUDIO_TOKEN_FLOOR.toInt(), safeCeiling)
        )
    }

    fun maxAudioSamples(
        text: String,
        customVoice: Boolean = false,
        textTokenCount: Int? = null,
        instruction: String? = null
    ): Long = maxAudioTokens(text, customVoice, textTokenCount, instruction).toLong() * AUDIO_SAMPLES_PER_TOKEN

    /**
     * Estimates the peak host-side bytes for one generation while the previous
     * item is being persisted. This intentionally includes both generated
     * FloatArrays, the PCM conversion buffer, and the complete WAV buffer.
     */
    fun estimatedPeakBytes(characterCount: Int): Long {
        require(characterCount >= 0) { "characterCount must not be negative" }
        val audioTokens = ceil(characterCount * SAFE_AUDIO_TOKENS / SAFE_BATCH_CHARACTERS.toDouble())
            .toLong().coerceIn(512L, SAFE_AUDIO_TOKENS.toLong())
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
