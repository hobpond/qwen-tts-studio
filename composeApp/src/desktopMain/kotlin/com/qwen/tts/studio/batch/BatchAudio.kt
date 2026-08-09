package com.qwen.tts.studio.batch

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

/** The result of a generator that knows the sample rate of its output. */
data class GeneratedAudio(val samples: FloatArray, val sampleRate: Int) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(samples.all(Float::isFinite)) { "Audio contains a non-finite sample" }
    }
}

/** A callback can be backed by any generator; it deliberately has no engine dependency. */
fun interface BatchAudioGenerator {
    fun generate(request: BatchChunkRequest): GeneratedAudio
}

data class BatchChunkRequest(val index: Int, val text: String)

enum class BatchChunkStatus { PENDING, COMPLETE, FAILED, CANCELLED }

data class BatchChunk(
    val index: Int,
    val fileName: String,
    val text: String = "",
    val status: BatchChunkStatus = BatchChunkStatus.PENDING,
    val sampleRate: Int? = null,
    val frameCount: Long? = null,
    val sha256: String? = null,
    val error: String? = null
)

data class BatchManifest(
    val batchId: String,
    val expectedChunkCount: Int,
    val chunks: List<BatchChunk>,
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(batchId.isNotBlank()) { "batchId must not be blank" }
        require(expectedChunkCount >= 0) { "expectedChunkCount must not be negative" }
        require(chunks.map(BatchChunk::index).distinct().size == chunks.size) { "Duplicate chunk index" }
        require(chunks.all { it.index in 0 until expectedChunkCount }) { "Chunk index is outside the batch" }
    }

    fun withChunk(chunk: BatchChunk): BatchManifest =
        copy(chunks = (chunks.filterNot { it.index == chunk.index } + chunk).sortedBy { it.index })
}

/** Desktop persistence and strict recombination for generated mono PCM audio. */
class BatchAudioStore(directory: Path) {
    private val directory = directory.toAbsolutePath().normalize()
    val directoryPath: Path get() = directory
    private val manifestPath = directory.resolve("manifest.json")

    fun loadManifest(path: Path = manifestPath): BatchManifest {
        val normalized = path.toAbsolutePath().normalize()
        require(Files.isRegularFile(normalized)) { "Manifest does not exist: $normalized" }
        return BatchManifestCodec.decode(Files.readString(normalized, StandardCharsets.UTF_8))
    }

    /** Rebuilds a manifest from the inspectable chunk text sidecars and existing WAVs. */
    fun generateManifestFromSidecars(
        batchId: String,
        metadata: Map<String, String> = emptyMap()
    ): BatchManifest {
        val textFiles = Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().matches(Regex("chunk-\\d{6}\\.txt")) }
                .sorted(compareBy { it.fileName.toString() })
                .toList()
        }
        require(textFiles.isNotEmpty()) { "No chunk-XXXXXX.txt sidecars were found in $directory" }
        val chunks = textFiles.mapIndexed { expectedIndex, textFile ->
            val name = textFile.fileName.toString()
            val index = name.removePrefix("chunk-").removeSuffix(".txt").toInt()
            require(index == expectedIndex) { "Chunk sidecars must be contiguous starting at chunk-000000.txt" }
            val text = Files.readString(textFile, StandardCharsets.UTF_8)
            val wavFile = safeChild(chunkFileName(index))
            val base = BatchChunk(index, chunkFileName(index), text)
            if (!Files.isRegularFile(wavFile)) base else runCatching {
                val wav = Files.readAllBytes(wavFile)
                val parsed = parseWav(wav)
                base.copy(status = BatchChunkStatus.COMPLETE, sampleRate = parsed.sampleRate,
                    frameCount = parsed.frameCount, sha256 = sha256(wav))
            }.getOrElse { base.copy(error = it.message) }
        }
        return BatchManifest(batchId, chunks.size, chunks, metadata).also(::persistManifest)
    }

    /** Creates chunk sidecars and a manifest from the selected source chunks. */
    fun generateManifestFromTexts(
        batchId: String,
        texts: List<String>,
        metadata: Map<String, String> = emptyMap()
    ): BatchManifest {
        require(texts.isNotEmpty()) { "No source chunks were supplied" }
        val chunks = texts.mapIndexed { index, text ->
            val fileName = chunkFileName(index)
            writeAtomic(safeChild(textFileName(index)), text.toByteArray(StandardCharsets.UTF_8))
            val wavFile = safeChild(fileName)
            val base = BatchChunk(index, fileName, text)
            if (!Files.isRegularFile(wavFile)) base else runCatching {
                val wav = Files.readAllBytes(wavFile)
                val parsed = parseWav(wav)
                base.copy(status = BatchChunkStatus.COMPLETE, sampleRate = parsed.sampleRate,
                    frameCount = parsed.frameCount, sha256 = sha256(wav))
            }.getOrElse { base.copy(error = it.message) }
        }
        return BatchManifest(batchId, chunks.size, chunks, metadata).also(::persistManifest)
    }

    fun validateChunkFile(chunk: BatchChunk): ChunkAudioMetadata {
        val wav = Files.readAllBytes(safeChild(chunk.fileName))
        val parsed = parseWav(wav)
        require(chunk.status == BatchChunkStatus.COMPLETE) { "Chunk ${chunk.index} is not complete" }
        require(parsed.sampleRate == chunk.sampleRate) { "Chunk ${chunk.index} sample rate mismatch" }
        require(parsed.frameCount == chunk.frameCount) { "Chunk ${chunk.index} frame count mismatch" }
        require(sha256(wav) == chunk.sha256) { "Chunk ${chunk.index} checksum mismatch" }
        return ChunkAudioMetadata(parsed.sampleRate, parsed.frameCount, wav.size)
    }

    fun createManifest(
        batchId: String,
        expectedChunkCount: Int,
        metadata: Map<String, String> = emptyMap()
    ): BatchManifest =
        BatchManifest(batchId, expectedChunkCount, emptyList(), metadata).also(::persistManifest)

    /** Reopens a compatible batch and validates existing chunk files for resume. */
    fun createOrResumeManifest(batchId: String, texts: List<String>, metadata: Map<String, String> = emptyMap()): BatchManifest {
        require(texts.isNotEmpty()) { "texts must not be empty" }
        val compatible = if (Files.exists(manifestPath)) {
            val existing = Files.readString(manifestPath, StandardCharsets.UTF_8)
            // The output directory is the durable batch identity. The UI may create a
            // fresh transient request ID after a restart, so requiring batchId here
            // would make every resume look like a new batch. Stable model/voice/text
            // metadata below still prevents adopting unrelated output.
            existing.contains("\"expectedChunkCount\":${texts.size}") &&
                metadata.filterKeys { it != "batchMaxCharacters" }.all { (key, value) ->
                    existing.contains("\"${jsonEscape(key)}\":\"${jsonEscape(value)}\"")
                }
        } else false
        if (!compatible) {
            return BatchManifest(
                batchId,
                texts.size,
                texts.mapIndexed { index, text -> BatchChunk(index, chunkFileName(index), text) },
                metadata
            ).also(::persistManifest)
        }
        val resumed = texts.mapIndexed { index, text ->
            val fileName = chunkFileName(index)
            val file = safeChild(fileName)
            if (!Files.isRegularFile(file)) return@mapIndexed BatchChunk(index, fileName, text)
            runCatching {
                val wav = Files.readAllBytes(file)
                val parsed = parseWav(wav)
                val textFile = safeChild(textFileName(index))
                if (!Files.isRegularFile(textFile) || Files.readString(textFile, StandardCharsets.UTF_8) != text) {
                    writeAtomic(textFile, text.toByteArray(StandardCharsets.UTF_8))
                }
                BatchChunk(index, fileName, text, BatchChunkStatus.COMPLETE, parsed.sampleRate,
                    parsed.frameCount, sha256(wav))
            }.getOrElse { BatchChunk(index, fileName, text, error = it.message) }
        }
        return BatchManifest(batchId, texts.size, resumed, metadata).also(::persistManifest)
    }

    fun writeChunk(manifest: BatchManifest, index: Int, audio: GeneratedAudio, text: String = ""): BatchManifest {
        val chunk = writeChunkFile(manifest, index, audio, text)
        return manifest.withChunk(chunk).also(::persistManifest)
    }

    /** Encodes and atomically writes one chunk without changing the manifest. */
    fun writeChunkFile(manifest: BatchManifest, index: Int, audio: GeneratedAudio, text: String = ""): BatchChunk {
        require(index in 0 until manifest.expectedChunkCount) { "Chunk index is outside the batch" }
        val fileName = manifest.chunks.firstOrNull { it.index == index }?.fileName ?: chunkFileName(index)
        val target = safeChild(fileName)
        val pcm = encodePcm16(audio.samples)
        val wav = wavBytes(audio.sampleRate, pcm)
        writeAtomic(safeChild(textFileName(index)), text.toByteArray(StandardCharsets.UTF_8))
        writeAtomic(target, wav)
        return BatchChunk(index, fileName, text, BatchChunkStatus.COMPLETE, audio.sampleRate,
            audio.samples.size.toLong(), sha256(wav))
    }

    fun markFailed(manifest: BatchManifest, index: Int, error: String): BatchManifest =
        manifest.withChunk(BatchChunk(index, manifest.chunks.firstOrNull { it.index == index }?.fileName ?: chunkFileName(index),
            manifest.chunks.firstOrNull { it.index == index }?.text ?: "",
            BatchChunkStatus.FAILED, error = error)).also(::persistManifest)

    fun markCancelled(manifest: BatchManifest, index: Int): BatchManifest =
        manifest.withChunk(BatchChunk(index, manifest.chunks.firstOrNull { it.index == index }?.fileName ?: chunkFileName(index),
            manifest.chunks.firstOrNull { it.index == index }?.text ?: "",
            BatchChunkStatus.CANCELLED, error = "Cancelled")).also(::persistManifest)

    fun persistManifest(manifest: BatchManifest) {
        Files.createDirectories(directory)
        writeAtomic(manifestPath, manifestJson(manifest).toByteArray(StandardCharsets.UTF_8))
    }

    /** Verifies every manifest entry and WAV before creating the combined output. */
    fun recombine(manifest: BatchManifest, output: Path): Path {
        require(manifest.expectedChunkCount > 0) { "Cannot recombine an empty batch" }
        require(manifest.chunks.size == manifest.expectedChunkCount) { "Batch is incomplete" }
        val ordered = manifest.chunks.sortedBy { it.index }
        require(ordered.map(BatchChunk::index) == (0 until manifest.expectedChunkCount).toList()) { "Batch indexes are incomplete" }
        var sampleRate: Int? = null
        var totalPcmBytes = 0L
        ordered.forEach { chunk ->
            require(chunk.status == BatchChunkStatus.COMPLETE) { "Chunk ${chunk.index} is not complete" }
            val wav = safeChild(chunk.fileName).let(Files::readAllBytes)
            val parsed = parseWav(wav)
            require(parsed.sampleRate == chunk.sampleRate && parsed.frameCount == chunk.frameCount) { "Chunk ${chunk.index} metadata does not match WAV" }
            require(sha256(wav) == chunk.sha256) { "Chunk ${chunk.index} checksum mismatch" }
            if (sampleRate == null) sampleRate = parsed.sampleRate
            require(parsed.sampleRate == sampleRate) { "Chunks do not share one sample rate" }
            totalPcmBytes += parsed.pcm.size
        }
        require(totalPcmBytes <= Int.MAX_VALUE - 44) { "Combined WAV is too large" }
        val destination = output.toAbsolutePath().normalize()
        require(ordered.none { safeChild(it.fileName).toAbsolutePath().normalize() == destination }) {
            "Combined output must not overwrite a batch chunk"
        }
        Files.createDirectories(destination.parent)
        writeCombinedAtomic(destination, ordered, sampleRate!!, totalPcmBytes.toInt())
        return destination
    }

    private fun safeChild(fileName: String): Path {
        require(fileName.isNotBlank() && Path.of(fileName).nameCount == 1) { "Chunk file must be a direct child" }
        val child = directory.resolve(fileName).normalize()
        require(child.parent == directory.toAbsolutePath().normalize()) { "Chunk file escapes batch directory" }
        return child
    }

    private fun writeAtomic(target: Path, bytes: ByteArray) {
        val temp = target.resolveSibling(".${target.fileName}.tmp-${UUID.randomUUID()}")
        try {
            Files.write(temp, bytes)
            try {
                Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                throw IOException("Atomic rename is not supported for ${target.fileSystem}", e)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun writeCombinedAtomic(target: Path, chunks: List<BatchChunk>, sampleRate: Int, pcmBytes: Int) {
        val temp = target.resolveSibling(".${target.fileName}.tmp-${UUID.randomUUID()}")
        try {
            Files.newOutputStream(temp).use { output ->
                output.write(wavHeader(sampleRate, pcmBytes))
                chunks.forEach { chunk -> output.write(parseWav(Files.readAllBytes(safeChild(chunk.fileName))).pcm) }
            }
            try {
                Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                throw IOException("Atomic rename is not supported for ${target.fileSystem}", e)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private data class ParsedWav(val sampleRate: Int, val frameCount: Long, val pcm: ByteArray)

    private fun parseWav(bytes: ByteArray): ParsedWav {
        require(bytes.size >= 44 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WAVE")) { "Invalid WAV" }
        require(u32(bytes, 4) == bytes.size - 8L) { "Invalid RIFF size" }
        var cursor = 12
        var sampleRate: Int? = null
        var pcm: ByteArray? = null
        while (cursor + 8 <= bytes.size) {
            val size = u32(bytes, cursor + 4)
            require(size <= Int.MAX_VALUE && cursor + 8L + size <= bytes.size) { "Invalid WAV chunk size" }
            when (String(bytes, cursor, 4, StandardCharsets.US_ASCII)) {
                "fmt " -> {
                    require(size >= 16) { "Invalid WAV format chunk" }
                    val p = cursor + 8
                    require(u16(bytes, p) == 1 && u16(bytes, p + 2) == 1 && u16(bytes, p + 14) == 16) { "WAV is not mono PCM16" }
                    require(u16(bytes, p + 12) == 2) { "Invalid WAV block alignment" }
                    sampleRate = u32(bytes, p + 4).toInt()
                    require(sampleRate!! > 0) { "Invalid WAV sample rate" }
                    require(u32(bytes, p + 8) == sampleRate!! * 2L) { "Invalid WAV byte rate" }
                }
                "data" -> require(pcm == null) { "Duplicate WAV data chunk" }.also { pcm = bytes.copyOfRange(cursor + 8, cursor + 8 + size.toInt()) }
            }
            cursor += 8 + size.toInt() + (size.toInt() and 1)
        }
        require(cursor == bytes.size && sampleRate != null && pcm != null && pcm!!.size % 2 == 0) { "Incomplete WAV" }
        return ParsedWav(sampleRate!!, pcm!!.size.toLong() / 2, pcm!!)
    }

    private fun manifestJson(manifest: BatchManifest): String = buildString {
        append("{\"batchId\":\"").append(jsonEscape(manifest.batchId)).append("\",\"expectedChunkCount\":").append(manifest.expectedChunkCount).append(",\"metadata\":{")
        manifest.metadata.entries.sortedBy { it.key }.forEachIndexed { i, (key, value) ->
            if (i > 0) append(',')
            append('"').append(jsonEscape(key)).append("\":\"").append(jsonEscape(value)).append('"')
        }
        append("},\"chunks\":[")
        manifest.chunks.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append("{\"index\":").append(c.index).append(",\"fileName\":\"").append(jsonEscape(c.fileName)).append("\",\"text\":\"").append(jsonEscape(c.text)).append("\",\"status\":\"").append(c.status).append('\"')
            c.sampleRate?.let { append(",\"sampleRate\":").append(it) }; c.frameCount?.let { append(",\"frameCount\":").append(it) }; c.sha256?.let { append(",\"sha256\":\"").append(it).append('\"') }; c.error?.let { append(",\"error\":\"").append(jsonEscape(it)).append('\"') }
            append('}')
        }
        append("]}\n")
    }

    private fun jsonEscape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    private fun chunkFileName(index: Int) = "chunk-%06d.wav".format(index)
    private fun textFileName(index: Int) = "chunk-%06d.txt".format(index)
    private fun ascii(bytes: ByteArray, offset: Int, value: String) = String(bytes, offset, 4, StandardCharsets.US_ASCII) == value
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, o: Int) = (b[o].toLong() and 255) or ((b[o + 1].toLong() and 255) shl 8) or ((b[o + 2].toLong() and 255) shl 16) or ((b[o + 3].toLong() and 255) shl 24)

    private fun encodePcm16(samples: FloatArray): ByteArray = ByteArray(samples.size * 2).also { out ->
        samples.forEachIndexed { i, sample ->
            val value = (sample.coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
            out[i * 2] = value.toByte(); out[i * 2 + 1] = (value shr 8).toByte()
        }
    }

    private fun wavHeader(sampleRate: Int, pcmSize: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(StandardCharsets.US_ASCII)); putInt(36 + pcmSize); put("WAVE".toByteArray(StandardCharsets.US_ASCII)); put("fmt ".toByteArray(StandardCharsets.US_ASCII)); putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16); put("data".toByteArray(StandardCharsets.US_ASCII)); putInt(pcmSize)
    }.array()

    private fun wavBytes(sampleRate: Int, pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(StandardCharsets.US_ASCII)); putInt(36 + pcm.size); put("WAVE".toByteArray(StandardCharsets.US_ASCII)); put("fmt ".toByteArray(StandardCharsets.US_ASCII)); putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16); put("data".toByteArray(StandardCharsets.US_ASCII)); putInt(pcm.size); put(pcm)
    }.array()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

data class ChunkAudioMetadata(val sampleRate: Int, val frameCount: Long, val fileBytes: Int)
