package com.qwen.tts.studio.orchestration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Stable identity allocated by the caller and persisted with a run. */
@JvmInline
value class PipelineRunId(val value: String) {
    init {
        require(value.isNotBlank()) { "PipelineRunId must not be blank" }
    }
}

/** Monotonic manifest revision used for compare-and-swap command guards. */
@JvmInline
value class ManifestRevision(val value: Long) {
    init {
        require(value >= 0L) { "ManifestRevision must not be negative" }
    }

    fun next(): ManifestRevision = ManifestRevision(value + 1L)
}

@JvmInline
value class PipelineCommandId(val value: String) {
    init {
        require(value.isNotBlank()) { "PipelineCommandId must not be blank" }
    }
}

@JvmInline
value class PipelineLeaseId(val value: String) {
    init {
        require(value.isNotBlank()) { "PipelineLeaseId must not be blank" }
    }
}

data class PipelineLease(
    val id: PipelineLeaseId,
    val runId: PipelineRunId,
    val manifestRevision: ManifestRevision,
    val owner: String? = null
)

/** Every mutating command after start carries both guards. */
data class ManifestPrecondition(
    val runId: PipelineRunId,
    val expectedRevision: ManifestRevision,
    val lease: PipelineLease
) {
    init {
        require(lease.runId == runId) { "Lease belongs to a different run" }
        require(lease.manifestRevision == expectedRevision) { "Lease revision does not match expected revision" }
    }
}

enum class PipelinePhase {
    IDLE,
    STARTING,
    CHUNKING,
    GENERATING,
    VALIDATING,
    COMBINING,
    RENDERING,
    STREAMING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class ChunkState {
    PENDING,
    GENERATING,
    GENERATED,
    VALIDATING,
    VALIDATED,
    FAILED,
    CANCELLED
}

enum class ValidationState {
    NOT_REQUESTED,
    QUEUED,
    RUNNING,
    PASSED,
    FAILED,
    STALE,
    SKIPPED
}

enum class ArtifactKind {
    SOURCE_TEXT,
    MANIFEST,
    TEXT_CHUNK,
    AUDIO_CHUNK,
    COMBINED_AUDIO,
    SUBTITLE,
    SWEEP_VIDEO,
    STREAM
}

/** These fields intentionally remain separate: generated is not proof of playability or validation. */
data class ArtifactState(
    val generated: Boolean = false,
    val playable: Boolean = false,
    val validated: Boolean = false,
    val visualReady: Boolean = false
) {
    init {
        require(!playable || generated) { "Playable artifacts must be generated" }
        require(!validated || playable) { "Validated artifacts must be playable" }
        require(!visualReady || generated) { "Visual-ready artifacts must be generated" }
    }

    fun generated(): ArtifactState = copy(generated = true)
    fun playable(): ArtifactState = copy(generated = true, playable = true)
    fun validated(): ArtifactState = copy(generated = true, playable = true, validated = true)
    fun visualReady(): ArtifactState = copy(generated = true, visualReady = true)
}

data class PipelineProvenance(
    val source: String? = null,
    val model: String? = null,
    val modelFingerprint: String? = null,
    val runtimeFingerprint: String? = null,
    val voice: String? = null,
    val voicePrompt: String? = null,
    val attributes: Map<String, String> = emptyMap()
)

data class PipelineArtifact(
    val id: String,
    val kind: ArtifactKind,
    /** A path, URI, or adapter-defined handle. The contract never opens it. */
    val location: String,
    val state: ArtifactState = ArtifactState(),
    val byteCount: Long? = null,
    val sha256: String? = null,
    val provenance: PipelineProvenance = PipelineProvenance()
) {
    init {
        require(id.isNotBlank()) { "Artifact id must not be blank" }
        require(location.isNotBlank()) { "Artifact location must not be blank" }
        require(byteCount == null || byteCount >= 0L) { "Artifact byteCount must not be negative" }
    }
}

data class PipelineError(
    val code: String,
    val message: String,
    val phase: PipelinePhase? = null,
    val chunkIndex: Int? = null,
    val retryable: Boolean = false
) {
    init {
        require(code.isNotBlank()) { "PipelineError code must not be blank" }
        require(message.isNotBlank()) { "PipelineError message must not be blank" }
        require(chunkIndex == null || chunkIndex >= 0) { "PipelineError chunkIndex must not be negative" }
    }
}

data class ChunkValidation(
    val state: ValidationState = ValidationState.NOT_REQUESTED,
    val validator: String? = null,
    val score: Double? = null,
    val message: String? = null,
    val manifestRevision: ManifestRevision? = null,
    val leaseId: PipelineLeaseId? = null
) {
    init {
        require(score == null || score in 0.0..1.0) { "Validation score must be between 0 and 1" }
        require(state != ValidationState.PASSED || score == null || score >= 0.0) { "Passed validation score is invalid" }
    }
}

data class ChunkSnapshot(
    val index: Int,
    val displayIndex: String = index.toString(),
    val textLength: Int = 0,
    val state: ChunkState = ChunkState.PENDING,
    val artifactIds: List<String> = emptyList(),
    val validation: ChunkValidation = ChunkValidation(),
    val error: PipelineError? = null
) {
    init {
        require(index >= 0) { "Chunk index must not be negative" }
        require(displayIndex.isNotBlank()) { "Chunk displayIndex must not be blank" }
        require(textLength >= 0) { "Chunk textLength must not be negative" }
        require(artifactIds.distinct().size == artifactIds.size) { "Chunk artifactIds must be unique" }
    }
}

data class ChunkCounts(
    val total: Int,
    val pending: Int,
    val generating: Int,
    val generated: Int,
    val validating: Int,
    val validated: Int,
    val failed: Int,
    val cancelled: Int
) {
    init {
        require(listOf(pending, generating, generated, validating, validated, failed, cancelled).all { it >= 0 }) {
            "Chunk counts must not be negative"
        }
        require(total == pending + generating + generated + validating + validated + failed + cancelled) {
            "Chunk counts must add up to total"
        }
    }

    companion object {
        fun from(chunks: List<ChunkSnapshot>): ChunkCounts {
            fun count(state: ChunkState) = chunks.count { it.state == state }
            return ChunkCounts(
                total = chunks.size,
                pending = count(ChunkState.PENDING),
                generating = count(ChunkState.GENERATING),
                generated = count(ChunkState.GENERATED),
                validating = count(ChunkState.VALIDATING),
                validated = count(ChunkState.VALIDATED),
                failed = count(ChunkState.FAILED),
                cancelled = count(ChunkState.CANCELLED)
            )
        }
    }
}

data class ValidationSummary(
    val state: ValidationState = ValidationState.NOT_REQUESTED,
    val validatedChunks: Int = 0,
    val failedChunks: Int = 0,
    val validator: String? = null,
    val message: String? = null
) {
    init {
        require(validatedChunks >= 0) { "validatedChunks must not be negative" }
        require(failedChunks >= 0) { "failedChunks must not be negative" }
    }
}

data class PipelineSnapshot(
    val runId: PipelineRunId? = null,
    val manifestRevision: ManifestRevision = ManifestRevision(0L),
    val phase: PipelinePhase = PipelinePhase.IDLE,
    val chunks: List<ChunkSnapshot> = emptyList(),
    val artifacts: List<PipelineArtifact> = emptyList(),
    val validation: ValidationSummary = ValidationSummary(),
    val errors: List<PipelineError> = emptyList(),
    val provenance: PipelineProvenance = PipelineProvenance(),
    val activeLease: PipelineLease? = null,
    val message: String? = null
) {
    init {
        require(chunks.map { it.index }.distinct().size == chunks.size) { "Snapshot chunk indexes must be unique" }
        require(artifacts.map { it.id }.distinct().size == artifacts.size) { "Snapshot artifact ids must be unique" }
        require(activeLease == null || activeLease.runId == runId) { "Active lease must belong to the snapshot run" }
    }

    val chunkCounts: ChunkCounts get() = ChunkCounts.from(chunks)

    fun chunk(index: Int): ChunkSnapshot? = chunks.firstOrNull { it.index == index }

    /** Stale events are ignored so UI and headless consumers can safely reconcile one event stream. */
    fun apply(event: PipelineEvent): PipelineSnapshot {
        if (event is RunStarted) {
            if (runId != null && runId != event.runId) return this
            if (runId == event.runId && event.manifestRevision.value < manifestRevision.value) return this
            return copy(
                runId = event.runId,
                manifestRevision = event.manifestRevision,
                phase = PipelinePhase.STARTING,
                chunks = event.chunks,
                artifacts = event.artifacts,
                validation = ValidationSummary(),
                errors = emptyList(),
                provenance = event.provenance,
                activeLease = event.lease,
                message = null
            )
        }
        if (runId == null || event.runId != runId || event.manifestRevision.value < manifestRevision.value) return this
        val base = copy(manifestRevision = event.manifestRevision)
        return when (event) {
            is PhaseChanged -> base.copy(phase = event.phase, message = event.message)
            is ChunkStateChanged -> base.replaceChunk(event.chunk)
            is ArtifactPublished -> base.replaceArtifact(event.artifact)
            is ValidationChanged -> base.copy(
                chunks = event.chunk?.let(base.chunks::replaceByIndex) ?: base.chunks,
                validation = event.summary
            )
            is ErrorRaised -> base.copy(errors = (base.errors + event.error).distinct())
            is LeaseChanged -> base.copy(activeLease = event.lease)
            is RunFinished -> base.copy(phase = event.phase, message = event.message)
            is RunStarted -> error("RunStarted is handled above")
        }
    }

    private fun replaceChunk(chunk: ChunkSnapshot): PipelineSnapshot =
        copy(chunks = chunks.replaceByIndex(chunk))

    private fun replaceArtifact(artifact: PipelineArtifact): PipelineSnapshot =
        copy(artifacts = artifacts.replaceById(artifact))
}

private fun List<ChunkSnapshot>.replaceByIndex(replacement: ChunkSnapshot): List<ChunkSnapshot> =
    if (any { it.index == replacement.index }) map { if (it.index == replacement.index) replacement else it }
    else (this + replacement).sortedBy { it.index }

private fun List<PipelineArtifact>.replaceById(replacement: PipelineArtifact): List<PipelineArtifact> =
    if (any { it.id == replacement.id }) map { if (it.id == replacement.id) replacement else it }
    else this + replacement

sealed interface PipelineEvent {
    val runId: PipelineRunId
    val manifestRevision: ManifestRevision
}

data class RunStarted(
    override val runId: PipelineRunId,
    override val manifestRevision: ManifestRevision,
    val chunks: List<ChunkSnapshot>,
    val artifacts: List<PipelineArtifact> = emptyList(),
    val provenance: PipelineProvenance = PipelineProvenance(),
    val lease: PipelineLease
) : PipelineEvent {
    init {
        require(lease.runId == runId) { "Run lease belongs to a different run" }
        require(lease.manifestRevision == manifestRevision) { "Run lease revision must match event revision" }
    }
}

data class PhaseChanged(
    override val runId: PipelineRunId,
    override val manifestRevision: ManifestRevision,
    val phase: PipelinePhase,
    val message: String? = null
) : PipelineEvent

data class ChunkStateChanged(
    override val runId: PipelineRunId,
    override val manifestRevision: ManifestRevision,
    val chunk: ChunkSnapshot
) : PipelineEvent

data class ArtifactPublished(
    override val runId: PipelineRunId,
    override val manifestRevision: ManifestRevision,
    val artifact: PipelineArtifact
) : PipelineEvent

data class ValidationChanged(
    override val runId: PipelineRunId,
    override val manifestRevision: ManifestRevision,
    val summary: ValidationSummary,
    val chunk: ChunkSnapshot? = null
) : PipelineEvent

data class ErrorRaised(
    override val runId: PipelineRunId,
    override val manifestRevision: ManifestRevision,
    val error: PipelineError
) : PipelineEvent

data class LeaseChanged(
    override val runId: PipelineRunId,
    override val manifestRevision: ManifestRevision,
    val lease: PipelineLease?
) : PipelineEvent

data class RunFinished(
    override val runId: PipelineRunId,
    override val manifestRevision: ManifestRevision,
    val phase: PipelinePhase,
    val message: String? = null
) : PipelineEvent {
    init {
        require(phase == PipelinePhase.COMPLETED || phase == PipelinePhase.FAILED || phase == PipelinePhase.CANCELLED) {
            "RunFinished must use a terminal phase"
        }
    }
}

data class SourceInput(
    val location: String,
    val displayName: String? = null,
    val sha256: String? = null,
    val contentType: String? = null
) {
    init {
        require(location.isNotBlank()) { "Source location must not be blank" }
    }
}

data class StartOptions(
    val voice: String? = null,
    val voicePrompt: String? = null,
    val model: String? = null,
    val modelFingerprint: String? = null,
    val runtimeFingerprint: String? = null,
    val attributes: Map<String, String> = emptyMap()
)

sealed interface ChunkSelection {
    data object All : ChunkSelection
    data class Indexes(val values: Set<Int>) : ChunkSelection {
        init {
            require(values.all { it >= 0 }) { "Chunk indexes must not be negative" }
        }
    }
}

sealed interface PipelineCommand {
    val commandId: PipelineCommandId
    val runId: PipelineRunId
    val precondition: ManifestPrecondition?
}

data class StartPipeline(
    override val commandId: PipelineCommandId,
    override val runId: PipelineRunId,
    val source: SourceInput,
    val options: StartOptions = StartOptions()
) : PipelineCommand {
    override val precondition: ManifestPrecondition? = null
}

sealed interface ActivePipelineCommand : PipelineCommand {
    override val precondition: ManifestPrecondition
    override val runId: PipelineRunId get() = precondition.runId
}

data class ResumePipeline(
    override val commandId: PipelineCommandId,
    override val precondition: ManifestPrecondition
) : ActivePipelineCommand

data class CancelPipeline(
    override val commandId: PipelineCommandId,
    override val precondition: ManifestPrecondition,
    val reason: String? = null
) : ActivePipelineCommand

data class RetryChunks(
    override val commandId: PipelineCommandId,
    override val precondition: ManifestPrecondition,
    val selection: ChunkSelection,
    val revalidate: Boolean = true
) : ActivePipelineCommand

data class RechunkPipeline(
    override val commandId: PipelineCommandId,
    override val precondition: ManifestPrecondition,
    val maxCharacters: Int,
    val selection: ChunkSelection = ChunkSelection.All
) : ActivePipelineCommand {
    init {
        require(maxCharacters > 0) { "maxCharacters must be positive" }
    }
}

data class ValidatePipeline(
    override val commandId: PipelineCommandId,
    override val precondition: ManifestPrecondition,
    val selection: ChunkSelection = ChunkSelection.All,
    val validatorProfile: String? = null
) : ActivePipelineCommand

data class CombinePipeline(
    override val commandId: PipelineCommandId,
    override val precondition: ManifestPrecondition,
    val artifactId: String? = null
) : ActivePipelineCommand

data class RenderPipeline(
    override val commandId: PipelineCommandId,
    override val precondition: ManifestPrecondition,
    val linesOnScreen: Int = 7,
    val profile: String? = null
) : ActivePipelineCommand {
    init {
        require(linesOnScreen in 3..11 && linesOnScreen % 2 == 1) {
            "linesOnScreen must be an odd value between 3 and 11"
        }
    }
}

data class StreamPipeline(
    override val commandId: PipelineCommandId,
    override val precondition: ManifestPrecondition,
    val artifactId: String,
    val live: Boolean = true
) : ActivePipelineCommand {
    init {
        require(artifactId.isNotBlank()) { "Stream artifactId must not be blank" }
    }
}

enum class CommandOutcome { ACCEPTED, REJECTED_STALE, REJECTED_INVALID }

enum class CommandRejectionReason { RUN_MISMATCH, REVISION_MISMATCH, LEASE_MISMATCH, INVALID_PHASE }

data class CommandDecision(
    val outcome: CommandOutcome,
    val reason: CommandRejectionReason? = null,
    val message: String? = null
) {
    val accepted: Boolean get() = outcome == CommandOutcome.ACCEPTED
}

/** Shared guard logic for both a desktop adapter and a headless fake. */
object PipelineCommandGuards {
    fun check(snapshot: PipelineSnapshot, command: PipelineCommand): CommandDecision {
        if (command is StartPipeline) {
            return if (snapshot.runId == null || snapshot.phase in terminalPhases) {
                CommandDecision(CommandOutcome.ACCEPTED)
            } else {
                CommandDecision(CommandOutcome.REJECTED_INVALID, CommandRejectionReason.INVALID_PHASE, "A run is already active")
            }
        }
        val guard = command.precondition
            ?: return CommandDecision(CommandOutcome.REJECTED_INVALID, CommandRejectionReason.LEASE_MISMATCH, "Active commands require a manifest precondition")
        if (snapshot.runId != guard.runId) {
            return CommandDecision(CommandOutcome.REJECTED_STALE, CommandRejectionReason.RUN_MISMATCH, "Command run does not match the current snapshot")
        }
        if (snapshot.manifestRevision != guard.expectedRevision) {
            return CommandDecision(CommandOutcome.REJECTED_STALE, CommandRejectionReason.REVISION_MISMATCH, "Manifest revision is stale")
        }
        val activeLease = snapshot.activeLease
        if (activeLease == null || activeLease != guard.lease) {
            return CommandDecision(CommandOutcome.REJECTED_STALE, CommandRejectionReason.LEASE_MISMATCH, "Manifest lease is stale")
        }
        return CommandDecision(CommandOutcome.ACCEPTED)
    }

    private val terminalPhases = setOf(PipelinePhase.COMPLETED, PipelinePhase.FAILED, PipelinePhase.CANCELLED)
}

data class PipelineCommandReceipt(
    val commandId: PipelineCommandId,
    val runId: PipelineRunId,
    val outcome: CommandOutcome,
    val revision: ManifestRevision,
    val message: String? = null
)

/** Adapter seam: UI and instrumentation dispatch identical commands and observe identical state/events. */
interface AudiobookPipelineAdapter {
    val snapshot: StateFlow<PipelineSnapshot>
    val events: Flow<PipelineEvent>

    suspend fun dispatch(command: PipelineCommand): PipelineCommandReceipt
}
