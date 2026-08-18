package com.qwen.tts.studio.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudiobookPipelineContractTest {
    private val run = PipelineRunId("run-1")
    private val revision = ManifestRevision(4L)
    private val lease = PipelineLease(PipelineLeaseId("lease-1"), run, revision, owner = "test")
    private val guard = ManifestPrecondition(run, revision, lease)

    @Test
    fun chunkCountsAndArtifactReadinessRemainDistinct() {
        val chunks = listOf(
            ChunkSnapshot(0, state = ChunkState.GENERATED),
            ChunkSnapshot(1, state = ChunkState.VALIDATED),
            ChunkSnapshot(2, state = ChunkState.FAILED),
            ChunkSnapshot(3, state = ChunkState.PENDING)
        )
        val counts = ChunkCounts.from(chunks)

        assertEquals(4, counts.total)
        assertEquals(1, counts.generated)
        assertEquals(1, counts.validated)
        assertEquals(1, counts.failed)
        assertEquals(1, counts.pending)
        assertEquals(ArtifactState(generated = true), ArtifactState().generated())
        assertEquals(ArtifactState(generated = true, playable = true), ArtifactState().playable())
        assertEquals(ArtifactState(generated = true, playable = true, validated = true), ArtifactState().validated())
        assertEquals(ArtifactState(generated = true, visualReady = true), ArtifactState().visualReady())
    }

    @Test
    fun staleRevisionAndLeaseAreRejected() {
        val snapshot = activeSnapshot()
        val revisionStale = ResumePipeline(
            PipelineCommandId("resume-old-revision"),
            guard.copy(expectedRevision = ManifestRevision(3L), lease = lease.copy(manifestRevision = ManifestRevision(3L)))
        )
        val leaseStale = ResumePipeline(
            PipelineCommandId("resume-old-lease"),
            guard.copy(lease = lease.copy(id = PipelineLeaseId("lease-old")))
        )

        assertEquals(CommandRejectionReason.REVISION_MISMATCH, PipelineCommandGuards.check(snapshot, revisionStale).reason)
        assertEquals(CommandOutcome.REJECTED_STALE, PipelineCommandGuards.check(snapshot, revisionStale).outcome)
        assertEquals(CommandRejectionReason.LEASE_MISMATCH, PipelineCommandGuards.check(snapshot, leaseStale).reason)
    }

    @Test
    fun eventReducerIgnoresWrongRunAndOlderRevision() {
        val snapshot = activeSnapshot()
        val newer = PhaseChanged(run, ManifestRevision(5L), PipelinePhase.GENERATING)
        val older = PhaseChanged(run, revision, PipelinePhase.PAUSED)
        val otherRun = PhaseChanged(PipelineRunId("other"), ManifestRevision(99L), PipelinePhase.FAILED)

        val afterNewer = snapshot.apply(newer)
        assertEquals(PipelinePhase.GENERATING, afterNewer.phase)
        assertEquals(ManifestRevision(5L), afterNewer.manifestRevision)
        assertEquals(afterNewer, afterNewer.apply(older))
        assertEquals(afterNewer, afterNewer.apply(otherRun))
    }

    @Test
    fun eventReducerPublishesChunksArtifactsAndValidation() {
        val snapshot = activeSnapshot()
        val generated = snapshot.chunks.first().copy(
            state = ChunkState.GENERATED,
            artifactIds = listOf("audio-0")
        )
        val audio = PipelineArtifact(
            id = "audio-0",
            kind = ArtifactKind.AUDIO_CHUNK,
            location = "batch/chunk-000000.wav",
            state = ArtifactState().playable()
        )
        val validated = generated.copy(
            state = ChunkState.VALIDATED,
            validation = ChunkValidation(
                state = ValidationState.PASSED,
                validator = "asr",
                score = 0.98,
                manifestRevision = ManifestRevision(6L),
                leaseId = lease.id
            )
        )
        val afterChunk = snapshot.apply(ChunkStateChanged(run, ManifestRevision(5L), generated))
        val afterArtifact = afterChunk.apply(ArtifactPublished(run, ManifestRevision(5L), audio))
        val afterValidation = afterArtifact.apply(
            ValidationChanged(
                run,
                ManifestRevision(6L),
                ValidationSummary(ValidationState.PASSED, validatedChunks = 1, validator = "asr"),
                validated
            )
        )

        assertEquals(ChunkState.VALIDATED, afterValidation.chunk(0)?.state)
        assertEquals(ValidationState.PASSED, afterValidation.chunk(0)?.validation?.state)
        assertEquals(1, afterValidation.artifacts.size)
        assertEquals(1, afterValidation.validation.validatedChunks)
        assertEquals(ManifestRevision(6L), afterValidation.manifestRevision)
    }

    @Test
    fun commandGuardAcceptsCurrentLeaseAndRejectsOtherRun() {
        val snapshot = activeSnapshot()
        val accepted = PipelineCommandGuards.check(snapshot, ValidatePipeline(PipelineCommandId("validate"), guard))
        val otherRunGuard = guard.copy(
            runId = PipelineRunId("other"),
            lease = lease.copy(runId = PipelineRunId("other"))
        )
        val rejected = PipelineCommandGuards.check(snapshot, ValidatePipeline(PipelineCommandId("validate-other"), otherRunGuard))

        assertTrue(accepted.accepted)
        assertFalse(rejected.accepted)
        assertEquals(CommandRejectionReason.RUN_MISMATCH, rejected.reason)
    }

    @Test
    fun contractRejectsInvalidLeaseAndRenderWindow() {
        assertFailsWith<IllegalArgumentException> {
            ManifestPrecondition(run, revision, lease.copy(manifestRevision = ManifestRevision(5L)))
        }
        assertFailsWith<IllegalArgumentException> {
            RenderPipeline(PipelineCommandId("render"), guard, linesOnScreen = 4)
        }
    }

    private fun activeSnapshot(): PipelineSnapshot = PipelineSnapshot(
        runId = run,
        manifestRevision = revision,
        phase = PipelinePhase.PAUSED,
        chunks = listOf(ChunkSnapshot(0, textLength = 10), ChunkSnapshot(1, textLength = 20)),
        activeLease = lease,
        provenance = PipelineProvenance(source = "book.txt")
    )
}
