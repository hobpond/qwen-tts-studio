package com.qwen.tts.studio.agent

import com.qwen.tts.studio.batch.BatchAudioStore
import com.qwen.tts.studio.batch.BatchChunk
import com.qwen.tts.studio.batch.BatchChunkStatus
import com.qwen.tts.studio.batch.BatchManifest
import com.qwen.tts.studio.engine.NativeBackendPreference
import com.qwen.tts.studio.engine.QwenAsrEngine
import com.qwen.tts.studio.batch.AsrWindow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsrConcurrencyRunnerTest {
    @Test
    fun parallelProfileLoadsEachEngineOnceKeepsThemLoadedAndMatchesSerialBaseline() {
        val root = Files.createTempDirectory("asr-concurrency-runner")
        val manifestPath = writeManifest(root)
        val created = mutableListOf<FakeEngine>()
        val factory = AsrConcurrencyEngineFactory { backend ->
            FakeEngine(backend).also { created += it }
        }

        val report = AsrConcurrencyRunner.execute(
            AsrConcurrencyOptions(
                profile = AsrConcurrencyProfile.ParallelCuda2,
                outputDirectory = root.resolve("report"),
                manifestPath = manifestPath,
                asrModelPath = root.resolve("asr.gguf"),
                maxChunks = 1,
                warmupRounds = 1,
                measuredRounds = 2,
                timeoutMillis = 5_000L
            ),
            factory
        )

        assertTrue(report.passed, report.error ?: report.toJson())
        assertEquals(2, created.size)
        assertTrue(created.all { it.loadCalls == 1 })
        assertTrue(created.all { it.closeCalls == 1 })
        assertEquals(2, report.engineCount)
        assertEquals(2, report.engines.size)
        assertEquals(2, report.phases.first { it.phase == "warmup" }.callCount)
        assertEquals(4, report.phases.first { it.phase == "serial-baseline" }.callCount)
        assertEquals(4, report.phases.first { it.phase == "parallel" }.callCount)
        assertTrue(report.calls.filter { it.phase == "parallel" }.all { it.baselineMatched == true })
        assertTrue(report.decision?.eligibleForConcurrentAsr == true, report.toJson())
        assertTrue(report.toJson().contains("eligibleForConcurrentAsr"))
    }

    @Test
    fun cudaGateFailsClosedWhenDeviceMemoryEvidenceIsMissing() {
        val decision = AsrConcurrencyGate.evaluate(
            AsrConcurrencyGateInput(
                profile = AsrConcurrencyProfile.ParallelCuda2,
                backend = NativeBackendPreference.Cuda,
                allCallsSuccessful = true,
                baselineMismatches = 0,
                minimumFreeBytes = null,
                totalBytes = null,
                serialElapsedMillis = 100L,
                parallelElapsedMillis = 50L,
                p95ParallelCallMillis = 10L,
                p95SerialCallMillis = 10L,
                memoryGrowthBytes = 0L
            )
        )

        assertFalse(decision.eligibleForConcurrentAsr)
        assertTrue(decision.reasons.any { it.contains("memory evidence", ignoreCase = true) })
    }

    @Test
    fun cpuProfilesCanPassWithoutDeviceMemoryMetricsButNeverBecomeCudaEligible() {
        val decision = AsrConcurrencyGate.evaluate(
            AsrConcurrencyGateInput(
                profile = AsrConcurrencyProfile.ParallelCpu2,
                backend = NativeBackendPreference.Cpu,
                allCallsSuccessful = true,
                baselineMismatches = 0,
                minimumFreeBytes = null,
                totalBytes = null,
                serialElapsedMillis = 100L,
                parallelElapsedMillis = 50L,
                p95ParallelCallMillis = 10L,
                p95SerialCallMillis = 10L,
                memoryGrowthBytes = null
            )
        )

        assertTrue(decision.profilePassed)
        assertFalse(decision.eligibleForConcurrentAsr)
    }

    private fun writeManifest(root: java.nio.file.Path): java.nio.file.Path {
        Files.writeString(root.resolve("asr.gguf"), "fake model")
        Files.write(root.resolve("chunk-000000.wav"), byteArrayOf(1, 2, 3))
        val manifest = BatchManifest(
            batchId = "asr-test",
            expectedChunkCount = 1,
            chunks = listOf(
                BatchChunk(
                    index = 0,
                    fileName = "chunk-000000.wav",
                    text = "first chunk",
                    status = BatchChunkStatus.COMPLETE
                )
            )
        )
        val store = BatchAudioStore(root)
        store.persistManifest(manifest)
        return root.resolve("manifest.json")
    }

    private class FakeEngine(private val backend: NativeBackendPreference) : AsrConcurrencyEngine {
        var loadCalls = 0
        var closeCalls = 0

        override fun load(modelFile: java.io.File) {
            loadCalls++
            assertTrue(modelFile.isFile)
        }

        override fun backendInfo(): QwenAsrEngine.BackendInfo =
            if (backend == NativeBackendPreference.Cuda) {
                QwenAsrEngine.BackendInfo(backend, "CUDA0", true, true, true, 8L * 1024 * 1024 * 1024, 12L * 1024 * 1024 * 1024)
            } else {
                QwenAsrEngine.BackendInfo(backend, "CPU", false, false, false, null, null)
            }

        override fun transcribe(
            wav: java.io.File,
            window: AsrWindow,
            maxTokens: Int,
            threads: Int
        ): QwenAsrEngine.NativeResult {
            Thread.sleep(5L)
            return QwenAsrEngine.NativeResult("first chunk", "en", true, null, 5L)
        }

        override fun close() {
            closeCalls++
        }
    }
}
