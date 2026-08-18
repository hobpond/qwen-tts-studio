package com.qwen.tts.studio.orchestration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopAudiobookPipelineOrchestratorTest {
    @Test
    fun ordinaryCommandsAreSerialized() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val executor = object : PipelineCommandExecutor {
            override suspend fun execute(
                context: PipelineExecutionContext,
                port: PipelineExecutionPort
            ): PipelineExecutionResult {
                calls += context.commandId.value
                if (context.commandId.value == "one") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                port.publish(PhaseChanged(context.runId, ManifestRevision(1), PipelinePhase.GENERATING))
                return result(context)
            }

            override suspend fun requestCancellation(
                context: PipelineExecutionContext,
                port: PipelineExecutionPort
            ): PipelineExecutionResult = result(context)
        }
        val seed = activeSnapshot()
        val adapter = DesktopAudiobookPipelineOrchestrator(
            executor,
            snapshotProvider = PipelineSnapshotProvider { seed }
        )
        try {
            val first = async { adapter.dispatch(resume("one", seed)) }
            firstStarted.await()
            val second = async { adapter.dispatch(resume("two", seed)) }
            delay(50)
            assertEquals(listOf("one"), calls)

            releaseFirst.complete(Unit)
            assertEquals(CommandOutcome.ACCEPTED, first.await().outcome)
            assertEquals(CommandOutcome.ACCEPTED, second.await().outcome)
            assertEquals(listOf("one", "two"), calls)
        } finally {
            adapter.close()
        }
    }

    @Test
    fun staleCommandIsRejectedWithoutExecutorInvocationAndLeavesEvidence() = runBlocking {
        var invocations = 0
        val executor = object : PipelineCommandExecutor {
            override suspend fun execute(
                context: PipelineExecutionContext,
                port: PipelineExecutionPort
            ): PipelineExecutionResult {
                invocations += 1
                return result(context)
            }

            override suspend fun requestCancellation(
                context: PipelineExecutionContext,
                port: PipelineExecutionPort
            ): PipelineExecutionResult = result(context)
        }
        val seed = activeSnapshot()
        val adapter = DesktopAudiobookPipelineOrchestrator(
            executor,
            snapshotProvider = PipelineSnapshotProvider { seed }
        )
        try {
            val stale = resume(
                id = "stale",
                snapshot = seed,
                revision = ManifestRevision(0),
                lease = seed.activeLease!!
            )
            val receipt = adapter.dispatch(stale)

            assertEquals(CommandOutcome.REJECTED_STALE, receipt.outcome)
            assertEquals(0, invocations)
            assertEquals(CommandRejectionReason.REVISION_MISMATCH, adapter.lastCommandEvidence.value?.reason)
            assertEquals(CommandOutcome.REJECTED_STALE, adapter.lastCommandEvidence.value?.outcome)
            assertTrue(adapter.snapshot.value.errors.any { it.code == "STALE_COMMAND" })
        } finally {
            adapter.close()
        }
    }

    @Test
    fun acceptedExecutorEventsReduceIntoSnapshotAndAreForwarded() = runBlocking {
        val runId = PipelineRunId("run-events")
        val lease = PipelineLease(PipelineLeaseId("lease-events"), runId, ManifestRevision(1))
        val chunk = ChunkSnapshot(index = 0, displayIndex = "0.1", textLength = 12)
        val artifact = PipelineArtifact(
            id = "audio-0.1",
            kind = ArtifactKind.AUDIO_CHUNK,
            location = "chunk-0.1.wav",
            state = ArtifactState().playable()
        )
        val executor = object : PipelineCommandExecutor {
            override suspend fun execute(
                context: PipelineExecutionContext,
                port: PipelineExecutionPort
            ): PipelineExecutionResult {
                port.publish(RunStarted(runId, ManifestRevision(1), listOf(chunk), lease = lease))
                port.publish(PhaseChanged(runId, ManifestRevision(1), PipelinePhase.GENERATING))
                port.publish(ChunkStateChanged(runId, ManifestRevision(1), chunk.copy(state = ChunkState.GENERATED)))
                port.publish(ArtifactPublished(runId, ManifestRevision(1), artifact))
                port.publish(
                    ValidationChanged(
                        runId,
                        ManifestRevision(1),
                        ValidationSummary(ValidationState.PASSED, validatedChunks = 1, validator = "fake"),
                        chunk.copy(
                            state = ChunkState.VALIDATED,
                            validation = ChunkValidation(
                                state = ValidationState.PASSED,
                                validator = "fake",
                                score = 1.0,
                                manifestRevision = ManifestRevision(1),
                                leaseId = lease.id
                            )
                        )
                    )
                )
                port.publish(RunFinished(runId, ManifestRevision(1), PipelinePhase.COMPLETED, "done"))
                return result(context)
            }

            override suspend fun requestCancellation(
                context: PipelineExecutionContext,
                port: PipelineExecutionPort
            ): PipelineExecutionResult = result(context)
        }
        val adapter = DesktopAudiobookPipelineOrchestrator(executor)
        val events = mutableListOf<PipelineEvent>()
        val collectorReady = CompletableDeferred<Unit>()
        val collector = launch {
            adapter.events
                .onSubscription { collectorReady.complete(Unit) }
                .collect { events += it }
        }
        try {
            collectorReady.await()
            val receipt = adapter.dispatch(
                StartPipeline(
                    commandId = PipelineCommandId("events-command"),
                    runId = runId,
                    source = SourceInput("book.txt")
                )
            )

            assertEquals(CommandOutcome.ACCEPTED, receipt.outcome)
            assertEquals(PipelinePhase.COMPLETED, adapter.snapshot.value.phase)
            assertEquals(ChunkState.VALIDATED, adapter.snapshot.value.chunk(0)?.state)
            assertEquals(ValidationState.PASSED, adapter.snapshot.value.validation.state)
            assertEquals("audio-0.1", adapter.snapshot.value.artifacts.single().id)
            assertEquals(6, events.size)
            assertTrue(events.last() is RunFinished)
        } finally {
            collector.cancelAndJoin()
            adapter.close()
        }
    }

    @Test
    fun cancellationSignalsInFlightExecutionAndWaitsForTerminalEvent() = runBlocking {
        val cancellationRequested = CompletableDeferred<Unit>()
        val executionStarted = CompletableDeferred<Unit>()
        val seed = activeSnapshot()
        val executor = object : PipelineCommandExecutor {
            override suspend fun execute(
                context: PipelineExecutionContext,
                port: PipelineExecutionPort
            ): PipelineExecutionResult {
                executionStarted.complete(Unit)
                cancellationRequested.await()
                port.publish(PhaseChanged(context.runId, ManifestRevision(1), PipelinePhase.CANCELLED))
                port.publish(RunFinished(context.runId, ManifestRevision(1), PipelinePhase.CANCELLED, "user cancelled"))
                return result(context)
            }

            override suspend fun requestCancellation(
                context: PipelineExecutionContext,
                port: PipelineExecutionPort
            ): PipelineExecutionResult {
                cancellationRequested.complete(Unit)
                return result(context, "cancellation requested")
            }
        }
        val adapter = DesktopAudiobookPipelineOrchestrator(
            executor,
            snapshotProvider = PipelineSnapshotProvider { seed }
        )
        try {
            val running = async { adapter.dispatch(resume("running", seed)) }
            executionStarted.await()
            val cancelled = withTimeout(2_000) {
                adapter.dispatch(
                    CancelPipeline(
                        commandId = PipelineCommandId("cancel"),
                        precondition = seed.precondition()
                    )
                )
            }

            assertEquals(CommandOutcome.ACCEPTED, cancelled.outcome)
            assertEquals(PipelinePhase.CANCELLED, adapter.snapshot.value.phase)
            assertEquals(CommandOutcome.ACCEPTED, running.await().outcome)
        } finally {
            adapter.close()
        }
    }

    private fun result(
        context: PipelineExecutionContext,
        message: String? = null
    ) = PipelineExecutionResult(context.commandId, context.runId, message)

    private fun activeSnapshot(): PipelineSnapshot {
        val runId = PipelineRunId("run-active")
        val revision = ManifestRevision(1)
        val lease = PipelineLease(PipelineLeaseId("lease-active"), runId, revision)
        return PipelineSnapshot(
            runId = runId,
            manifestRevision = revision,
            phase = PipelinePhase.GENERATING,
            chunks = listOf(ChunkSnapshot(index = 0, textLength = 8, state = ChunkState.GENERATING)),
            activeLease = lease
        )
    }

    private fun resume(
        id: String,
        snapshot: PipelineSnapshot,
        revision: ManifestRevision = snapshot.manifestRevision,
        lease: PipelineLease = snapshot.activeLease!!
    ) = ResumePipeline(
        commandId = PipelineCommandId(id),
        precondition = ManifestPrecondition(snapshot.runId!!, revision, lease.copy(manifestRevision = revision))
    )

    private fun PipelineSnapshot.precondition(): ManifestPrecondition =
        ManifestPrecondition(runId!!, manifestRevision, activeLease!!)
}
