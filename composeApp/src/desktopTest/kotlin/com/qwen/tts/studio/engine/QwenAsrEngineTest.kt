package com.qwen.tts.studio.engine

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QwenAsrEngineTest {
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
        val freedPointers = mutableListOf<Long>()

        override fun init(): Long = 7L

        override fun free(ptr: Long) {
            freedPointers += ptr
        }

        override fun loadModel(ptr: Long, modelPath: String): Boolean = loadResult

        override fun transcribe(
            ptr: Long,
            samples: FloatArray,
            maxTokens: Int,
            threads: Int
        ): QwenAsrEngine.NativeResult? = null
    }
}
