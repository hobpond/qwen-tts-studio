package com.qwen.tts.studio.engine

import java.io.File
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

internal interface QwenAsrNativeApi {
    fun init(): Long
    fun free(ptr: Long)
    fun loadModel(ptr: Long, modelPath: String): Boolean
    fun transcribe(ptr: Long, samples: FloatArray, maxTokens: Int, threads: Int): QwenAsrEngine.NativeResult?
}

/** In-process Qwen3-ASR facade backed by the same native DLL and GGML build as TTS. */
class QwenAsrEngine private constructor(
    private val nativeApi: QwenAsrNativeApi?,
    private val ensureNativeLibrary: Boolean
) : AutoCloseable {
    constructor() : this(null, true)

    internal constructor(nativeApi: QwenAsrNativeApi) : this(nativeApi, false)

    data class NativeResult(
        val text: String,
        val language: String,
        val success: Boolean,
        val errorMsg: String?,
        val timeMs: Long
    )

    private var nativePtr: Long = 0L

    init {
        if (ensureNativeLibrary) QwenEngine.ensureNativeLibrary()
        nativePtr = nativeApi?.init() ?: nativeInit()
        check(nativePtr != 0L) { "Could not initialize the native Qwen3-ASR engine." }
    }

    fun load(modelFile: File) {
        try {
            require(modelFile.isFile) { "Qwen3-ASR model not found: ${modelFile.absolutePath}" }
            val loaded = nativeApi?.loadModel(nativePtr, modelFile.absolutePath)
                ?: nativeLoadModel(nativePtr, modelFile.absolutePath)
            check(loaded) {
                val nativeError = if (nativeApi == null) nativeGetLastError(nativePtr) else null
                "Could not load Qwen3-ASR model: ${nativeError ?: modelFile.absolutePath}"
            }
        } catch (error: Throwable) {
            runCatching { close() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    fun transcribe(wav: File, window: AsrAudioWindow? = null, maxTokens: Int = 1024, threads: Int = 4): NativeResult {
        require(wav.isFile) { "Audio file not found: ${wav.absolutePath}" }
        val targetFormat = AudioFormat(16_000f, 16, 1, true, false)
        val samples = AudioSystem.getAudioInputStream(wav).use { source ->
            AudioSystem.getAudioInputStream(targetFormat, source).use { converted ->
                readSamples(converted, window)
            }
        }
        val result = (nativeApi?.transcribe(nativePtr, samples, maxTokens, threads)
            ?: nativeTranscribeSamples(nativePtr, samples, maxTokens, threads))
            ?: error("Native Qwen3-ASR returned no result.")
        check(result.success) { result.errorMsg ?: "Qwen3-ASR transcription failed." }
        return result
    }

    override fun close() {
        if (nativePtr != 0L) {
            nativeApi?.free(nativePtr) ?: nativeFree(nativePtr)
            nativePtr = 0L
        }
    }

    private external fun nativeInit(): Long
    private external fun nativeFree(ptr: Long)
    private external fun nativeLoadModel(ptr: Long, modelPath: String): Boolean
    private external fun nativeGetLastError(ptr: Long): String?
    private external fun nativeTranscribeSamples(ptr: Long, samples: FloatArray, maxTokens: Int, threads: Int): NativeResult?

    private fun readSamples(stream: AudioInputStream, window: AsrAudioWindow?): FloatArray {
        if (window == null) return readAllSamples(stream)

        val maximum = AsrAudioWindow.MAX_SAMPLES
        val knownFrames = stream.frameLength
        if (knownFrames != AudioSystem.NOT_SPECIFIED.toLong()) {
            if (window == AsrAudioWindow.SUFFIX) {
                skipFrames(stream, (knownFrames - maximum).coerceAtLeast(0L))
            }
            return readFrames(stream, maximum)
        }

        // Some decoders do not publish a frame length. Keep only the tail in
        // that case so a long file cannot turn a five-second validation window
        // into an unbounded FloatArray.
        return if (window == AsrAudioWindow.PREFIX) readFrames(stream, maximum) else readTail(stream, maximum)
    }

    private fun readFrames(stream: AudioInputStream, maximumFrames: Int): FloatArray {
        val bytes = ByteArray(maximumFrames * TARGET_FRAME_SIZE)
        var count = 0
        while (count < bytes.size) {
            val read = stream.read(bytes, count, bytes.size - count)
            if (read < 0) break
            if (read == 0) continue
            count += read
        }
        return decodePcm16(bytes, count)
    }

    private fun readTail(stream: AudioInputStream, maximumFrames: Int): FloatArray {
        val ring = FloatArray(maximumFrames)
        val bytes = ByteArray(8 * 1024)
        var pendingByte = -1
        var sampleCount = 0
        while (true) {
            val read = stream.read(bytes)
            if (read < 0) break
            if (read == 0) continue
            for (index in 0 until read) {
                val value = bytes[index].toInt() and 0xff
                if (pendingByte < 0) {
                    pendingByte = value
                } else {
                    val sample = ((value shl 8) or pendingByte).toShort() / 32768f
                    ring[sampleCount % maximumFrames] = sample
                    sampleCount++
                    pendingByte = -1
                }
            }
        }
        val count = minOf(sampleCount, maximumFrames)
        if (sampleCount <= maximumFrames) return ring.copyOf(count)
        val start = sampleCount % maximumFrames
        return FloatArray(count) { ring[(start + it) % maximumFrames] }
    }

    private fun readAllSamples(stream: AudioInputStream): FloatArray {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read > 0) bytes.write(buffer, 0, read)
        }
        val materialized = bytes.toByteArray()
        return decodePcm16(materialized, materialized.size)
    }

    private fun skipFrames(stream: AudioInputStream, frames: Long) {
        var remaining = frames * TARGET_FRAME_SIZE
        val scratch = ByteArray(8 * 1024)
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val read = stream.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read < 0) break
            if (read == 0) continue
            remaining -= read
        }
    }

    private fun decodePcm16(bytes: ByteArray, byteCount: Int): FloatArray {
        val sampleCount = byteCount / TARGET_FRAME_SIZE
        return FloatArray(sampleCount) { index ->
            val lo = bytes[index * 2].toInt() and 0xff
            val hi = bytes[index * 2 + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
    }

    private companion object {
        const val TARGET_FRAME_SIZE = 2
    }
}

enum class AsrAudioWindow {
    PREFIX,
    SUFFIX;

    companion object {
        const val MAX_SAMPLES = 16_000 * 5
    }

    fun select(samples: FloatArray): FloatArray {
        val windowSamples = MAX_SAMPLES.coerceAtMost(samples.size)
        return when (this) {
            PREFIX -> samples.copyOfRange(0, windowSamples)
            SUFFIX -> samples.copyOfRange(samples.size - windowSamples, samples.size)
        }
    }
}
