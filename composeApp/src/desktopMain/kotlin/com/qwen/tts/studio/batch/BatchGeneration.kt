package com.qwen.tts.studio.batch

import com.qwen.tts.studio.engine.NativeBackendPreference
import com.qwen.tts.studio.engine.QwenEngine
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.Future

private object BatchLog {
    private val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun error(message: String) {
        System.err.println("[${OffsetDateTime.now(ZoneId.systemDefault()).format(formatter)}] $message")
    }
}

interface BatchEngine {
    fun loadDetailed(
        modelDir: String,
        modelName: String?,
        backendPreference: NativeBackendPreference
    ): QwenEngine.NativeOperationResult

    fun supportsReusableBufferedSession(): Boolean

    /** Capabilities and named speakers for the model loaded by [loadDetailed]. */
    fun modelCapabilities(): QwenEngine.NativeCapabilities? = null

    fun availableSpeakers(): List<String> = emptyList()

    fun backendMemory(): com.qwen.tts.studio.engine.QwenEngine.BackendMemory? = null

    fun textTokenCount(text: String): Int = -1

    fun generateDetailed(
        text: String,
        speakerEmbeddingPath: String?,
        iclPromptPath: String?,
        languageId: Int,
        instruction: String?,
        speaker: String?,
        maxAudioTokens: Int
    ): QwenEngine.NativeResult

    fun generateStreaming(
        text: String,
        speakerEmbeddingPath: String?,
        iclPromptPath: String?,
        languageId: Int,
        instruction: String?,
        speaker: String?
    ): BatchStreamingResult? = null
}

data class BatchStreamingResult(
    val audio: FloatArray,
    val sampleRate: Int,
    val spans: List<BatchAlignmentSpan>
)

class QwenBatchEngine(private val engine: QwenEngine) : BatchEngine {
    override fun loadDetailed(modelDir: String, modelName: String?, backendPreference: NativeBackendPreference) =
        engine.loadDetailed(modelDir, modelName, backendPreference)

    override fun supportsReusableBufferedSession() = engine.supportsReusableBufferedSession()

    override fun modelCapabilities() = engine.getModelCapabilities()

    override fun availableSpeakers() = engine.getAvailableSpeakers()

    override fun backendMemory() = engine.backendMemory()

    override fun textTokenCount(text: String) = engine.textTokenCount(text)

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

    override fun generateStreaming(
        text: String,
        speakerEmbeddingPath: String?,
        iclPromptPath: String?,
        languageId: Int,
        instruction: String?,
        speaker: String?
    ): BatchStreamingResult? {
        val audio = ArrayList<FloatArray>()
        var sampleRate = 0
        val spans = ArrayList<BatchAlignmentSpan>()
        val result = engine.generateStreaming(
            text = text,
            speakerEmbeddingPath = speakerEmbeddingPath,
            iclPromptPath = iclPromptPath,
            languageId = languageId,
            instruction = instruction,
            speaker = speaker,
            options = QwenEngine.StreamingOptions(chunkSeconds = 0.75f, leftContextSeconds = 2f, collectAudio = true)
        ) { chunk ->
            if (chunk.audio.isNotEmpty()) audio += chunk.audio
            sampleRate = chunk.sampleRate
            byteRangeToTextRange(text, chunk.startTextByte, chunk.endTextByte)?.let { range ->
                spans += BatchAlignmentSpan(
                    startText = range.first,
                    endText = range.second,
                    startSeconds = chunk.startSample.toFloat() / chunk.sampleRate.coerceAtLeast(1),
                    endSeconds = chunk.endSample.toFloat() / chunk.sampleRate.coerceAtLeast(1),
                    confidence = chunk.confidence
                )
            }
            true
        }
        if (result?.success != true || audio.isEmpty() || sampleRate <= 0 || spans.isEmpty()) return null
        val merged = FloatArray(audio.sumOf { it.size })
        var offset = 0
        audio.forEach { part -> part.copyInto(merged, offset); offset += part.size }
        return BatchStreamingResult(merged, sampleRate, spans)
    }

    private fun byteRangeToTextRange(text: String, startByte: Int, endByte: Int): Pair<Int, Int>? {
        if (startByte < 0 || endByte <= startByte) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (startByte >= bytes.size) return null
        fun byteToChar(target: Int): Int {
            var byte = 0
            var char = 0
            while (char < text.length && byte < target) {
                val codePoint = text.codePointAt(char)
                byte += String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
                char += Character.charCount(codePoint)
            }
            return char.coerceAtMost(text.length)
        }
        return byteToChar(startByte) to byteToChar(endByte.coerceAtMost(bytes.size))
    }
}

data class BatchVoiceParameters(
    val name: String,
    val modelName: String? = null,
    val voicePrompt: String? = null,
    val speakerEmbeddingPath: String? = null,
    val iclPromptPath: String? = null,
    val speaker: String? = null
)

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
    val allowIncompatibleReplacement: Boolean = false,
    val defaultVoiceName: String? = null,
    val chunkVoices: Map<Int, BatchVoiceParameters> = emptyMap()
) {
    init {
        require(batchId.isNotBlank()) { "batchId must not be blank" }
        require(modelDir.isNotBlank()) { "modelDir must not be blank" }
        require(texts.isNotEmpty()) { "texts must not be empty" }
        require(texts.all { it.isNotBlank() }) { "texts must not contain blank entries" }
        require(regenerateIndices.all { it in texts.indices }) { "regenerateIndices contains an invalid chunk index" }
        require(onlyIndices == null || onlyIndices.all { it in texts.indices }) { "onlyIndices contains an invalid chunk index" }
        require(chunkVoices.keys.all { it in texts.indices }) { "chunkVoices contains an invalid chunk index" }
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
        onChunkStarted: (index: Int) -> Unit = {},
        onChunkReady: (index: Int) -> Unit = {},
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

        var loadedCapabilities = engine.modelCapabilities()
        var loadedSpeakers = engine.availableSpeakers()
        if (loadedCapabilities?.supportsNamedSpeakers == true &&
            request.speaker.isNullOrBlank() && request.speakerEmbeddingPath.isNullOrBlank() && request.iclPromptPath.isNullOrBlank() &&
            request.chunkVoices.values.none { !it.speaker.isNullOrBlank() || !it.speakerEmbeddingPath.isNullOrBlank() || !it.iclPromptPath.isNullOrBlank() } &&
            loadedSpeakers.isEmpty()
        ) {
            return preflightFailure(
                "The selected CustomVoice model exposes no named speakers. Select a speaker or provide a speaker embedding/reference before generating."
            )
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
        val textTokenCounter: ((String) -> Int)? = if (engine.textTokenCount("") >= 0) {
            { value -> engine.textTokenCount(TextBatching.cleanForSynthesis(value)) }
        } else null
        val needsMeasuredRepack = !request.preserveChunkBoundaries && request.texts.any { text ->
            text.length > maxCharacters || (textTokenCounter?.invoke(text)?.let { it > 2_048 } == true)
        }
        val texts = if (needsMeasuredRepack) {
            TextBatching.packParagraphsMeasured(
                request.texts.joinToString(separator = ""),
                maxCharacters,
                maxTextTokens = 2_048,
                tokenCount = textTokenCounter
            )
        } else {
            request.texts
        }
        val generationTexts = texts.map(TextBatching::cleanForSynthesis)
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
                        error = limitError,
                        voiceName = existing?.voiceName,
                        modelName = existing?.modelName,
                        voicePrompt = existing?.voicePrompt
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
                    error = error,
                    voiceName = manifest.chunks.firstOrNull { it.index == index }?.voiceName,
                    modelName = manifest.chunks.firstOrNull { it.index == index }?.modelName,
                    voicePrompt = manifest.chunks.firstOrNull { it.index == index }?.voicePrompt
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
                pending.alignmentSpans?.let { spans ->
                    val sampleRate = chunk.sampleRate ?: 0
                    val duration = (chunk.frameCount ?: 0L).toFloat() / sampleRate.coerceAtLeast(1)
                    val alignment = BatchChunkAlignment(
                        textSha256 = sha256Text(pending.text),
                        wavSha256 = chunk.sha256.orEmpty(),
                        sampleRate = sampleRate,
                        durationSeconds = duration,
                        quality = "APPROXIMATE",
                        method = "qwen-tts-streaming-estimated",
                        spans = spans
                    )
                    if (alignment.isValidFor(pending.text, chunk.sha256.orEmpty(), duration)) {
                        store.writeAlignment(pending.index, alignment)
                        BatchLog.error("[BatchAlignment] chunk=${pending.index} method=${alignment.method} quality=${alignment.quality} spans=${spans.size} duration=${"%.3f".format(java.util.Locale.ROOT, duration)}")
                    } else {
                        store.deleteAlignment(pending.index)
                        BatchLog.error("[BatchAlignment] chunk=${pending.index} rejected reason=guardrail spans=${spans.size} duration=${"%.3f".format(java.util.Locale.ROOT, duration)}")
                    }
                }
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
                            error = "Cancelled",
                            voiceName = existing?.voiceName,
                            modelName = existing?.modelName,
                            voicePrompt = existing?.voicePrompt
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
                val requestedVoiceName = request.chunkVoices[index]?.name
                if (existing?.status == BatchChunkStatus.COMPLETE && index !in request.regenerateIndices &&
                    (requestedVoiceName == null || (existing.voiceName == requestedVoiceName &&
                        existing.modelName == request.chunkVoices[index]?.modelName &&
                        existing.voicePrompt == request.chunkVoices[index]?.voicePrompt))
                ) {
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
                    val voice = request.chunkVoices[index]
                    if (voice != null && (manifest.chunks.firstOrNull { it.index == index }?.voiceName != voice.name || manifest.chunks.firstOrNull { it.index == index }?.modelName != voice.modelName || manifest.chunks.firstOrNull { it.index == index }?.voicePrompt != voice.voicePrompt)) {
                        manifest = manifest.withChunk(manifest.chunks.first { it.index == index }.copy(voiceName = voice.name, modelName = voice.modelName, voicePrompt = voice.voicePrompt))
                        store.persistManifest(manifest)
                        onManifest(manifest)
                    }
                    val requestedModel = voice?.modelName ?: request.modelName
                    if (requestedModel != request.modelName) {
                        val reloaded = engine.loadDetailed(request.modelDir, requestedModel, request.backendPreference)
                        if (!reloaded.success) error(reloaded.errorMsg ?: "Could not load model $requestedModel")
                        loadedCapabilities = engine.modelCapabilities()
                        loadedSpeakers = engine.availableSpeakers()
                    }
                    // Make regeneration visibly pending before native work starts.
                    manifest = store.markPending(manifest, index, text)
                    onChunkStarted(index)
                    onManifest(manifest)
                    val generationText = generationTexts[index]
                    val speakerEmbedding = voice?.speakerEmbeddingPath ?: request.speakerEmbeddingPath
                    val iclPrompt = voice?.iclPromptPath ?: request.iclPromptPath
                    val instruction = voice?.voicePrompt ?: request.instruction
                    val speaker = voice?.speaker ?: request.speaker ?: loadedSpeakers.firstOrNull()
                    if (loadedCapabilities?.supportsNamedSpeakers == true &&
                        speaker.isNullOrBlank() && speakerEmbedding.isNullOrBlank() && iclPrompt.isNullOrBlank()
                    ) {
                        error("CustomVoice model requires a named speaker, reference, or speaker embedding; none was resolved for chunk $index.")
                    }
                    var streamed = engine.generateStreaming(generationText, speakerEmbedding, iclPrompt, request.languageId, instruction, speaker)
                    var native = streamed?.let { QwenEngine.NativeResult(it.audio, it.sampleRate, true, null, 0L) }
                        ?: engine.generateDetailed(
                            text = generationText,
                            speakerEmbeddingPath = speakerEmbedding,
                            iclPromptPath = iclPrompt,
                            languageId = request.languageId,
                            instruction = instruction,
                            speaker = speaker,
                            maxAudioTokens = BatchMemoryPolicy.maxAudioTokens(generationText)
                        )
                    val safeAudioSamples = BatchMemoryPolicy.maxAudioSamples(generationText)
                    // Native generation can stop just below the requested
                    // budget, before the token ceiling is reached. In long
                    // chunks that still presents as a clipped final sentence.
                    // Retry near the ceiling so validation does not have to
                    // discover the truncation after persistence.
                    if (native.success && native.audio != null &&
                        native.audio.size.toLong() >= (safeAudioSamples * AUDIO_RETRY_THRESHOLD).toLong() &&
                        generationText.length >= 200
                    ) {
                        val split = splitForAudioRetry(generationText)
                        if (split != null) {
                            onStatus("Audio budget reached for chunk $index; retrying at a sentence boundary...")
                            val parts = split.toList().map { part ->
                                engine.generateDetailed(
                                    text = part,
                                    speakerEmbeddingPath = speakerEmbedding,
                                    iclPromptPath = iclPrompt,
                                    languageId = request.languageId,
                                    instruction = instruction,
                                    speaker = speaker,
                                    maxAudioTokens = BatchMemoryPolicy.maxAudioTokens(part)
                                )
                            }
                            if (parts.all { it.success && it.audio?.isNotEmpty() == true && it.sampleRate == native.sampleRate }) {
                                val samples = FloatArray(parts.sumOf { it.audio!!.size })
                                var offset = 0
                                parts.forEach { part -> part.audio!!.copyInto(samples, offset); offset += part.audio!!.size }
                                native = QwenEngine.NativeResult(samples, native.sampleRate, true, null, native.timeMs)
                                streamed = null
                            }
                        }
                    }
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
                    onChunkReady(index)
                    commitPendingWrite()?.let { pendingError ->
                        val currentError = markFailure(index, text, "Previous persistence failed: $pendingError")
                        return BatchGenerationResult(BatchGenerationStatus.FAILED, manifest, completed, "$pendingError; $currentError", request.outputDirectory)
                    }
                    val writeManifest = manifest
                    val future = persistenceExecutor.submit<BatchChunk> {
                        onPersistenceStarted(index)
                        BatchLog.error("[BatchPersistence] start chunk=$index dir=${store.directoryPath}")
                        try {
                            store.writeChunkFile(writeManifest, index, generated, text).also { chunk ->
                                // Persist completion as soon as the WAV and text sidecars are
                                // durable. Do not wait for the next GPU generation to finish;
                                // otherwise the manifest can report PENDING for minutes while
                                // the completed chunk is already playable on disk.
                                val persisted = store.persistCompletedChunk(chunk)
                                onManifest(persisted)
                                onPersistenceCompleted(index)
                                BatchLog.error("[BatchPersistence] complete chunk=$index file=${store.directoryPath.resolve(chunk.fileName)} manifest=${store.directoryPath.resolve("manifest.json")}")
                            }
                        } catch (error: Throwable) {
                            BatchLog.error("[BatchPersistence] failed chunk=$index dir=${store.directoryPath} error=${error.message ?: error::class.simpleName}")
                            throw error
                        }
                    }
                    pendingWrite = PendingWrite(index, text, future, streamed?.spans)
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

    private data class PendingWrite(
        val index: Int,
        val text: String,
        val future: Future<BatchChunk>,
        val alignmentSpans: List<BatchAlignmentSpan>? = null
    )

    private fun splitForAudioRetry(text: String): Pair<String, String>? {
        val candidates = Regex("(?<=[.!?。！？])\\s+").findAll(text).map { it.range.first + 1 }.toList()
        val midpoint = text.length / 2
        val cut = candidates.minByOrNull { kotlin.math.abs(it - midpoint) } ?: return null
        if (cut <= 0 || cut >= text.length) return null
        return text.substring(0, cut).trimEnd() to text.substring(cut).trimStart()
    }

    private companion object {
        const val AUDIO_RETRY_THRESHOLD = 0.95
    }

}
