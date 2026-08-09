package com.qwen.tts.studio.batch

import com.qwen.tts.studio.engine.NativeBackendPreference
import com.qwen.tts.studio.engine.QwenEngine
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
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
    val preserveChunkBoundaries: Boolean = false,
    val regenerateIndices: Set<Int> = emptySet(),
    val onlyIndices: Set<Int>? = null
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
        onStatus: (String) -> Unit = {}
    ): BatchGenerationResult {
        require(request.outputDirectory.toAbsolutePath().normalize() == store.directoryPath) {
            "Batch store directory must equal request output directory"
        }

        val loaded = engine.loadDetailed(request.modelDir, request.modelName, request.backendPreference)
        if (!loaded.success) {
            val manifest = store.createManifest(request.batchId, request.texts.size, emptyMap())
            return BatchGenerationResult(BatchGenerationStatus.FAILED, manifest, emptyList(), loaded.errorMsg, request.outputDirectory)
        }
        if (!engine.supportsReusableBufferedSession()) {
            val error = "Batch generation requires a native reusable session; CLI fallback is not supported."
            val manifest = store.createManifest(request.batchId, request.texts.size, emptyMap())
            return BatchGenerationResult(BatchGenerationStatus.FAILED, manifest, emptyList(), error, request.outputDirectory)
        }

        val memory = BatchMemoryPolicy.snapshot(engine.backendMemory())
        val maxCharacters = BatchMemoryPolicy.maxCharacters(memory)
        val texts = if (request.preserveChunkBoundaries) {
            request.texts
        } else {
            TextBatching.packParagraphs(request.texts.joinToString("\n\n"), maxCharacters)
        }
        onStatus("Scanning existing chunk files...")
        val metadata = mapOf(
            "modelDir" to File(request.modelDir).absoluteFile.normalize().path,
            "modelName" to (request.modelName ?: ""),
            "backendPreference" to request.backendPreference.id,
            "voiceMode" to when {
                !request.speaker.isNullOrBlank() -> "named-speaker"
                !request.iclPromptPath.isNullOrBlank() -> "icl-prompt"
                !request.speakerEmbeddingPath.isNullOrBlank() -> "speaker-embedding"
                else -> "model-default"
            },
            "speaker" to (request.speaker ?: ""),
            "instruction" to (request.instruction ?: ""),
            "speakerEmbeddingPath" to (request.speakerEmbeddingPath ?: ""),
            "iclPromptPath" to (request.iclPromptPath ?: ""),
            "voiceProvenance" to (request.voiceProvenance ?: ""),
            "languageId" to request.languageId.toString(),
            "channels" to "1",
            "pcmEncoding" to "PCM_SIGNED_LE_16",
            "batchMaxCharacters" to maxCharacters.toString()
        )
        val resumableMetadata = metadata + ("textFingerprint" to textFingerprint(texts))
        var manifest = store.createOrResumeManifest(request.batchId, texts, resumableMetadata)
        val resumedCount = manifest.chunks.count { it.status == BatchChunkStatus.COMPLETE }
        onStatus(if (resumedCount == 0) "No compatible chunks found; starting at chunk 0." else "Resumed $resumedCount of ${texts.size} existing chunks.")

        val completed = mutableListOf<BatchItemResult>()
        val persistenceExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "batch-audio-persistence").apply { isDaemon = true }
        }
        var pendingWrite: PendingWrite? = null

        fun commitPendingWrite() {
            val pending = pendingWrite ?: return
            val chunk = pending.future.get()
            manifest = manifest.withChunk(chunk)
            store.persistManifest(manifest)
            completed += BatchItemResult(
                pending.index,
                pending.text,
                request.outputDirectory.resolve(chunk.fileName).toFile()
            )
            onProgress(completed.size, texts.size)
            pendingWrite = null
        }

        try {
            for (index in texts.indices) {
            val text = texts[index]
            if (request.onlyIndices != null && index !in request.onlyIndices) continue
            val existing = manifest.chunks.firstOrNull { it.index == index }
            if (existing?.status == BatchChunkStatus.COMPLETE && index !in request.regenerateIndices) {
                commitPendingWrite()
                completed += BatchItemResult(index, text, request.outputDirectory.resolve(existing.fileName).toFile())
                onProgress(completed.size, texts.size)
                continue
            }
            if (shouldCancel()) {
                commitPendingWrite()
                for (remaining in index until texts.size) {
                    manifest = store.markCancelled(manifest, remaining)
                }
                return BatchGenerationResult(BatchGenerationStatus.CANCELLED, manifest, completed)
            }

            try {
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
                    manifest = store.markFailed(manifest, index, error)
                    return BatchGenerationResult(BatchGenerationStatus.FAILED, manifest, completed, error, request.outputDirectory)
                }

                // Keep one write in flight while the next native generation runs. The
                // manifest is committed in order, so resume remains crash-safe.
                commitPendingWrite()
                val generated = GeneratedAudio(audio, native.sampleRate)
                val future = persistenceExecutor.submit<BatchChunk> {
                    store.writeChunkFile(manifest, index, generated, text)
                }
                pendingWrite = PendingWrite(index, text, future)
            } catch (error: Throwable) {
                runCatching { commitPendingWrite() }
                val message = error.message ?: error::class.simpleName ?: "Batch chunk failed."
                manifest = store.markFailed(manifest, index, message)
                return BatchGenerationResult(BatchGenerationStatus.FAILED, manifest, completed, message, request.outputDirectory)
            }
            }
            commitPendingWrite()
            if (shouldCancel() && completed.size < texts.size) {
                return BatchGenerationResult(BatchGenerationStatus.CANCELLED, manifest, completed, outputDirectory = request.outputDirectory)
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

    private fun textFingerprint(texts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        texts.forEach { text ->
            digest.update(text.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
