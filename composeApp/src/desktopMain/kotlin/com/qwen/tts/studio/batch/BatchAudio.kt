package com.qwen.tts.studio.batch

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
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
    val error: String? = null,
    val voiceName: String? = null,
    val modelName: String? = null,
    val voicePrompt: String? = null,
    val validationPassed: Boolean? = null,
    val validationMessage: String? = null,
    val validationSignature: String? = null,
    /** Durable logical label shown to users; [index] remains the internal ordinal. */
    val displayIndex: String = index.toString()
)

data class BatchManifest(
    val batchId: String,
    val expectedChunkCount: Int,
    val chunks: List<BatchChunk>,
    val metadata: Map<String, String> = emptyMap(),
    /** Monotonic control-plane revision; legacy manifests decode as zero. */
    val revision: Long = 0L
) {
    init {
        require(batchId.isNotBlank()) { "batchId must not be blank" }
        require(expectedChunkCount >= 0) { "expectedChunkCount must not be negative" }
        require(revision >= 0L) { "revision must not be negative" }
        require(chunks.map(BatchChunk::index).distinct().size == chunks.size) { "Duplicate chunk index" }
        require(chunks.all { it.index in 0 until expectedChunkCount }) { "Chunk index is outside the batch" }
        require(chunks.all { it.displayIndex.isNotBlank() }) { "Chunk display index must not be blank" }
        require(chunks.map(BatchChunk::displayIndex).distinct().size == chunks.size) { "Duplicate chunk display index" }
    }

    fun withChunk(chunk: BatchChunk): BatchManifest =
        copy(chunks = (chunks.filterNot { it.index == chunk.index } + chunk).sortedBy { it.index })
}

data class BatchRechunkResult(
    val manifest: BatchManifest,
    val failedSourceIndexes: List<Int>,
    val sourceToNewIndexes: Map<Int, List<Int>>,
    val backupManifest: Path
)

/** A manifest write lost its compare-and-swap race with another writer. */
class BatchManifestConflictException(message: String) : IllegalStateException(message)

/** Desktop persistence and strict recombination for generated mono PCM audio. */
open class BatchAudioStore(directory: Path) {
    private val directory = directory.toAbsolutePath().normalize()
    val directoryPath: Path get() = directory
    private val manifestPath = directory.resolve("manifest.json")
    private val manifestLockPath = directory.resolve(MANIFEST_LOCK_FILE)
    private val processManifestLock = processManifestLocks.computeIfAbsent(manifestLockPath) { ReentrantLock() }

    fun loadManifest(path: Path = manifestPath): BatchManifest {
        val normalized = path.toAbsolutePath().normalize()
        return loadManifestUnlocked(normalized)
    }

    private fun loadManifestUnlocked(path: Path = manifestPath): BatchManifest {
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
            val previous = existing?.chunks?.firstOrNull { it.index == index }
            val base = BatchChunk(index, chunkFileName(index), text,
                voiceName = previous?.voiceName,
                modelName = previous?.modelName,
                voicePrompt = previous?.voicePrompt,
                displayIndex = previous?.displayIndex ?: index.toString())
            provenComplete(existing?.chunks?.firstOrNull { it.index == index }, text, wavFile, index)
                ?.let { parsed ->
                    base.copy(status = BatchChunkStatus.COMPLETE, sampleRate = parsed.sampleRate,
                        frameCount = parsed.frameCount, sha256 = existing?.chunks?.first { it.index == index }?.sha256)
                }
                ?: base
        }
        return persistManifest(withManifestRevision(BatchManifest(batchId, chunks.size, chunks, metadata), existing))
    }

    /** Creates chunk sidecars and a manifest from the selected source chunks. */
    fun generateManifestFromTexts(
        batchId: String,
        texts: List<String>,
        metadata: Map<String, String> = emptyMap(),
        defaultVoiceName: String? = null,
        defaultModelName: String? = null,
        defaultVoicePrompt: String? = null
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
            val previous = existing?.chunks?.firstOrNull { it.index == index }
            val base = BatchChunk(index, fileName, text,
                voiceName = previous?.voiceName ?: defaultVoiceName,
                modelName = previous?.modelName ?: defaultModelName,
                voicePrompt = previous?.voicePrompt ?: defaultVoicePrompt,
                displayIndex = previous?.displayIndex ?: index.toString())
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
        return persistManifest(withManifestRevision(BatchManifest(batchId, chunks.size, chunks, metadata), existing))
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
        persistManifest(withManifestRevision(BatchManifest(batchId, expectedChunkCount, emptyList(), metadata), existingManifest()))

    /** Reopens a compatible batch and validates existing chunk files for resume. */
    fun createOrResumeManifest(
        batchId: String,
        texts: List<String>,
        metadata: Map<String, String> = emptyMap(),
        allowUnreadableReplacement: Boolean = false
    ): BatchManifest {
        require(texts.isNotEmpty()) { "texts must not be empty" }
        // The output directory is the durable batch identity. The UI may create a
        // fresh transient request ID after a restart, so requiring batchId here
        // would make every resume look like a new batch. Stable metadata and the
        // persisted source text still prevent adopting unrelated output.
        val existingRead = if (Files.isRegularFile(manifestPath)) {
            runCatching { loadManifest() }
        } else {
            Result.success(null)
        }
        val existing = existingRead.getOrNull()
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
            ).let {
                persistManifest(
                    withManifestRevision(it, existing),
                    allowUnreadableReplacement = allowUnreadableReplacement && existingRead.isFailure
                )
            }
        }
        val resumed = texts.mapIndexed { index, text ->
            val fileName = chunkFileName(index)
            val file = safeChild(fileName)
            val previous = existing?.chunks?.firstOrNull { it.index == index }
            // A stale WAV is not evidence of a completed chunk. Only a chunk that
            // was durably marked COMPLETE for the same source text may be resumed.
            if (previous?.status != BatchChunkStatus.COMPLETE || previous.text != text || !Files.isRegularFile(file)) {
                return@mapIndexed BatchChunk(index, fileName, text,
                    voiceName = previous?.voiceName,
                    modelName = previous?.modelName,
                    voicePrompt = previous?.voicePrompt,
                    displayIndex = previous?.displayIndex ?: index.toString())
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
                        error = "Existing WAV checksum mismatch; regeneration required",
                        voiceName = previous.voiceName,
                        modelName = previous.modelName,
                        voicePrompt = previous.voicePrompt,
                        displayIndex = previous.displayIndex
                    )
                }
                val textFile = safeChild(textFileName(index))
                if (!Files.isRegularFile(textFile) || Files.readString(textFile, StandardCharsets.UTF_8) != text) {
                    writeAtomic(textFile, text.toByteArray(StandardCharsets.UTF_8))
                }
                BatchChunk(index, fileName, text, BatchChunkStatus.COMPLETE, parsed.sampleRate,
                    parsed.frameCount, previous.sha256,
                    voiceName = previous.voiceName,
                    modelName = previous.modelName,
                    voicePrompt = previous.voicePrompt,
                    validationPassed = previous.validationPassed,
                    validationMessage = previous.validationMessage,
                    validationSignature = previous.validationSignature,
                    displayIndex = previous.displayIndex)
            }.getOrElse { BatchChunk(index, fileName, text,
                error = it.message,
                voiceName = previous.voiceName,
                modelName = previous.modelName,
                voicePrompt = previous.voicePrompt,
                displayIndex = previous.displayIndex) }
        }
        val resumedMetadata = if (compatible && existing != null) {
            // Preserve additive per-chunk generation evidence (for example the
            // exact aggregate audio-cap inputs) when a compatible request
            // reopens the durable batch. Request metadata remains authoritative
            // for identity and current provenance, while old evidence must not
            // disappear merely because it is not part of the new request.
            existing.metadata + metadata
        } else {
            metadata
        }
        return persistManifest(withManifestRevision(BatchManifest(batchId, texts.size, resumed, resumedMetadata), existing))
    }

    /**
     * Splits every failed or validation-failed source chunk in-place.
     *
     * The integer index remains a contiguous storage/generation ordinal so the
     * existing engine and file naming contracts remain stable. The decimal
     * [BatchChunk.displayIndex] is the durable logical identity: a failed
     * source chunk labelled `15` becomes `15.1`, `15.2`, while unaffected
     * source chunks retain their labels. Complete audio is staged and copied
     * to its new ordinal, and the manifest is committed last so a failed
     * migration rolls back both files and manifest state.
     */
    @Synchronized
    fun rechunkFailedChunks(manifest: BatchManifest, maxCharacters: Int): BatchRechunkResult {
        require(maxCharacters > 0) { "maxCharacters must be positive" }
        val current = loadManifest()
        require(current == manifest) { "The supplied manifest is stale; reload it before rechunking." }
        val ordered = manifest.chunks.sortedBy { it.index }
        require(ordered.size == manifest.expectedChunkCount) { "Manifest is incomplete; cannot rechunk it." }
        require(ordered.map { it.index } == (0 until manifest.expectedChunkCount).toList()) {
            "Manifest indexes are incomplete; cannot rechunk it."
        }

        val failedSources = ordered.filter {
            it.status != BatchChunkStatus.COMPLETE || it.validationPassed == false
        }
        require(failedSources.isNotEmpty()) { "No failed or validation-failed chunks require rechunking." }
        ordered.filter { it !in failedSources }.forEach(::validateChunkFile)

        data class PlannedChunk(
            val sources: List<BatchChunk>,
            val text: String,
            val displayIndex: String,
            val isComplete: Boolean,
            val index: Int
        ) {
            val source: BatchChunk get() = sources.first()
        }

        val initialPlanned = mutableListOf<PlannedChunk>()
        ordered.forEach { source ->
            val failed = source in failedSources
            // A short failed chunk still needs a retry, even when it cannot be
            // split further. Keep it as one pending child rather than rejecting
            // the entire recovery plan because another source chunk is longer.
            val pieces = if (!failed || source.text.length <= maxCharacters) {
                listOf(source.text)
            } else {
                TextBatching.packParagraphsForGeneration(source.text, maxCharacters)
            }
            pieces.forEachIndexed { part, text ->
                initialPlanned += PlannedChunk(
                    sources = listOf(source),
                    text = text,
                    displayIndex = if (failed) "${source.displayIndex}.${part + 1}" else source.displayIndex,
                    isComplete = !failed,
                    index = 0
                )
            }
        }

        // Recovery can expose a short failed tail that was already split away
        // from its speech context by an earlier pass. Reattach that tail to a
        // neighboring plan when the combined text remains under the recovery
        // limit, and regenerate the combined unit. Keeping the neighbor in the
        // pending unit is intentional: its old WAV no longer corresponds to
        // the new text and must not be preserved as if it were still valid.
        val minimumRecoveryCharacters = (maxCharacters / 2).coerceAtLeast(2)
        val mergedPlanned = mutableListOf<PlannedChunk>()
        var cursor = 0
        while (cursor < initialPlanned.size) {
            val current = initialPlanned[cursor]
            val isShortRetry = !current.isComplete && current.text.length < minimumRecoveryCharacters
            if (isShortRetry && mergedPlanned.isNotEmpty()) {
                val previous = mergedPlanned.last()
                if (previous.text.length + current.text.length <= maxCharacters) {
                    mergedPlanned[mergedPlanned.lastIndex] = previous.copy(
                        sources = previous.sources + current.sources,
                        text = previous.text + current.text,
                        displayIndex = "${previous.displayIndex}.1",
                        isComplete = false
                    )
                    cursor++
                    continue
                }
            }
            if (isShortRetry && cursor + 1 < initialPlanned.size) {
                val next = initialPlanned[cursor + 1]
                if (current.text.length + next.text.length <= maxCharacters) {
                    mergedPlanned += current.copy(
                        sources = current.sources + next.sources,
                        text = current.text + next.text,
                        displayIndex = "${current.displayIndex}.1",
                        isComplete = false
                    )
                    cursor += 2
                    continue
                }
            }
            mergedPlanned += current
            cursor++
        }
        val planned = mergedPlanned.mapIndexed { index, item -> item.copy(index = index) }
        val sourceToNewIndexes = linkedMapOf<Int, MutableList<Int>>()
        planned.forEach { item ->
            item.sources.forEach { source ->
                sourceToNewIndexes.getOrPut(source.index) { mutableListOf() } += item.index
            }
        }
        require(planned.isNotEmpty()) { "Rechunking did not produce any chunks." }

        val newTexts = planned.map { it.text }
        val newMetadata = manifest.metadata.toMutableMap().apply {
            keys.filter { it.startsWith("audioCapTokens.") }.toList().forEach(::remove)
            ordered.filter { it.status == BatchChunkStatus.COMPLETE && it.validationPassed != false }
                .forEach { source ->
                    val oldCap = manifest.metadata["audioCapTokens.${source.index}"]
                    val newIndex = sourceToNewIndexes[source.index]?.singleOrNull()
                    if (oldCap != null && newIndex != null && planned[newIndex].isComplete) {
                        this["audioCapTokens.$newIndex"] = oldCap
                    }
                }
            this["textFingerprint"] = BatchIdentity.textFingerprint(newTexts)
            this["rechunkVersion"] = "1"
            this["rechunkSourceBatchId"] = manifest.batchId
            this["rechunkFailedSourceIndexes"] = failedSources.joinToString(",") { it.index.toString() }
            this["rechunkFailedSourceDisplayIndexes"] = failedSources.joinToString(",") { it.displayIndex }
            this["rechunkMaxCharacters"] = maxCharacters.toString()
        }
        val newChunks = planned.map { item ->
            if (item.isComplete) {
                item.source.copy(
                    index = item.index,
                    fileName = chunkFileName(item.index),
                    text = item.text,
                    displayIndex = item.displayIndex
                )
            } else {
                item.source.copy(
                    index = item.index,
                    fileName = chunkFileName(item.index),
                    text = item.text,
                    status = BatchChunkStatus.PENDING,
                    sampleRate = null,
                    frameCount = null,
                    sha256 = null,
                    error = "Rechunked from failed chunk(s) " +
                        item.sources.filter { it in failedSources }
                            .joinToString(",") { it.displayIndex }
                            .ifBlank { item.source.displayIndex } +
                        "; pending generation.",
                    validationPassed = null,
                    validationMessage = null,
                    validationSignature = null,
                    displayIndex = item.displayIndex
                )
            }
        }

        val artifactPattern = Regex("chunk-\\d{6}\\.(wav|txt|alignment)")
        val originalArtifacts = Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().matches(artifactPattern) }.toList()
        }
        val staging = Files.createTempDirectory(directory, ".rechunk-staging-${UUID.randomUUID()}")
        val staged = linkedMapOf<String, Path>()
        var backup: Path? = null
        var committed = false
        var artifactsMutated = false
        try {
            originalArtifacts.forEach { source ->
                val target = staging.resolve(source.fileName.toString())
                Files.copy(source, target, REPLACE_EXISTING)
                staged[source.fileName.toString()] = target
            }

            return withManifestLock {
                // Staging is deliberately outside the lock, but the artifact
                // swap and manifest CAS are one short commit transaction. A
                // queued validator or a second store can therefore either win
                // before this point (causing a clean conflict) or observe the
                // new manifest after the whole swap.
                val commitCurrent = loadManifestUnlocked()
                if (commitCurrent != manifest) {
                    throw BatchManifestConflictException(
                        "The supplied manifest changed while rechunk artifacts were being staged; reload before retrying."
                    )
                }
                backup = directory.resolve(
                    "manifest.before-rechunk-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.json"
                )
                writeAtomic(backup!!, manifestJson(commitCurrent).toByteArray(StandardCharsets.UTF_8))
                newMetadata["rechunkBackupManifest"] = backup!!.fileName.toString()

                artifactsMutated = true
                originalArtifacts.forEach { Files.deleteIfExists(it) }
                planned.forEach { item ->
                    writeAtomic(
                        safeChild(textFileName(item.index)),
                        item.text.toByteArray(StandardCharsets.UTF_8)
                    )
                    if (item.isComplete) {
                        val wav = staged[item.source.fileName]
                            ?: error("Complete chunk ${item.source.displayIndex} WAV is missing from staging.")
                        copyAtomic(wav, safeChild(chunkFileName(item.index)))
                        staged[alignmentFileName(item.source.index)]?.let { alignment ->
                            copyAtomic(alignment, alignmentPath(directory, item.index))
                        }
                    }
                }
                val finalManifest = BatchManifest(
                    manifest.batchId,
                    planned.size,
                    newChunks,
                    newMetadata.toMap()
                )
                val persistedFinal = persistManifestUnlocked(finalManifest, revisionOf(commitCurrent))
                committed = true
                BatchRechunkResult(
                    manifest = persistedFinal,
                    failedSourceIndexes = failedSources.map { it.index },
                    sourceToNewIndexes = sourceToNewIndexes.mapValues { it.value.toList() },
                    backupManifest = backup!!
                )
            }
        } catch (error: Throwable) {
            if (!committed && artifactsMutated) {
                runCatching {
                    Files.list(directory).use { stream ->
                        stream.filter { it.fileName.toString().matches(artifactPattern) }
                            .forEach { Files.deleteIfExists(it) }
                    }
                    staged.forEach { (name, source) ->
                        Files.copy(source, directory.resolve(name), REPLACE_EXISTING)
                    }
                    backup?.let { Files.deleteIfExists(it) }
                }.onFailure { restoreError -> error.addSuppressed(restoreError) }
            }
            throw error
        } finally {
            Files.walk(staging).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /** Durably invalidates a chunk before native generation begins. */
    @Synchronized
    fun markPending(manifest: BatchManifest, index: Int, text: String): BatchManifest = withManifestLock {
        require(index in 0 until manifest.expectedChunkCount) { "Chunk index is outside the batch" }
        val current = loadManifestUnlocked()
        requireCompatibleSnapshot(current, manifest, index, text)
        val existing = current.chunks.firstOrNull { it.index == index }
        val pending = BatchChunk(
            index = index,
            fileName = existing?.fileName ?: chunkFileName(index),
            text = text,
            status = BatchChunkStatus.PENDING,
            voiceName = existing?.voiceName,
            modelName = existing?.modelName,
            voicePrompt = existing?.voicePrompt,
            displayIndex = existing?.displayIndex ?: index.toString()
        )
        deleteAlignment(index)
        persistManifestUnlocked(current.withChunk(pending), revisionOf(current))
    }

    fun writeChunk(manifest: BatchManifest, index: Int, audio: GeneratedAudio, text: String = ""): BatchManifest {
        val chunk = writeChunkFile(manifest, index, audio, text)
        return persistCompletedChunk(chunk)
    }

    fun writeAlignment(index: Int, alignment: BatchChunkAlignment) {
        require(index >= 0) { "Chunk index must not be negative" }
        writeAtomic(alignmentPath(directory, index), BatchAlignmentCodec.encode(alignment).toByteArray(StandardCharsets.UTF_8))
    }

    fun deleteAlignment(index: Int) {
        Files.deleteIfExists(alignmentPath(directory, index))
    }

    /** Encodes and atomically writes one chunk without changing the manifest. */
    open fun writeChunkFile(manifest: BatchManifest, index: Int, audio: GeneratedAudio, text: String = ""): BatchChunk = withManifestLock {
        require(index in 0 until manifest.expectedChunkCount) { "Chunk index is outside the batch" }
        val current = if (Files.isRegularFile(manifestPath)) loadManifestUnlocked() else manifest
        require(current.batchId == manifest.batchId && current.expectedChunkCount == manifest.expectedChunkCount) {
            "The batch changed while chunk $index was being generated; refusing to write stale audio."
        }
        val currentChunk = current.chunks.firstOrNull { it.index == index }
        val expectedChunk = manifest.chunks.firstOrNull { it.index == index }
        if (currentChunk != null && expectedChunk != null && !sameGenerationIdentity(currentChunk, expectedChunk)) {
            throw BatchManifestConflictException(
                "Chunk $index changed while audio was being generated; refusing to replace its WAV."
            )
        }
        val fileName = currentChunk?.fileName ?: expectedChunk?.fileName ?: chunkFileName(index)
        val effectiveText = if (text.isEmpty() && currentChunk != null && currentChunk.text.isNotEmpty()) {
            currentChunk.text
        } else {
            text
        }
        val target = safeChild(fileName)
        val pcm = encodePcm16(audio.samples)
        val wav = wavBytes(audio.sampleRate, pcm)
        writeAtomic(safeChild(textFileName(index)), effectiveText.toByteArray(StandardCharsets.UTF_8))
        writeAtomic(target, wav)
        BatchChunk(index, fileName, effectiveText, BatchChunkStatus.COMPLETE, audio.sampleRate,
            audio.samples.size.toLong(), sha256(wav),
            voiceName = currentChunk?.voiceName ?: expectedChunk?.voiceName,
            modelName = currentChunk?.modelName ?: expectedChunk?.modelName,
            voicePrompt = currentChunk?.voicePrompt ?: expectedChunk?.voicePrompt,
            displayIndex = currentChunk?.displayIndex ?: expectedChunk?.displayIndex ?: index.toString())
    }

    fun markFailed(manifest: BatchManifest, index: Int, error: String, text: String? = null): BatchManifest = withManifestLock {
        val current = loadManifestUnlocked()
        requireCompatibleSnapshot(current, manifest, index)
        requireRevisionMatch(current, manifest, index)
        val existing = current.chunks.firstOrNull { it.index == index }
        val failed = current.withChunk(BatchChunk(
            index = index,
            fileName = existing?.fileName ?: chunkFileName(index),
            text = text ?: existing?.text.orEmpty(),
            status = BatchChunkStatus.FAILED,
            error = error,
            voiceName = existing?.voiceName,
            modelName = existing?.modelName,
            voicePrompt = existing?.voicePrompt,
            displayIndex = existing?.displayIndex ?: index.toString()
        ))
        deleteAlignment(index)
        persistManifestUnlocked(failed, revisionOf(current))
    }

    fun markCancelled(manifest: BatchManifest, index: Int): BatchManifest = withManifestLock {
        val current = loadManifestUnlocked()
        requireCompatibleSnapshot(current, manifest, index)
        requireRevisionMatch(current, manifest, index)
        val existing = current.chunks.firstOrNull { it.index == index }
        if (existing?.status == BatchChunkStatus.COMPLETE) return@withManifestLock current
        val cancelled = current.withChunk(BatchChunk(
            index = index,
            fileName = existing?.fileName ?: chunkFileName(index),
            text = existing?.text.orEmpty(),
            status = BatchChunkStatus.CANCELLED,
            error = "Cancelled",
            voiceName = existing?.voiceName,
            modelName = existing?.modelName,
            voicePrompt = existing?.voicePrompt,
            displayIndex = existing?.displayIndex ?: index.toString()
        ))
        deleteAlignment(index)
        persistManifestUnlocked(cancelled, revisionOf(current))
    }

    @Synchronized
    fun persistManifest(
        manifest: BatchManifest,
        allowUnreadableReplacement: Boolean = false
    ): BatchManifest = withManifestLock {
        persistManifestUnlocked(manifest, revisionOf(manifest), allowUnreadableReplacement)
    }

    /** Applies one manifest update to the newest durable snapshot. */
    @Synchronized
    fun updateManifest(transform: (BatchManifest) -> BatchManifest): BatchManifest = withManifestLock {
        val current = loadManifestUnlocked()
        persistManifestUnlocked(transform(current), revisionOf(current))
    }

    /**
     * Commits a completed WAV into the newest manifest rather than a snapshot
     * captured when generation started. The writer runs concurrently with GPU
     * generation, so replacing the whole manifest from an old snapshot could
     * erase newer PENDING or COMPLETE entries.
     */
    @Synchronized
    fun persistCompletedChunk(chunk: BatchChunk): BatchManifest = withManifestLock {
        val current = loadManifestUnlocked()
        val existing = current.chunks.firstOrNull { it.index == chunk.index }
        if (existing != null) {
            if (!sameGenerationIdentity(existing, chunk)) {
                throw BatchManifestConflictException(
                    "Chunk ${chunk.index} changed before its generated WAV completed; refusing stale completion."
                )
            }
            if (existing.status == BatchChunkStatus.CANCELLED || existing.status == BatchChunkStatus.FAILED) {
                throw BatchManifestConflictException(
                    "Chunk ${chunk.index} is ${existing.status}; refusing a completion from an older generation attempt."
                )
            }
        }
        persistManifestUnlocked(current.withChunk(chunk), revisionOf(current))
    }

    /** Captures the exact durable artifact that a validator is allowed to publish. */
    @Synchronized
    fun captureBatchChunkValidationLease(index: Int): BatchChunkValidationLease = withManifestLock {
        val current = loadManifestUnlocked()
        val chunk = current.chunks.firstOrNull { it.index == index }
            ?: error("Chunk $index is not present in the manifest.")
        BatchChunkValidationLease.fromChunk(chunk)
    }

    /** Persists validation for one durable chunk without rewriting other rows. */
    @Synchronized
    fun persistChunkValidation(
        index: Int,
        passed: Boolean,
        message: String,
        expectedLease: BatchChunkValidationLease? = null
    ): BatchManifest = withManifestLock {
        val current = loadManifestUnlocked()
        val chunk = current.chunks.firstOrNull { it.index == index }
            ?: error("Chunk $index is not present in the manifest.")
        expectedLease?.let { lease ->
            if (!lease.matches(chunk)) {
                throw BatchManifestConflictException(
                    "Queued validation for chunk $index is stale because its manifest row or WAV changed."
                )
            }
        }
        val complete = chunk.status == BatchChunkStatus.COMPLETE
        val validated = chunk.copy(
            validationPassed = complete && passed,
            validationMessage = if (complete) message else BatchValidator.incompleteChunkMessage(chunk),
            validationSignature = BatchValidationSignature.forChunk(chunk)
        )
        persistManifestUnlocked(current.withChunk(validated), revisionOf(current))
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
        // RIFF/WAV stores these sizes as unsigned 32-bit fields.  Java's
        // ByteBuffer exposes them as signed Ints, but a valid PCM payload may
        // be larger than Int.MAX_VALUE while still fitting below the RIFF
        // four-gibibyte ceiling.  Keep the accounting wide until the header
        // is encoded so long batches can be recombined on Windows.
        require(totalPcmBytes <= RIFF_UINT32_MAX - 36L) {
            "Combined WAV exceeds the RIFF/WAV 4 GiB limit"
        }
        val destination = output.toAbsolutePath().normalize()
        require(ordered.none { safeChild(it.fileName).toAbsolutePath().normalize() == destination }) {
            "Combined output must not overwrite a batch chunk"
        }
        Files.createDirectories(destination.parent)
        writeCombinedAtomic(destination, ordered, sampleRate!!, totalPcmBytes)
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

    private fun withManifestRevision(manifest: BatchManifest, existing: BatchManifest?): BatchManifest =
        manifest.copy(
            metadata = manifest.metadata - MANIFEST_REVISION_KEY,
            revision = revisionOf(existing)
        )

    private fun revisionOf(manifest: BatchManifest?): Long =
        manifest?.revision?.takeIf { it > 0L }
            ?: manifest?.metadata?.get(MANIFEST_REVISION_KEY)?.toLongOrNull()
            ?: 0L

    private fun persistManifestUnlocked(
        manifest: BatchManifest,
        expectedRevision: Long,
        allowUnreadableReplacement: Boolean = false
    ): BatchManifest {
        Files.createDirectories(directory)
        val existing = if (Files.isRegularFile(manifestPath)) {
            try {
                loadManifestUnlocked()
            } catch (error: Throwable) {
                if (allowUnreadableReplacement && expectedRevision == 0L) {
                    null
                } else {
                    throw error
                }
            }
        } else null
        val actualRevision = revisionOf(existing)
        if (existing != null && expectedRevision != actualRevision) {
            throw BatchManifestConflictException(
                "Manifest revision conflict in $directory: expected $expectedRevision, found $actualRevision."
            )
        }
        if (existing == null && expectedRevision != 0L) {
            throw BatchManifestConflictException(
                "Manifest was replaced while writing $directory: expected revision $expectedRevision, but no manifest exists."
            )
        }
        val incoming = manifest.copy(
            metadata = manifest.metadata - MANIFEST_REVISION_KEY,
            revision = actualRevision
        )
        val currentNormalized = existing?.copy(
            metadata = existing.metadata - MANIFEST_REVISION_KEY,
            revision = actualRevision
        )
        if (currentNormalized != null && incoming == currentNormalized) {
            return currentNormalized
        }
        val persisted = manifest.copy(
            metadata = manifest.metadata - MANIFEST_REVISION_KEY,
            revision = actualRevision + 1L
        )
        writeAtomic(manifestPath, manifestJson(persisted).toByteArray(StandardCharsets.UTF_8))
        return persisted
    }

    private fun <T> withManifestLock(block: () -> T): T {
        Files.createDirectories(directory)
        val nested = processManifestLock.isHeldByCurrentThread
        processManifestLock.lock()
        try {
            if (nested) return block()
            FileChannel.open(
                manifestLockPath,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE
            ).use { channel ->
                val fileLock = channel.lock()
                try {
                    return block()
                } finally {
                    fileLock.release()
                }
            }
        } finally {
            processManifestLock.unlock()
        }
    }

    private fun requireCompatibleSnapshot(
        current: BatchManifest,
        supplied: BatchManifest,
        index: Int,
        text: String? = null
    ) {
        if (current.batchId != supplied.batchId || current.expectedChunkCount != supplied.expectedChunkCount) {
            throw BatchManifestConflictException(
                "Batch plan changed while chunk $index was being updated; reload before retrying."
            )
        }
        val currentChunk = current.chunks.firstOrNull { it.index == index }
        val suppliedChunk = supplied.chunks.firstOrNull { it.index == index }
        if (text != null && currentChunk != null && currentChunk.text != text) {
            throw BatchManifestConflictException(
                "Chunk $index text changed while its update was pending; refusing a stale write."
            )
        }
        if (currentChunk != null && suppliedChunk != null && !sameGenerationIdentity(currentChunk, suppliedChunk)) {
            throw BatchManifestConflictException(
                "Chunk $index generation inputs changed while its update was pending; refusing a stale write."
            )
        }
    }

    private fun requireRevisionMatch(current: BatchManifest, supplied: BatchManifest, index: Int) {
        val expected = revisionOf(supplied)
        val actual = revisionOf(current)
        if (expected != actual) {
            throw BatchManifestConflictException(
                "Manifest revision changed before chunk $index was updated; expected $expected, found $actual."
            )
        }
    }

    private fun sameGenerationIdentity(left: BatchChunk, right: BatchChunk): Boolean =
        left.index == right.index &&
            left.fileName == right.fileName &&
            left.text == right.text &&
            left.displayIndex == right.displayIndex &&
            left.voiceName == right.voiceName &&
            left.modelName == right.modelName &&
            left.voicePrompt == right.voicePrompt

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
            moveAtomicWithRetry(temp, target)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun copyAtomic(source: Path, target: Path) {
        val temp = target.resolveSibling(".${target.fileName}.tmp-${UUID.randomUUID()}")
        try {
            Files.copy(source, temp, REPLACE_EXISTING)
            moveAtomicWithRetry(temp, target)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * Windows security scanners and concurrent readers can briefly hold the
     * destination during an otherwise valid manifest commit. Retry only that
     * transient access-denied condition; preserve fail-closed behavior for all
     * other filesystem errors.
     */
    private fun moveAtomicWithRetry(temp: Path, target: Path) {
        var lastDenied: AccessDeniedException? = null
        repeat(ATOMIC_MOVE_RETRY_COUNT) { attempt ->
            try {
                Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
                return
            } catch (e: AtomicMoveNotSupportedException) {
                throw IOException("Atomic rename is not supported for ${target.fileSystem}", e)
            } catch (e: AccessDeniedException) {
                lastDenied = e
                if (attempt + 1 < ATOMIC_MOVE_RETRY_COUNT) {
                    Thread.sleep(ATOMIC_MOVE_RETRY_DELAY_MILLIS)
                }
            }
        }
        throw lastDenied ?: IOException("Atomic rename failed for $target")
    }

    private fun writeCombinedAtomic(target: Path, chunks: List<BatchChunk>, sampleRate: Int, pcmBytes: Long) {
        val temp = target.resolveSibling(".${target.fileName}.tmp-${UUID.randomUUID()}")
        try {
            Files.newOutputStream(temp).use { output ->
                output.write(wavHeader(sampleRate, pcmBytes))
                chunks.forEach { chunk -> output.write(parseWav(Files.readAllBytes(safeChild(chunk.fileName))).pcm) }
            }
            moveAtomicWithRetry(temp, target)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private companion object {
        const val MANIFEST_LOCK_FILE = ".manifest.lock"
        const val MANIFEST_REVISION_KEY = "manifestRevision"
        const val RIFF_UINT32_MAX = 0xffff_ffffL
        const val ATOMIC_MOVE_RETRY_COUNT = 8
        const val ATOMIC_MOVE_RETRY_DELAY_MILLIS = 75L
        val processManifestLocks = ConcurrentHashMap<Path, ReentrantLock>()
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
        append("{\"batchId\":\"").append(jsonEscape(manifest.batchId)).append("\",\"expectedChunkCount\":").append(manifest.expectedChunkCount).append(",\"manifestRevision\":").append(manifest.revision).append(",\"metadata\":{")
        manifest.metadata.entries.sortedBy { it.key }.forEachIndexed { i, (key, value) ->
            if (i > 0) append(',')
            append('"').append(jsonEscape(key)).append("\":\"").append(jsonEscape(value)).append('"')
        }
        append("},\"chunks\":[")
        manifest.chunks.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append("{\"index\":").append(c.index).append(",\"displayIndex\":\"").append(jsonEscape(c.displayIndex)).append("\",\"fileName\":\"").append(jsonEscape(c.fileName)).append("\",\"text\":\"").append(jsonEscape(c.text)).append("\",\"status\":\"").append(c.status).append('\"')
             c.sampleRate?.let { append(",\"sampleRate\":").append(it) }; c.frameCount?.let { append(",\"frameCount\":").append(it) }; c.sha256?.let { append(",\"sha256\":\"").append(it).append('\"') }; c.error?.let { append(",\"error\":\"").append(jsonEscape(it)).append('\"') }; c.voiceName?.let { append(",\"voiceName\":\"").append(jsonEscape(it)).append('\"') }; c.modelName?.let { append(",\"modelName\":\"").append(jsonEscape(it)).append('\"') }; c.voicePrompt?.let { append(",\"voicePrompt\":\"").append(jsonEscape(it)).append('\"') }; c.validationPassed?.let { append(",\"validationPassed\":").append(it) }; c.validationMessage?.let { append(",\"validationMessage\":\"").append(jsonEscape(it)).append('\"') }; c.validationSignature?.let { append(",\"validationSignature\":\"").append(jsonEscape(it)).append('\"') }
            append('}')
        }
        append("]}\n")
    }

    private fun jsonEscape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    private fun chunkFileName(index: Int) = "chunk-%06d.wav".format(index)
    private fun textFileName(index: Int) = "chunk-%06d.txt".format(index)
    private fun alignmentFileName(index: Int) = "chunk-%06d.alignment".format(index)
    private fun ascii(bytes: ByteArray, offset: Int, value: String) = String(bytes, offset, 4, StandardCharsets.US_ASCII) == value
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, o: Int) = (b[o].toLong() and 255) or ((b[o + 1].toLong() and 255) shl 8) or ((b[o + 2].toLong() and 255) shl 16) or ((b[o + 3].toLong() and 255) shl 24)

    private fun encodePcm16(samples: FloatArray): ByteArray = ByteArray(samples.size * 2).also { out ->
        samples.forEachIndexed { i, sample ->
            val value = (sample.coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
            out[i * 2] = value.toByte(); out[i * 2 + 1] = (value shr 8).toByte()
        }
    }

    private fun wavHeader(sampleRate: Int, pcmSize: Long): ByteArray {
        require(pcmSize >= 0L && pcmSize <= RIFF_UINT32_MAX - 36L) { "PCM payload exceeds the RIFF/WAV 4 GiB limit" }
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(StandardCharsets.US_ASCII)); putInt((36L + pcmSize).toInt()); put("WAVE".toByteArray(StandardCharsets.US_ASCII)); put("fmt ".toByteArray(StandardCharsets.US_ASCII)); putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16); put("data".toByteArray(StandardCharsets.US_ASCII)); putInt(pcmSize.toInt())
        }.array()
    }

    private fun wavBytes(sampleRate: Int, pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(StandardCharsets.US_ASCII)); putInt(36 + pcm.size); put("WAVE".toByteArray(StandardCharsets.US_ASCII)); put("fmt ".toByteArray(StandardCharsets.US_ASCII)); putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16); put("data".toByteArray(StandardCharsets.US_ASCII)); putInt(pcm.size); put(pcm)
    }.array()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

data class ChunkAudioMetadata(val sampleRate: Int, val frameCount: Long, val fileBytes: Int)
