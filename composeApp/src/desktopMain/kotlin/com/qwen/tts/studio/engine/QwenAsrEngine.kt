package com.qwen.tts.studio.engine

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/** In-process Qwen3-ASR facade backed by the same native DLL and GGML build as TTS. */
class QwenAsrEngine : AutoCloseable {
    data class NativeResult(
        val text: String,
        val language: String,
        val success: Boolean,
        val errorMsg: String?,
        val timeMs: Long
    )

    private var nativePtr: Long = 0L

    init {
        QwenEngine.ensureNativeLibrary()
        nativePtr = nativeInit()
        check(nativePtr != 0L) { "Could not initialize the native Qwen3-ASR engine." }
    }

    fun load(modelFile: File) {
        require(modelFile.isFile) { "Qwen3-ASR model not found: ${modelFile.absolutePath}" }
        check(nativeLoadModel(nativePtr, modelFile.absolutePath)) {
            "Could not load Qwen3-ASR model: ${modelFile.absolutePath}"
        }
    }

    fun transcribe(wav: File, window: AsrAudioWindow? = null, maxTokens: Int = 1024, threads: Int = 4): NativeResult {
        require(wav.isFile) { "Audio file not found: ${wav.absolutePath}" }
        val source = AudioSystem.getAudioInputStream(wav)
        val targetFormat = AudioFormat(16_000f, 16, 1, true, false)
        val converted = AudioSystem.getAudioInputStream(targetFormat, source)
        val bytes = converted.use { it.readBytes() }
        val allSamples = FloatArray(bytes.size / 2) { index ->
            val lo = bytes[index * 2].toInt() and 0xff
            val hi = bytes[index * 2 + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
        val samples = window?.select(allSamples) ?: allSamples
        val result = nativeTranscribeSamples(nativePtr, samples, maxTokens, threads)
            ?: error("Native Qwen3-ASR returned no result.")
        check(result.success) { result.errorMsg ?: "Qwen3-ASR transcription failed." }
        return result
    }

    override fun close() {
        if (nativePtr != 0L) {
            nativeFree(nativePtr)
            nativePtr = 0L
        }
    }

    private external fun nativeInit(): Long
    private external fun nativeFree(ptr: Long)
    private external fun nativeLoadModel(ptr: Long, modelPath: String): Boolean
    private external fun nativeTranscribeSamples(ptr: Long, samples: FloatArray, maxTokens: Int, threads: Int): NativeResult?
}

enum class AsrAudioWindow {
    PREFIX,
    SUFFIX;

    fun select(samples: FloatArray): FloatArray {
        val windowSamples = (16_000 * 5).coerceAtMost(samples.size)
        return when (this) {
            PREFIX -> samples.copyOfRange(0, windowSamples)
            SUFFIX -> samples.copyOfRange(samples.size - windowSamples, samples.size)
        }
    }
}
