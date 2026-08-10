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
            backend = QwenEngine.BackendMemory(256L * 1024 * 1024, 12L * 1024 * 1024 * 1024),
            freeRamBytes = 256L * 1024 * 1024,
            totalRamBytes = 32L * 1024 * 1024 * 1024
        )

        assertEquals(5_000, BatchMemoryPolicy.maxCharacters(ample))
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
}
