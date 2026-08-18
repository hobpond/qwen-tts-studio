package com.qwen.tts.studio.batch

import com.qwen.tts.studio.engine.QwenEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchMemoryPolicyTest {
    @Test
    fun peakEstimateIncludesOverlapAndGrowsWithChunkSize() {
        val short = BatchMemoryPolicy.estimatedPeakBytes(800)
        val long = BatchMemoryPolicy.estimatedPeakBytes(5_000)

        assertTrue(short > 128L * 1024L * 1024L)
        assertTrue(long > short)
        assertTrue(long < 512L * 1024L * 1024L)
    }

    @Test
    fun characterLimitUsesTighterHostOrBackendBudget() {
        val ample = BatchMemorySnapshot(
            backend = QwenEngine.BackendMemory(10L * 1024 * 1024 * 1024, 12L * 1024 * 1024 * 1024),
            freeRamBytes = 16L * 1024 * 1024 * 1024,
            totalRamBytes = 32L * 1024 * 1024 * 1024
        )
        val constrained = BatchMemorySnapshot(
            backend = QwenEngine.BackendMemory(128L * 1024 * 1024, 12L * 1024 * 1024 * 1024),
            freeRamBytes = 128L * 1024 * 1024,
            totalRamBytes = 32L * 1024 * 1024 * 1024
        )

        assertEquals(4_000, BatchMemoryPolicy.maxCharacters(ample))
        assertTrue(BatchMemoryPolicy.maxCharacters(constrained) < 5_000)
        assertEquals(null, BatchMemoryPolicy.plan(constrained).maxCharacters)
        assertTrue(BatchMemoryPolicy.plan(constrained).reason.orEmpty().contains("minimum"))
    }

    @Test
    fun reportedCapacityNeverExceedsObservedBudget() {
        val memory = BatchMemorySnapshot(
            backend = QwenEngine.BackendMemory(2L * 1024 * 1024 * 1024, 12L * 1024 * 1024 * 1024),
            freeRamBytes = 4L * 1024 * 1024 * 1024,
            totalRamBytes = 8L * 1024 * 1024 * 1024
        )
        val plan = BatchMemoryPolicy.plan(memory)

        assertTrue(plan.maxCharacters != null)
        assertTrue(BatchMemoryPolicy.estimatedPeakBytes(plan.maxCharacters!!) <= plan.observedBudgetBytes!!)
    }

    @Test
    fun insufficientObservedCapacityIsExplicit() {
        val plan = BatchMemoryPolicy.plan(
            BatchMemorySnapshot(
                backend = QwenEngine.BackendMemory(1L, 12L * 1024 * 1024 * 1024),
                freeRamBytes = 1L,
                totalRamBytes = 8L * 1024 * 1024 * 1024
            )
        )

        assertEquals(null, plan.maxCharacters)
        assertTrue(plan.observedBudgetBytes!! < BatchMemoryPolicy.estimatedPeakBytes(800))
        assertTrue(plan.reason.orEmpty().isNotBlank())
    }

    @Test
    fun unknownMemoryFallsBackToConservativeLimit() {
        assertEquals(1_600, BatchMemoryPolicy.maxCharacters(BatchMemorySnapshot(null, null, null)))
    }

    @Test
    fun customVoiceUsesNativeCeilingInsteadOfBaseVoiceSafetyCeiling() {
        val text = "x".repeat(2_000)

        assertEquals(1_792, BatchMemoryPolicy.maxAudioTokens(text))
        assertEquals(2_048, BatchMemoryPolicy.maxAudioTokens(text, customVoice = true))
        assertTrue(
            BatchMemoryPolicy.maxAudioSamples(text, customVoice = true) >
                BatchMemoryPolicy.maxAudioSamples(text)
        )
    }

    @Test
    fun audioBudgetSubtractsFormattedTextAndInstructionContext() {
        val budget = BatchMemoryPolicy.maxAudioTokens(
            text = "x".repeat(4_000),
            customVoice = true,
            textTokenCount = 760,
            instruction = "Soothing"
        )

        assertTrue(budget < 3_350)
        assertTrue(budget > 3_300)
    }

    @Test
    fun audioBudgetRequiresMinimumContextHeadroom() {
        val text = "x"

        val tooLittle = BatchMemoryPolicy.audioBudget(
            text = text,
            customVoice = true,
            textTokenCount = 3_520
        )
        val enough = BatchMemoryPolicy.audioBudget(
            text = text,
            customVoice = true,
            textTokenCount = 3_519
        )

        assertEquals(568, tooLittle.contextTokens)
        assertTrue(tooLittle.requiresRechunk)
        assertEquals(569, enough.contextTokens)
        assertTrue(!enough.requiresRechunk)
    }

    @Test
    fun audioBudgetRechunksNearTheReferenceAudioCeiling() {
        val nearCeiling = BatchMemoryPolicy.audioBudget(
            text = "x".repeat(337),
            customVoice = true,
            textTokenCount = 337
        )
        val belowCeiling = BatchMemoryPolicy.audioBudget(
            text = "x".repeat(336),
            customVoice = true,
            textTokenCount = 336
        )

        assertTrue(nearCeiling.durationTokens >= 346)
        assertTrue(nearCeiling.requiresRechunk)
        assertTrue(belowCeiling.durationTokens < 346)
        assertTrue(!belowCeiling.requiresRechunk)
    }
}
