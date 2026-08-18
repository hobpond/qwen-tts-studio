package com.qwen.tts.studio.agent

import com.qwen.tts.studio.engine.QwenEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentTelemetryTest {
    @Test
    fun samplesAndMemoryHighWaterRemainBoundedAndKeepDomainsSeparate() {
        val clock = ManualClock()
        val telemetry = AgentTelemetry(
            nanoTime = clock::now,
            processMemory = {
                AgentProcessMemorySnapshot(
                    rssBytes = 100L,
                    committedVirtualBytes = 200L,
                    hostPhysicalFreeBytes = 600L,
                    hostPhysicalTotalBytes = 1_000L
                )
            },
            maxSamples = 3
        )

        telemetry.sample("first", QwenEngine.BackendMemory(800L, 1_000L))
        telemetry.sample("second", QwenEngine.BackendMemory(500L, 1_000L))
        telemetry.sample("third", QwenEngine.BackendMemory(200L, 1_000L))
        telemetry.sample("fourth", QwenEngine.BackendMemory(900L, 1_000L))
        telemetry.sample("latest", QwenEngine.BackendMemory(100L, 1_000L))

        val snapshot = telemetry.snapshot()
        assertEquals(3, snapshot.sampleCount)
        assertEquals(2, snapshot.droppedSampleCount)
        assertEquals(listOf("first", "second", "latest"), snapshot.samples.map { it.phase })

        val backend = snapshot.memory.deviceBackend
        assertEquals("device_backend_or_native_allocator", backend.memoryDomain)
        assertEquals(5, backend.sampleCount)
        assertEquals(100L, backend.freeBytesMin)
        assertEquals(900L, backend.usedBytesHighWater)
        assertEquals(0.9, backend.usedHighWaterRatio!!, 0.000001)

        val hostPhysical = snapshot.memory.hostPhysical
        assertEquals("host_physical_system_memory", hostPhysical.memoryDomain)
        assertEquals(5, hostPhysical.sampleCount)
        assertEquals(400L, hostPhysical.usedBytesHighWater)
        assertEquals(100L, snapshot.memory.hostProcess.rssBytesHighWater)

        val json = snapshot.toJson()
        assertTrue(json.contains("deviceBackend"))
        assertTrue(json.contains("hostPhysical"))
        assertTrue(json.contains("never host physical memory"))
    }

    @Test
    fun generationAndValidationAggregationUsesMonotonicDurationsAndAudioRtf() {
        val clock = ManualClock()
        val telemetry = AgentTelemetry(
            nanoTime = clock::now,
            processMemory = { AgentProcessMemorySnapshot() }
        )

        val generationStarted = telemetry.beginPhase("generation-buffered")
        clock.advanceMillis(1_500L)
        telemetry.finishGeneration(
            startedAtNanos = generationStarted,
            kind = "buffered",
            text = "deterministic chunk",
            audioSampleCount = 24_000,
            sampleRate = 24_000,
            reportedTimeMillis = 1_200L,
            success = true
        )

        val validationStarted = telemetry.beginPhase("validation")
        clock.advanceMillis(275L)
        telemetry.finishValidation(validationStarted, passed = true)

        val snapshot = telemetry.snapshot(persistedAudioDurationSeconds = 3.0)
        val generation = snapshot.generation
        assertEquals(1, generation.attemptedCalls)
        assertEquals(1, generation.successfulCalls)
        assertEquals(1_500L, generation.totalElapsedMillis)
        assertEquals(1.0, generation.attemptedAudioDurationSeconds!!, 0.000001)
        assertEquals(3.0, generation.audioDurationSeconds!!, 0.000001)
        assertEquals("persisted_manifest", generation.audioDurationSource)
        assertEquals(0.5, generation.realTimeFactor!!, 0.000001)
        assertEquals(0.4, generation.reportedRealTimeFactor!!, 0.000001)
        assertEquals(1.5, generation.records.single().realTimeFactor!!, 0.000001)

        val validation = snapshot.validation
        assertEquals(1, validation.runs)
        assertEquals(275L, validation.totalElapsedMillis)
        assertEquals(true, validation.lastPassed)
        assertEquals(275L, validation.records.single().elapsedMillis)
        assertTrue(snapshot.phaseTimings.any { it.phase == "validation" && it.successful == true })
    }

    @Test
    fun failingOptionalProvidersDoNotBreakTelemetryCollection() {
        val clock = ManualClock()
        val telemetry = AgentTelemetry(
            nanoTime = clock::now,
            processMemory = { error("host metrics unavailable") }
        )
        telemetry.attachBackendMemoryProvider { error("backend metrics unavailable") }

        val started = telemetry.beginPhase("generation-buffered")
        clock.advanceMillis(10L)
        telemetry.finishGeneration(
            startedAtNanos = started,
            kind = "buffered",
            text = "safe",
            audioSampleCount = null,
            sampleRate = null,
            reportedTimeMillis = null,
            success = false
        )

        val snapshot = telemetry.snapshot()
        assertEquals(1, snapshot.generation.attemptedCalls)
        assertEquals(10L, snapshot.generation.totalElapsedMillis)
        assertEquals(0, snapshot.memory.deviceBackend.sampleCount)
        assertNotNull(snapshot.samples.singleOrNull { it.phase == "generation-buffered-end" })
    }

    private class ManualClock(private var nanos: Long = 0L) {
        fun now(): Long = nanos

        fun advanceMillis(millis: Long) {
            nanos += millis * 1_000_000L
        }
    }
}
