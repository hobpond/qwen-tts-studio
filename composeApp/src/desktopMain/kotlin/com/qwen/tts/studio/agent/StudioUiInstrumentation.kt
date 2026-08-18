package com.qwen.tts.studio.agent

import com.qwen.tts.studio.batch.BatchAudioStore
import com.qwen.tts.studio.batch.BatchChunkStatus
import com.qwen.tts.studio.batch.BatchGenerationRequest
import com.qwen.tts.studio.batch.BatchGenerationStatus
import com.qwen.tts.studio.batch.BatchManifest
import com.qwen.tts.studio.batch.BatchVoiceMode
import com.qwen.tts.studio.batch.BatchVoiceParameters
import com.qwen.tts.studio.batch.TextBatching
import com.qwen.tts.studio.viewmodel.StudioBatchUiState
import com.qwen.tts.studio.viewmodel.BatchIncrementalValidationSettings
import com.qwen.tts.studio.viewmodel.StudioViewModel
import com.qwen.tts.studio.engine.NativeBackendPreference
import java.io.File

/**
 * Code-level driver for the actions exposed by [BatchScreen].
 *
 * The desktop UI invokes these same view-model operations from button and file
 * picker callbacks. Keeping the sequence here makes an agent's hands explicit
 * without synthesizing OS input or creating a Compose window.
 */
class StudioUiInstrumentation(
    private val viewModel: StudioViewModel,
    private val onAction: (AgentUiActionEvidence) -> Unit = {}
) {
    suspend fun generate(
        request: BatchGenerationRequest,
        incrementalValidation: BatchIncrementalValidationSettings? = null
    ) {
        invoke("generate", "BatchScreen/Generate") {
            val job = viewModel.startBatchGeneration(request, incrementalValidation)
                ?: error("Studio rejected the batch start request.")
            job.join()
            requireCompletedBatch("Batch generation")
        }
    }

    fun loadManifest(manifestFile: File) {
        invokeSync("load-manifest", "BatchScreen/Load manifest") {
            // This is the exact callback sequence used by BatchScreen's file
            // picker after the user chooses a manifest.
            val request = viewModel.loadBatchManifest(manifestFile)
            val manifest = BatchAudioStore(request.outputDirectory).loadManifest(manifestFile.toPath())
            viewModel.setBatchManifestWorkspace(
                manifestFile,
                request,
                manifest
            )
            require(viewModel.batchState.value.manifest == manifest) {
                "Batch manifest was read but the BatchScreen state was not updated."
            }
        }
    }

    /** Invokes the BatchScreen failed-chunk recovery action in-place. */
    fun rechunkFailed(
        manifestFile: File,
        maxCharacters: Int = TextBatching.DEFAULT_RECHUNK_CHARACTERS
    ): BatchManifest {
        var updated: BatchManifest? = null
        invokeSync("rechunk-failed", "BatchScreen/Rechunk failed chunks") {
            val before = viewModel.batchState.value.manifest
                ?: error("Batch has no loaded manifest; load a manifest first.")
            val migrated = viewModel.rechunkFailedBatch(manifestFile, maxCharacters)
            updated = migrated
            require(migrated != before) {
                "Failed-chunk recovery did not change the durable manifest."
            }
            require(migrated.chunks.any { it.displayIndex.contains('.') }) {
                "Failed-chunk rechunking did not persist decimal logical indexes."
            }
        }
        return updated ?: error("Failed-chunk rechunking did not return a manifest.")
    }

    suspend fun validateAll(
        manifestFile: File,
        asrModelFile: File?,
        sourceFile: File?,
        asrBackend: NativeBackendPreference = NativeBackendPreference.Cpu,
        reusePersistedValidation: Boolean = false
    ) {
        invoke("validate-all", "BatchScreen/Validate all chunks") {
            viewModel.validateAllBatchChunks(
                manifestFile,
                asrModelFile,
                sourceFile,
                asrBackend,
                reusePersistedValidation
            ).join()
            requireValidationSucceeded()
        }
    }

    suspend fun combine(manifestFile: File, outputFile: File) {
        invoke("combine", "BatchScreen/Combine") {
            val job = viewModel.recombineBatchManifest(manifestFile, outputFile)
                ?: error("Batch manifest is not ready for recombination.")
            job.join()
            val state = viewModel.batchState.value
            require(state.recombineError == null && state.combinedFile?.isFile == true) {
                "Batch recombination did not produce a durable WAV."
            }
        }
    }

    /** Invokes the BatchScreen "Resume batch" callback against the loaded replay request. */
    suspend fun resume(
        incrementalValidation: BatchIncrementalValidationSettings? = null
    ) {
        invoke("resume", "BatchScreen/Resume batch") {
            val (request, manifest) = replayRequestAndManifest()
            val job = viewModel.startBatchGeneration(
                request.copy(
                    regenerateIndices = emptySet(),
                    onlyIndices = null,
                    chunkVoices = chunkVoices(manifest, request)
                ),
                incrementalValidation
            ) ?: error("Studio rejected the batch resume request.")
            job.join()
            requireCompletedBatch("Batch resume")
        }
    }

    /** Invokes the BatchScreen "Regenerate all chunks" callback. */
    suspend fun regenerateAll(
        incrementalValidation: BatchIncrementalValidationSettings? = null
    ) {
        invoke("regenerate-all", "BatchScreen/Regenerate all chunks") {
            val (request, manifest) = replayRequestAndManifest()
            val job = viewModel.startBatchGeneration(
                request.copy(
                    regenerateIndices = manifest.chunks.map { it.index }.toSet(),
                    onlyIndices = null,
                    chunkVoices = chunkVoices(manifest, request)
                ),
                incrementalValidation
            ) ?: error("Studio rejected the batch regeneration request.")
            job.join()
            requireCompletedBatch("Batch regeneration")
        }
    }

    /** Invokes the BatchScreen row-level regenerate/generate callback for one chunk. */
    suspend fun regenerateChunk(
        chunkIndex: Int,
        incrementalValidation: BatchIncrementalValidationSettings? = null
    ) {
        invoke("regenerate-chunk", "BatchScreen/Regenerate chunk $chunkIndex") {
            val (request, manifest) = replayRequestAndManifest()
            val chunk = manifest.chunks.firstOrNull { it.index == chunkIndex }
                ?: error("Chunk $chunkIndex is not present in the loaded manifest.")
            val job = viewModel.startBatchGeneration(
                request.copy(
                    regenerateIndices = if (chunk.status == BatchChunkStatus.COMPLETE) setOf(chunkIndex) else emptySet(),
                    onlyIndices = setOf(chunkIndex),
                    chunkVoices = chunkVoices(manifest, request)
                ),
                incrementalValidation
            ) ?: error("Studio rejected the chunk regeneration request.")
            job.join()
            requireCompletedBatch("Chunk $chunkIndex regeneration")
        }
    }

    /** Invokes the per-row validation callback used by BatchScreen. */
    suspend fun validateChunk(
        manifestFile: File,
        chunkIndex: Int,
        asrModelFile: File?,
        sourceFile: File?,
        asrBackend: NativeBackendPreference = NativeBackendPreference.Cpu
    ) {
        invoke("validate-chunk", "BatchScreen/Validate chunk $chunkIndex") {
            viewModel.validateBatchChunk(manifestFile, chunkIndex, asrModelFile, sourceFile, asrBackend).join()
            val state = viewModel.batchState.value
            val validation = state.chunkValidation[chunkIndex]
            require(validation?.passed == true && state.manifest?.chunks
                ?.firstOrNull { it.index == chunkIndex }
                ?.validationPassed == true
            ) {
                "Chunk $chunkIndex validation failed or did not persist a passing result."
            }
        }
    }

    /** Starts a batch and invokes the BatchScreen Cancel callback while it is active. */
    suspend fun cancel(request: BatchGenerationRequest) {
        invoke("cancel", "BatchScreen/Cancel") {
            val job = viewModel.startBatchGeneration(request)
                ?: error("Studio rejected the batch start request for cancellation.")
            viewModel.cancelBatchGeneration()
            job.join()
            val state = viewModel.batchState.value
            require(
                !state.isRunning &&
                    state.result?.status == BatchGenerationStatus.CANCELLED &&
                    state.manifest?.chunks?.any { it.status == BatchChunkStatus.CANCELLED } == true
            ) {
                "Batch cancellation was not observed as a durable CANCELLED result."
            }
        }
    }

    fun snapshot(): AgentUiSnapshot = AgentUiSnapshot.from(viewModel.batchState.value)

    private fun requireCompletedBatch(operation: String) {
        val state = viewModel.batchState.value
        val manifest = state.manifest
        val complete = state.result?.status == BatchGenerationStatus.COMPLETED &&
            !state.isRunning &&
            manifest != null &&
            manifest.chunks.size == manifest.expectedChunkCount &&
            manifest.chunks.all { it.status == BatchChunkStatus.COMPLETE }
        require(complete) {
            "$operation did not complete the batch: result=${state.result?.status}, " +
                "chunks=${manifest?.chunks?.count { it.status == BatchChunkStatus.COMPLETE } ?: 0}/" +
                "${manifest?.expectedChunkCount ?: state.total}, error=${state.error ?: state.recombineError ?: "none"}."
        }
    }

    private fun requireValidationSucceeded() {
        val state = viewModel.batchState.value
        val report = state.validationReport
        val failedChunks = state.chunkValidation
            .filterValues { it.passed != true }
            .entries
            .sortedBy { it.key }
            .joinToString { (index, validation) ->
                "$index: ${validation.message ?: "no passing result"}"
            }
        val failedAsr = report?.asrFindings?.filterNot { it.passed }.orEmpty()
        require(
            report != null &&
                report.passed &&
                failedAsr.isEmpty() &&
                state.chunkValidation.isNotEmpty() &&
                state.chunkValidation.values.all { it.passed == true } &&
                state.manifest?.chunks?.all { it.validationPassed == true } == true
        ) {
            val deterministic = report?.errors?.joinToString { it.message }.orEmpty()
            val asr = failedAsr.joinToString { it.error ?: "ASR similarity check failed" }
            "Batch validation failed. " +
                listOfNotNull(
                    deterministic.takeIf { it.isNotBlank() }?.let { "deterministic=$it" },
                    failedChunks.takeIf { it.isNotBlank() }?.let { "chunks=$it" },
                    asr.takeIf { it.isNotBlank() }?.let { "asr=$it" },
                    state.error
                ).joinToString("; ").ifBlank { "No passing validation state was persisted." }
        }
    }

    private fun replayRequestAndManifest(): Pair<BatchGenerationRequest, BatchManifest> {
        val state = viewModel.batchState.value
        return (state.replayRequest ?: error("Batch has no replay request; load a manifest first.")) to
            (state.manifest ?: error("Batch has no loaded manifest; load a manifest first."))
    }

    private fun chunkVoices(
        manifest: BatchManifest,
        request: BatchGenerationRequest
    ): Map<Int, BatchVoiceParameters> = manifest.chunks.associate { chunk ->
        chunk.index to BatchVoiceParameters(
            name = chunk.voiceName ?: request.speaker ?: request.defaultVoiceName ?: "Default Voice",
            modelName = chunk.modelName ?: request.modelName,
            voicePrompt = chunk.voicePrompt ?: request.instruction,
            speaker = if (request.voiceMode == BatchVoiceMode.NAMED_SPEAKER) chunk.voiceName else null,
            speakerEmbeddingPath = request.speakerEmbeddingPath,
            iclPromptPath = request.iclPromptPath
        )
    }

    private suspend fun invoke(action: String, target: String, block: suspend () -> Unit) {
        val before = snapshot()
        try {
            block()
            onAction(AgentUiActionEvidence(action, target, before, snapshot(), completed = true))
        } catch (error: Throwable) {
            onAction(
                AgentUiActionEvidence(
                    action = action,
                    target = target,
                    before = before,
                    after = snapshot(),
                    completed = false,
                    error = describeFailure(error)
                )
            )
            throw error
        }
    }

    private fun invokeSync(action: String, target: String, block: () -> Unit) {
        val before = snapshot()
        try {
            block()
            onAction(AgentUiActionEvidence(action, target, before, snapshot(), completed = true))
        } catch (error: Throwable) {
            onAction(
                AgentUiActionEvidence(
                    action = action,
                    target = target,
                    before = before,
                    after = snapshot(),
                    completed = false,
                    error = describeFailure(error)
                )
            )
            throw error
        }
    }

    private fun describeFailure(error: Throwable): String {
        val state = viewModel.batchState.value
        val base = error.message ?: error::class.simpleName ?: "Action failed."
        val validation = state.chunkValidation
            .filterValues { it.passed == false }
            .entries
            .sortedBy { it.key }
            .joinToString { (index, value) -> "$index=${value.message ?: "failed"}" }
        return buildString {
            append(base)
            append(" [state: running=")
            append(state.isRunning)
            append(", result=")
            append(state.result?.status)
            append(", chunks=")
            append(state.completed)
            append('/')
            append(state.total)
            state.statusMessage?.let { append(", status=").append(it) }
            state.error?.let { append(", error=").append(it) }
            state.recombineError?.let { append(", recombineError=").append(it) }
            if (validation.isNotBlank()) append(", failedChunks=").append(validation)
            append(']')
        }
    }
}

data class AgentUiSnapshot(
    val surface: String,
    val manifestPath: String?,
    val expectedChunks: Int,
    val completedChunks: Int,
    val validation: Map<Int, Boolean?>,
    val isRunning: Boolean,
    val isRecombining: Boolean,
    val statusMessage: String?,
    val error: String?,
    val combinedFile: String?
) {
    companion object {
        fun from(state: StudioBatchUiState): AgentUiSnapshot = AgentUiSnapshot(
            surface = "BatchScreen",
            manifestPath = state.manifestFile,
            expectedChunks = state.total,
            completedChunks = state.completed,
            validation = state.chunkValidation.mapValues { (_, value) -> value.passed },
            isRunning = state.isRunning,
            isRecombining = state.isRecombining,
            statusMessage = state.statusMessage,
            error = state.error ?: state.recombineError,
            combinedFile = state.combinedFile?.path
        )
    }
}

data class AgentUiActionEvidence(
    val action: String,
    val target: String,
    val before: AgentUiSnapshot,
    val after: AgentUiSnapshot,
    val completed: Boolean,
    val error: String? = null
)
