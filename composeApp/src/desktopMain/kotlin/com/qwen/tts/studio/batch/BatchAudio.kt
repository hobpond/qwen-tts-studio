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
open class BatchAudioStore(directory: Path) {
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
        val existing = existingManifest()
        val textFiles = Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().matches(Regex("chunk-\\d{6}\\.txt")) }
                .sorted(compareBy { it.fileName.toString() })
                .toList()
        }
        require(textFiles.isNotEmpty()) { "No chunk-XXXXXX.txt sidecars were found in $directory" }
        requireCompatibleProvenance(existing, textFiles.size, metadata)
        val chunks = textFiles.mapIndexed { expectedIndex, textFile ->
            val name = textFile.fileName.toString()
            val index = name.removePrefix("chunk-").removeSuffix(".txt").toInt()
            require(index == expectedIndex) { "Chunk sidecars must be contiguous starting at chunk-000000.txt" }
            val text = Files.readString(textFile, StandardCharsets.UTF_8)
            val wavFile = safeChild(chunkFileName(index))
            val base = BatchChunk(index, chunkFileName(index), text)
            provenComplete(existing?.chunks?.firstOrNull { it.index == index }, text, wavFile, index)
                ?.let { parsed ->
                    base.copy(status = BatchChunkStatus.COMPLETE, sampleRate = parsed.sampleRate,
                        frameCount = parsed.frameCount, sha256 = existing?.chunks?.first { it.index == index }?.sha256)
                }
                ?: base
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
        val existing = existingManifest()
        requireCompatibleProvenance(existing, texts.size, metadata)
        if (existing != null) {
            validateExistingProvenance(existing)
        } else {
            validateUnownedSidecars(texts)
        }
        val chunks = texts.mapIndexed { index, text ->
            val fileName = chunkFileName(index)
            val wavFile = safeChild(fileName)
            val base = BatchChunk(index, fileName, text)
            provenComplete(existing?.chunks?.firstOrNull { it.index == index }, text, wavFile, index)
                ?.let { parsed ->
                    base.copy(status = BatchChunkStatus.COMPLETE, sampleRate = parsed.sampleRate,
                        frameCount = parsed.frameCount, sha256 = existing?.chunks?.first { it.index == index }?.sha256)
                }
                ?: base
        }
        texts.forEachIndexed { index, text ->
            writeAtomic(safeChild(textFileName(index)), text.toByteArray(StandardCharsets.UTF_8))
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
        // The output directory is the durable batch identity. The UI may create a
        // fresh transient request ID after a restart, so requiring batchId here
        // would make every resume look like a new batch. Stable metadata and the
        // persisted source text still prevent adopting unrelated output.
        val existing = if (Files.isRegularFile(manifestPath)) {
            runCatching { loadManifest() }.getOrNull()
        } else null
        val compatible = existing != null &&
            existing.expectedChunkCount == texts.size &&
            metadata.filterKeys { it != "batchMaxCharacters" }.all { (key, value) ->
                existing.metadata[key] == value
            }
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
            val previous = existing?.chunks?.firstOrNull { it.index == index }
            // A stale WAV is not evidence of a completed chunk. Only a chunk that
            // was durably marked COMPLETE for the same source text may be resumed.
            if (previous?.status != BatchChunkStatus.COMPLETE || previous.text != text || !Files.isRegularFile(file)) {
                return@mapIndexed BatchChunk(index, fileName, text)
            }
            runCatching {
                val wav = Files.readAllBytes(file)
                val parsed = parseWav(wav)
                if (previous.sha256 == null || sha256(wav) != previous.sha256) {
                    return@runCatching BatchChunk(
                        index,
                        fileName,
                        text,
                        BatchChunkStatus.PENDING,
                        error = "Existing WAV checksum mismatch; regeneration required"
                    )
                }
                val textFile = safeChild(textFileName(index))
                if (!Files.isRegularFile(textFile) || Files.readString(textFile, StandardCharsets.UTF_8) != text) {
                    writeAtomic(textFile, text.toByteArray(StandardCharsets.UTF_8))
                }
                BatchChunk(index, fileName, text, BatchChunkStatus.COMPLETE, parsed.sampleRate,
                    parsed.frameCount, previous.sha256)
            }.getOrElse { BatchChunk(index, fileName, text, error = it.message) }
        }
        return BatchManifest(batchId, texts.size, resumed, metadata).also(::persistManifest)
    }

    /** Durably invalidates a chunk before native generation begins. */
    fun markPending(manifest: BatchManifest, index: Int, text: String): BatchManifest {
        require(index in 0 until manifest.expectedChunkCount) { "Chunk index is outside the batch" }
        val existing = manifest.chunks.firstOrNull { it.index == index }
        return manifest.withChunk(
            BatchChunk(
                index = index,
                fileName = existing?.fileName ?: chunkFileName(index),
                text = text,
                status = BatchChunkStatus.PENDING
            )
        ).also(::persistManifest)
    }

    fun writeChunk(manifest: BatchManifest, index: Int, audio: GeneratedAudio, text: String = ""): BatchManifest {
        val chunk = writeChunkFile(manifest, index, audio, text)
        return manifest.withChunk(chunk).also(::persistManifest)
    }

    /** Encodes and atomically writes one chunk without changing the manifest. */
    open fun writeChunkFile(manifest: BatchManifest, index: Int, audio: GeneratedAudio, text: String = ""): BatchChunk {
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

    fun markFailed(manifest: BatchManifest, index: Int, error: String, text: String? = null): BatchManifest =
        manifest.withChunk(BatchChunk(index, manifest.chunks.firstOrNull { it.index == index }?.fileName ?: chunkFileName(index),
            text ?: manifest.chunks.firstOrNull { it.index == index }?.text ?: "",
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

    private fun existingManifest(): BatchManifest? =
        if (Files.isRegularFile(manifestPath)) loadManifest() else null

    private fun requireCompatibleProvenance(
        existing: BatchManifest?,
        expectedChunkCount: Int,
        metadata: Map<String, String>
    ) {
        if (existing == null) return
        require(existing.expectedChunkCount == expectedChunkCount) {
            "Existing manifest has ${existing.expectedChunkCount} chunks; refusing incompatible input"
        }
        val compatible = metadata.filterKeys { it != "batchMaxCharacters" }.all { (key, value) ->
            existing.metadata[key] == value
        }
        require(compatible) { "Existing manifest metadata is incompatible; refusing to overwrite provenance" }
    }

    private fun validateUnownedSidecars(texts: List<String>) {
        val sidecars = Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().matches(Regex("chunk-\\d{6}\\.txt")) }.toList()
        }
        sidecars.forEach { sidecar ->
            val index = sidecar.fileName.toString().removePrefix("chunk-").removeSuffix(".txt").toInt()
            require(index in texts.indices && Files.readString(sidecar, StandardCharsets.UTF_8) == texts[index]) {
                "Existing sidecar has no compatible provenance; refusing to overwrite ${sidecar.fileName}"
            }
        }
    }

    private fun validateExistingProvenance(existing: BatchManifest) {
        existing.chunks.forEach { previous ->
            val sidecar = safeChild(textFileName(previous.index))
            if (Files.isRegularFile(sidecar)) {
                require(Files.readString(sidecar, StandardCharsets.UTF_8) == previous.text) {
                    "Existing sidecar does not match manifest chunk ${previous.index}"
                }
            }
            if (previous.status == BatchChunkStatus.COMPLETE) {
                val wav = safeChild(previous.fileName)
                require(Files.isRegularFile(wav)) { "Completed chunk ${previous.index} WAV is missing" }
                val bytes = Files.readAllBytes(wav)
                val parsed = parseWav(bytes)
                require(previous.sha256 != null && sha256(bytes) == previous.sha256) {
                    "Completed chunk ${previous.index} WAV checksum is invalid"
                }
                require(parsed.sampleRate == previous.sampleRate && parsed.frameCount == previous.frameCount) {
                    "Completed chunk ${previous.index} WAV metadata is invalid"
                }
            }
        }
    }

    private fun provenComplete(
        previous: BatchChunk?,
        text: String,
        wavFile: Path,
        index: Int
    ): ParsedWav? {
        if (previous?.status != BatchChunkStatus.COMPLETE || previous.text != text ||
            previous.sha256 == null || !Files.isRegularFile(wavFile)) return null
        val sidecar = safeChild(textFileName(index))
        if (!Files.isRegularFile(sidecar) || Files.readString(sidecar, StandardCharsets.UTF_8) != text) return null
        return runCatching {
            val bytes = Files.readAllBytes(wavFile)
            val parsed = parseWav(bytes)
            require(sha256(bytes) == previous.sha256)
            require(parsed.sampleRate == previous.sampleRate && parsed.frameCount == previous.frameCount)
            parsed
        }.getOrNull()
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
