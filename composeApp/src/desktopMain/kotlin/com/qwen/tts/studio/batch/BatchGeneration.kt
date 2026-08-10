package com.qwen.tts.studio.batch

import com.qwen.tts.studio.engine.NativeBackendPreference
import com.qwen.tts.studio.engine.QwenEngine
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.Future

interface BatchEngine {
    fun loadDetailed(
        modelDir: String,
        modelName: String?,
        backendPreference: NativeBackendPreference
    ): QwenEngine.NativeOperationResult

    fun supportsReusableBufferedSession(): Boolean

    fun backendMemory(): com.qwen.tts.studio.engine.QwenEngine.BackendMemory? = null

    fun generateDetailed(
        text: String,
        speakerEmbeddingPath: String?,
        iclPromptPath: String?,
        languageId: Int,
        instruction: String?,
        speaker: String?,
        maxAudioTokens: Int
    ): QwenEngine.NativeResult
}

class QwenBatchEngine(private val engine: QwenEngine) : BatchEngine {
    override fun loadDetailed(modelDir: String, modelName: String?, backendPreference: NativeBackendPreference) =
        engine.loadDetailed(modelDir, modelName, backendPreference)

    override fun supportsReusableBufferedSession() = engine.supportsReusableBufferedSession()

    override fun backendMemory() = engine.backendMemory()

    override fun generateDetailed(
        text: String,
        speakerEmbeddingPath: String?,
        iclPromptPath: String?,
        languageId: Int,
        instruction: String?,
        speaker: String?,
        maxAudioTokens: Int
    ) = engine.generateDetailed(
        text = text,
        speakerEmbeddingPath = speakerEmbeddingPath,
        iclPromptPath = iclPromptPath,
        languageId = languageId,
        instruction = instruction,
        speaker = speaker,
        maxAudioTokens = maxAudioTokens
    )
}

/** Immutable request snapshot for one reusable native generation session. */
data class BatchGenerationRequest(
    val batchId: String,
    val modelDir: String,
    val modelName: String?,
    val backendPreference: NativeBackendPreference,
    val texts: List<String>,
    val languageId: Int,
    val instruction: String?,
    val speaker: String?,
    val speakerEmbeddingPath: String?,
    val iclPromptPath: String?,
    val outputDirectory: Path,
    val voiceProvenance: String? = null,
    val voiceMode: BatchVoiceMode? = null,
    val speakerEmbeddingSha256: String? = null,
    val referenceWavPath: String? = null,
    val referenceWavSha256: String? = null,
    val iclPromptSha256: String? = null,
    val preserveChunkBoundaries: Boolean = false,
    val regenerateIndices: Set<Int> = emptySet(),
    val onlyIndices: Set<Int>? = null,
    val allowIncompatibleReplacement: Boolean = false
) {
    init {
        require(batchId.isNotBlank()) { "batchId must not be blank" }
        require(modelDir.isNotBlank()) { "modelDir must not be blank" }
        require(texts.isNotEmpty()) { "texts must not be empty" }
        require(texts.all { it.isNotBlank() }) { "texts must not contain blank entries" }
        require(regenerateIndices.all { it in texts.indices }) { "regenerateIndices contains an invalid chunk index" }
        require(onlyIndices == null || onlyIndices.all { it in texts.indices }) { "onlyIndices contains an invalid chunk index" }
    }
}

data class BatchItemResult(
    val index: Int,
    val text: String,
    val outputFile: File?,
    val error: String? = null
)

enum class BatchGenerationStatus { COMPLETED, PARTIAL, CANCELLED, FAILED }

data class BatchGenerationResult(
    val status: BatchGenerationStatus,
    val manifest: BatchManifest,
    val items: List<BatchItemResult>,
    val error: String? = null,
    val outputDirectory: Path = Path.of(".").toAbsolutePath().normalize()
)

/**
 * Runs buffered requests against one explicitly loaded native engine.
 * The engine is never loaded or released between chunk requests.
 */
class BatchGenerationSession(
    private val engine: BatchEngine,
    private val store: BatchAudioStore
) {
    fun run(
        request: BatchGenerationRequest,
        shouldCancel: () -> Boolean = { false },
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        onStatus: (String) -> Unit = {},
        onManifest: (BatchManifest) -> Unit = {},
        onPersistenceStarted: (index: Int) -> Unit = {},
        onPersistenceCompleted: (index: Int) -> Unit = {}
    ): BatchGenerationResult {
        require(request.outputDirectory.toAbsolutePath().normalize() == store.directoryPath) {
            "Batch store directory must equal request output directory"
        }

        val requestIdentity = BatchIdentity.forRequest(request)
        val manifestPath = request.outputDirectory.toAbsolutePath().normalize().resolve("manifest.json")
        val manifestRead = if (Files.isRegularFile(manifestPath)) {
            runCatching { store.loadManifest() }
        } else {
            Result.success(null)
        }
        val previousManifest = manifestRead.getOrNull()
        val previousCompatible = BatchIdentity.isCompatible(previousManifest, requestIdentity, request.texts.size)

        fun inMemoryFailureManifest(): BatchManifest = previousManifest?.takeIf { previousCompatible } ?:
            BatchManifest(
                request.batchId,
                request.texts.size,
                request.texts.mapIndexed { index, text ->
                    BatchChunk(index, "chunk-%06d.wav".format(index), text)
                },
                requestIdentity.metadata()
            )

        fun preflightFailure(error: String?): BatchGenerationResult = BatchGenerationResult(
            BatchGenerationStatus.FAILED,
            inMemoryFailureManifest(),
            emptyList(),
            error,
            request.outputDirectory
        )

        val manifestFailure = manifestRead.exceptionOrNull()
        if (manifestFailure != null && !request.allowIncompatibleReplacement) {
            return preflightFailure(
                "The existing batch manifest could not be read; refusing to replace it without explicit authorization: " +
                    (manifestFailure.message ?: manifestFailure::class.simpleName)
            )
        }

        if (previousManifest != null && !previousCompatible && !request.allowIncompatibleReplacement) {
            return preflightFailure(
                "The output directory contains an incompatible batch. Generate a manifest or explicitly allow replacement before starting."
            )
        }

        val artifactFailure = runCatching {
            BatchIdentity.requireArtifactIntegrity(request)
        }.exceptionOrNull()
        if (artifactFailure != null) return preflightFailure(artifactFailure.message)

        val loaded = runCatching {
            engine.loadDetailed(request.modelDir, request.modelName, request.backendPreference)
        }.getOrElse { QwenEngine.NativeOperationResult(false, it.message ?: it::class.simpleName) }
        if (!loaded.success) return preflightFailure(loaded.errorMsg)
        if (!engine.supportsReusableBufferedSession()) {
            return preflightFailure("Batch generation requires a native reusable session; CLI fallback is not supported.")
        }

        val memory = BatchMemoryPolicy.snapshot(engine.backendMemory())
        val memoryPlan = BatchMemoryPolicy.plan(memory)
        val maxCharacters = memoryPlan.maxCharacters
        if (maxCharacters == null) {
            val error = memoryPlan.reason ?: "Insufficient observed memory for safe batch generation."
            val resultManifest = inMemoryFailureManifest()
            return BatchGenerationResult(BatchGenerationStatus.FAILED, resultManifest, emptyList(), error, request.outputDirectory)
        }
        // The request contains the authoritative final chunk plan. Rejoining
        // chunks here would invent separators and make direct generation
        // disagree with the manifest generated from the same source.
        val texts = if (!request.preserveChunkBoundaries && request.texts.any { it.length > maxCharacters }) {
            TextBatching.packParagraphs(request.texts.joinToString(separator = ""), maxCharacters)
        } else {
            request.texts
        }
        val selectedIndices = request.onlyIndices ?: texts.indices.toSet()
        if (request.preserveChunkBoundaries && previousCompatible) {
            val oversizedReplayIndices = selectedIndices.filter { index ->
                val previous = previousManifest?.chunks?.firstOrNull { it.index == index }
                previous != null &&
                    (previous.status != BatchChunkStatus.COMPLETE || index in request.regenerateIndices) &&
                    texts[index].length > maxCharacters
            }.sorted()
            if (oversizedReplayIndices.isNotEmpty()) {
                return preflightFailure(
                    "The current safe capacity is $maxCharacters characters, below selected replay chunks: ${oversizedReplayIndices.joinToString()}."
                )
            }
        }
        onStatus("Scanning existing chunk files...")
        val resumableMetadata = BatchIdentity.forRequest(request.copy(texts = texts)).metadata() +
            ("batchMaxCharacters" to maxCharacters.toString())
        var manifest = store.createOrResumeManifest(request.batchId, texts, resumableMetadata)
        onManifest(manifest)
        if (request.onlyIndices != null && previousManifest != null) {
            val previousCompatible = BatchIdentity.isCompatible(
                previousManifest,
                BatchIdentity.forRequest(request.copy(texts = texts)),
                texts.size
            )
            if (previousCompatible) {
                var restored = false
                previousManifest.chunks.filter { it.index !in request.onlyIndices }.forEach { previous ->
                    if (previous.text != texts[previous.index]) return@forEach
                    val current = manifest.chunks.firstOrNull { it.index == previous.index }
                    // Keep non-selected lifecycle states stable, but only retain a
                    // COMPLETE state when the resume scan proved its WAV valid.
                    if (previous.status != BatchChunkStatus.COMPLETE || current?.status == BatchChunkStatus.COMPLETE) {
                        manifest = manifest.withChunk(previous)
                        restored = true
                    }
                }
                if (restored) {
                    store.persistManifest(manifest)
                    onManifest(manifest)
                }
            }
        }
        val resumedCount = manifest.chunks.count { it.status == BatchChunkStatus.COMPLETE }
        onStatus(if (resumedCount == 0) "No compatible chunks found; starting at chunk 0." else "Resumed $resumedCount of ${texts.size} existing chunks.")

        val completed = mutableListOf<BatchItemResult>()

        fun failureMessage(error: Throwable): String =
            error.message ?: error::class.simpleName ?: "Batch persistence failed."

        val oversizedIndices = selectedIndices.filter { index ->
            val existing = manifest.chunks.firstOrNull { it.index == index }
            texts[index].length > maxCharacters && existing?.status != BatchChunkStatus.COMPLETE
        }.sorted()
        if (oversizedIndices.isNotEmpty()) {
            val limitError = "Submitted replay chunk plan exceeds the adaptive memory limit of $maxCharacters characters."
            val failed = oversizedIndices.fold(manifest) { current, index ->
                val existing = current.chunks.firstOrNull { it.index == index }
                current.withChunk(
                    BatchChunk(
                        index = index,
                        fileName = existing?.fileName ?: "chunk-%06d.wav".format(index),
                        text = texts[index],
                        status = BatchChunkStatus.FAILED,
                        error = limitError
                    )
                )
            }
            return try {
                store.persistManifest(failed)
                BatchGenerationResult(
                    BatchGenerationStatus.FAILED,
                    failed,
                    emptyList(),
                    "$limitError Chunks: ${oversizedIndices.joinToString()}.",
                    request.outputDirectory
                )
            } catch (error: Throwable) {
                BatchGenerationResult(
                    BatchGenerationStatus.FAILED,
                    failed,
                    emptyList(),
                    "$limitError Manifest persistence failed: ${failureMessage(error)}",
                    request.outputDirectory
                )
            }
        }

        val persistenceExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "batch-audio-persistence").apply { isDaemon = true }
        }
        var pendingWrite: PendingWrite? = null

        fun markFailure(index: Int, text: String, error: String): String {
            val failed = manifest.withChunk(
                BatchChunk(
                    index = index,
                    fileName = manifest.chunks.firstOrNull { it.index == index }?.fileName
                        ?: "chunk-%06d.wav".format(index),
                    text = text,
                    status = BatchChunkStatus.FAILED,
                    error = error
                )
            )
            manifest = failed
            val persistenceFailure = runCatching { store.persistManifest(failed) }.exceptionOrNull()
            onManifest(manifest)
            return if (persistenceFailure == null) error
            else "$error Manifest persistence failed: ${failureMessage(persistenceFailure)}"
        }

        fun commitPendingWrite(): String? {
            val pending = pendingWrite ?: return null
            return try {
                val chunk = pending.future.get()
                val nextManifest = manifest.withChunk(chunk)
                store.persistManifest(nextManifest)
                manifest = nextManifest
                onManifest(manifest)
                completed += BatchItemResult(
                    pending.index,
                    pending.text,
                    request.outputDirectory.resolve(chunk.fileName).toFile()
                )
                onProgress(completed.size, texts.size)
                pendingWrite = null
                null
            } catch (error: Throwable) {
                pendingWrite = null
                markFailure(pending.index, pending.text, failureMessage(error))
            }
        }

        fun cancelSelectedFrom(startIndex: Int): String? {
            return try {
                selectedIndices.filter { it >= startIndex }.sorted().forEach { index ->
                    val existing = manifest.chunks.firstOrNull { it.index == index }
                    // Cancellation must never downgrade a durable completion, even
                    // when the selected operation was a regeneration.
                    if (existing?.status == BatchChunkStatus.COMPLETE) return@forEach
                    manifest = manifest.withChunk(
                        BatchChunk(
                            index = index,
                            fileName = existing?.fileName ?: "chunk-%06d.wav".format(index),
                            text = existing?.text ?: texts[index],
                            status = BatchChunkStatus.CANCELLED,
                            error = "Cancelled"
                        )
                    )
                    store.persistManifest(manifest)
                    onManifest(manifest)
                }
                null
            } catch (error: Throwable) {
                "Cancellation persistence failed: ${failureMessage(error)}"
            }
        }

        fun cancellationResult(startIndex: Int): BatchGenerationResult {
            val persistenceError = cancelSelectedFrom(startIndex)
            return if (persistenceError == null) {
                BatchGenerationResult(
                    BatchGenerationStatus.CANCELLED,
                    manifest,
                    completed,
                    outputDirectory = request.outputDirectory
                )
            } else {
                BatchGenerationResult(
                    BatchGenerationStatus.FAILED,
                    manifest,
                    completed,
                    persistenceError,
                    request.outputDirectory
                )
            }
        }

        try {
            for (index in texts.indices) {
                if (index !in selectedIndices) continue
                val text = texts[index]
                val existing = manifest.chunks.firstOrNull { it.index == index }
                if (existing?.status == BatchChunkStatus.COMPLETE && index !in request.regenerateIndices) {
                    commitPendingWrite()?.let { error ->
                        return BatchGenerationResult(BatchGenerationStatus.FAILED, manifest, completed, error, request.outputDirectory)
                    }
                    completed += BatchItemResult(index, text, request.outputDirectory.resolve(existing.fileName).toFile())
                    onProgress(completed.size, texts.size)
                    continue
                }
                if (shouldCancel()) {
                    commitPendingWrite()?.let { error ->
                        return BatchGenerationResult(BatchGenerationStatus.FAILED, manifest, completed, error, request.outputDirectory)
                    }
                    return cancellationResult(index)
                }

                try {
                    // Make regeneration visibly pending before native work starts.
                    manifest = store.markPending(manifest, index, text)
                    onManifest(manifest)
                    val native = engine.generateDetailed(
                        text = text,
                        speakerEmbeddingPath = request.speakerEmbeddingPath,
                        iclPromptPath = request.iclPromptPath,
                        languageId = request.languageId,
                        instruction = request.instruction,
                        speaker = request.speaker,
                        maxAudioTokens = BatchMemoryPolicy.maxAudioTokens(text)
                    )
                    val audio = native.audio?.takeIf { native.success && it.isNotEmpty() }
                    if (audio == null || native.sampleRate <= 0) {
                        val error = native.errorMsg ?: "Synthesis returned no audio."
                        commitPendingWrite()?.let { pendingError ->
                            val currentError = markFailure(index, text, error)
                            return BatchGenerationResult(BatchGenerationStatus.FAILED, manifest, completed, "$pendingError; $currentError", request.outputDirectory)
                        }
                        val persistedError = markFailure(index, text, error)
                        return BatchGenerationResult(BatchGenerationStatus.FAILED, manifest, completed, persistedError, request.outputDirectory)
                    }

                    // Generate the next chunk while the previous chunk is written,
                    // then commit the previous write before admitting this one.
                    val generated = GeneratedAudio(audio, native.sampleRate)
                    commitPendingWrite()?.let { pendingError ->
                        val currentError = markFailure(index, text, "Previous persistence failed: $pendingError")
                        return BatchGenerationResult(BatchGenerationStatus.FAILED, manifest, completed, "$pendingError; $currentError", request.outputDirectory)
                    }
                    val writeManifest = manifest
                    val future = persistenceExecutor.submit<BatchChunk> {
                        onPersistenceStarted(index)
                        store.writeChunkFile(writeManifest, index, generated, text).also {
                            onPersistenceCompleted(index)
                        }
                    }
                    pendingWrite = PendingWrite(index, text, future)
                } catch (error: Throwable) {
                    commitPendingWrite()?.let { persistenceError ->
                        val currentError = markFailure(index, text, failureMessage(error))
                        return BatchGenerationResult(BatchGenerationStatus.FAILED, manifest, completed, "$persistenceError; $currentError", request.outputDirectory)
                    }
                    val message = markFailure(index, text, failureMessage(error))
                    return BatchGenerationResult(BatchGenerationStatus.FAILED, manifest, completed, message, request.outputDirectory)
                }
            }
            commitPendingWrite()?.let { error ->
                return BatchGenerationResult(BatchGenerationStatus.FAILED, manifest, completed, error, request.outputDirectory)
            }
            val selectedOperationComplete = selectedIndices.all { index ->
                val completedThisRun = completed.any { it.index == index }
                val alreadyComplete = index !in request.regenerateIndices &&
                    manifest.chunks.firstOrNull { it.index == index }?.status == BatchChunkStatus.COMPLETE
                completedThisRun || alreadyComplete
            }
            if (shouldCancel() && !selectedOperationComplete) {
                return cancellationResult(texts.lastIndex + 1)
            }
            val complete = manifest.chunks.size == texts.size && manifest.chunks.all { it.status == BatchChunkStatus.COMPLETE }
            return BatchGenerationResult(
                if (complete) BatchGenerationStatus.COMPLETED else BatchGenerationStatus.PARTIAL,
                manifest,
                completed,
                outputDirectory = request.outputDirectory
            )
        } finally {
            persistenceExecutor.shutdownNow()
        }
    }

    private data class PendingWrite(val index: Int, val text: String, val future: Future<BatchChunk>)

}
