package com.qwen.tts.studio.engine

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QwenAsrEngineTest {
    @Test
    fun cpuIsTheDefaultAndBackendPreferenceCrossesNativeSeam() {
        val native = FakeNativeApi(loadResult = true)
        val engine = QwenAsrEngine(native)

        assertEquals(NativeBackendPreference.Cpu, native.initializedPreference)
        engine.close()
    }

    @Test
    fun cudaMustBeRequestedExplicitlyAndBackendEvidenceIsObservable() {
        val native = FakeNativeApi(
            loadResult = true,
            backendName = "CUDA0",
            backendEvidence = longArrayOf(1, 1, 1, 5_000L, 8_000L)
        )
        val engine = QwenAsrEngine(native, NativeBackendPreference.Cuda)

        assertEquals(NativeBackendPreference.Cuda, native.initializedPreference)
        val info = engine.backendInfo()
        assertEquals("CUDA0", info?.name)
        assertTrue(info?.gpuActive == true)
        assertTrue(info?.weightsOnGpu == true)
        assertEquals(5_000L, info?.freeBytes)
        assertEquals(8_000L, info?.totalBytes)
        engine.close()
    }

    @Test
    fun validationWindowsNeverMaterializeMoreThanFiveSeconds() {
        val samples = FloatArray(AsrAudioWindow.MAX_SAMPLES + 100) { it.toFloat() }

        val prefix = AsrAudioWindow.PREFIX.select(samples)
        val suffix = AsrAudioWindow.SUFFIX.select(samples)

        assertEquals(AsrAudioWindow.MAX_SAMPLES, prefix.size)
        assertEquals(AsrAudioWindow.MAX_SAMPLES, suffix.size)
        assertEquals(samples.first(), prefix.first())
        assertEquals(samples[100], suffix.first())
        assertEquals(samples.last(), suffix.last())
    }

    @Test
    fun failedModelLoadClosesNativeEngine() {
        val native = FakeNativeApi(loadResult = false)
        val engine = QwenAsrEngine(native)
        val model = Files.createTempFile("qwen-asr", ".gguf").toFile()

        val failure = assertFailsWith<IllegalStateException> { engine.load(model) }

        assertTrue(failure.message!!.contains("Could not load Qwen3-ASR model"))
        assertEquals(listOf(7L), native.freedPointers)
        engine.close()
        assertEquals(listOf(7L), native.freedPointers)
    }

    private class FakeNativeApi(private val loadResult: Boolean) : QwenAsrNativeApi {
        constructor(
            loadResult: Boolean,
            backendName: String,
            backendEvidence: LongArray
        ) : this(loadResult) {
            this.backendName = backendName
            this.backendEvidence = backendEvidence
        }

        val freedPointers = mutableListOf<Long>()
        var initializedPreference: NativeBackendPreference? = null
        private var backendName = "CPU"
        private var backendEvidence = longArrayOf(0, 0, 0, -1, -1)

        override fun init(backendPreference: NativeBackendPreference): Long {
            initializedPreference = backendPreference
            return 7L
        }

        override fun free(ptr: Long) {
            freedPointers += ptr
        }

        override fun loadModel(ptr: Long, modelPath: String): Boolean = loadResult

        override fun backendName(ptr: Long): String = backendName

        override fun backendEvidence(ptr: Long): LongArray = backendEvidence

        override fun transcribe(
            ptr: Long,
            samples: FloatArray,
            maxTokens: Int,
            threads: Int
        ): QwenAsrEngine.NativeResult? = null
    }
}
