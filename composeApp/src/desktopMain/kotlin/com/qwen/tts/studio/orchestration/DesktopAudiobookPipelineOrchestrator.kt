package com.qwen.tts.studio.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Immutable input supplied to a desktop executor for one command invocation.
 * The adapter owns command admission; the executor owns the actual backend work.
 */
data class PipelineExecutionContext(
    val command: PipelineCommand,
    val commandId: PipelineCommandId = command.commandId,
    val runId: PipelineRunId = command.runId,
    val precondition: ManifestPrecondition? = command.precondition
) {
    init {
        require(commandId == command.commandId) { "Execution command identity must be preserved" }
        require(runId == command.runId) { "Execution run identity must be preserved" }
    }
}

/** Event publication seam used by a backend, headed UI adapter, or headless runner. */
fun interface PipelineExecutionPort {
    suspend fun publish(event: PipelineEvent)
}

/** Result returned after the executor has accepted and completed the command operation. */
data class PipelineExecutionResult(
    val commandId: PipelineCommandId,
    val runId: PipelineRunId,
    val message: String? = null
) {
    init {
        require(commandId.value.isNotBlank()) { "Execution result command id must not be blank" }
        require(runId.value.isNotBlank()) { "Execution result run id must not be blank" }
    }
}

/**
 * Desktop execution boundary. Implementations may call [port] as chunks become ready,
 * validated, visually prepared, or otherwise change the durable pipeline state.
 *
 * [requestCancellation] is intentionally separate from [execute]: it is called while an
 * in-flight execution may still be running, so a UI cancel action never waits for the normal
 * dispatch mutex before reaching the backend.
 */
interface PipelineCommandExecutor {
    suspend fun execute(
        context: PipelineExecutionContext,
        port: PipelineExecutionPort
    ): PipelineExecutionResult

    suspend fun requestCancellation(
        context: PipelineExecutionContext,
        port: PipelineExecutionPort
    ): PipelineExecutionResult
}

/** Provider used to reconstruct a desktop adapter from durable state without opening storage. */
fun interface PipelineSnapshotProvider {
    fun snapshot(): PipelineSnapshot
}

enum class PipelineCommandEvidenceKind {
    ACCEPTED,
    REJECTED
}

/** Adapter-level evidence for command admission, including rejections with no valid pipeline event. */
data class PipelineCommandEvidence(
    val commandId: PipelineCommandId,
    val commandRunId: PipelineRunId,
    val snapshotRunId: PipelineRunId?,
    val expectedRevision: ManifestRevision?,
    val actualRevision: ManifestRevision,
    val outcome: CommandOutcome,
    val kind: PipelineCommandEvidenceKind,
    val reason: CommandRejectionReason? = null,
    val message: String? = null
)

/**
 * Desktop orchestration core shared by headed semantic UI and headless instrumentation.
 *
 * It does not own a Qwen engine or a manifest store. It only guards command identity, serializes
 * ordinary operations, reduces executor events, and provides observable state/evidence.
 */
class DesktopAudiobookPipelineOrchestrator(
    private val executor: PipelineCommandExecutor,
    snapshotProvider: PipelineSnapshotProvider = PipelineSnapshotProvider { PipelineSnapshot() },
    executionDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AudiobookPipelineAdapter, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + executionDispatcher)
    private val dispatchMutex = Mutex()
    private val stateMutex = Mutex()

    private val _snapshot = MutableStateFlow(snapshotProvider.snapshot())
    override val snapshot: StateFlow<PipelineSnapshot> = _snapshot

    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()

    private val _commandEvidence = MutableSharedFlow<PipelineCommandEvidence>(extraBufferCapacity = 64)
    val commandEvidence: SharedFlow<PipelineCommandEvidence> = _commandEvidence.asSharedFlow()

    private val _lastCommandEvidence = MutableStateFlow<PipelineCommandEvidence?>(null)
    val lastCommandEvidence: StateFlow<PipelineCommandEvidence?> = _lastCommandEvidence

    private data class ActiveInvocation(
        val context: PipelineExecutionContext,
        val job: Deferred<PipelineExecutionResult>
    )

    private var activeInvocation: ActiveInvocation? = null

    /**
     * Ordinary commands are admitted and executed one at a time. Cancellation is a control-path
     * exception: it acquires state protection, signals the executor, and awaits the in-flight job
     * without taking [dispatchMutex].
     */
    override suspend fun dispatch(command: PipelineCommand): PipelineCommandReceipt {
        return if (command is CancelPipeline) {
            dispatchCancellation(command)
        } else {
            dispatchOrdinary(command)
        }
    }

    private suspend fun dispatchOrdinary(command: PipelineCommand): PipelineCommandReceipt =
        dispatchMutex.withLock {
            val decision = stateMutex.withLock { PipelineCommandGuards.check(_snapshot.value, command) }
            if (!decision.accepted) return@withLock reject(command, decision)

            val alreadyRunning = stateMutex.withLock {
                activeInvocation?.job?.isCompleted == false
            }
            if (alreadyRunning) {
                return@withLock reject(
                    command,
                    CommandDecision(
                        outcome = CommandOutcome.REJECTED_INVALID,
                        reason = CommandRejectionReason.INVALID_PHASE,
                        message = "Another pipeline command is still running"
                    )
                )
            }

            val context = PipelineExecutionContext(command)
            val execution = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                executor.execute(context, PipelineExecutionPort { event -> publish(context, event) })
            }
            stateMutex.withLock { activeInvocation = ActiveInvocation(context, execution) }
            execution.start()

            try {
                val result = execution.await()
                verifyResultIdentity(context, result)
                acknowledge(command, result.message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                publishFailure(context, failure)
                acknowledge(command, failure.message)
            } finally {
                stateMutex.withLock {
                    if (activeInvocation?.job == execution) activeInvocation = null
                }
            }
        }

    private suspend fun dispatchCancellation(command: CancelPipeline): PipelineCommandReceipt {
        val decision = stateMutex.withLock { PipelineCommandGuards.check(_snapshot.value, command) }
        if (!decision.accepted) return reject(command, decision)

        val context = PipelineExecutionContext(command)
        val result = try {
            executor.requestCancellation(
                context,
                PipelineExecutionPort { event -> publish(context, event) }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            publishFailure(context, failure)
            return acknowledge(command, failure.message)
        }
        verifyResultIdentity(context, result)

        val inFlight = stateMutex.withLock {
            activeInvocation?.takeIf { it.context.runId == command.runId && !it.job.isCompleted }
        }
        inFlight?.job?.await()
        return acknowledge(command, result.message)
    }

    private suspend fun acknowledge(command: PipelineCommand, message: String?): PipelineCommandReceipt {
        val current = stateMutex.withLock { _snapshot.value }
        val evidence = PipelineCommandEvidence(
            commandId = command.commandId,
            commandRunId = command.runId,
            snapshotRunId = current.runId,
            expectedRevision = command.precondition?.expectedRevision,
            actualRevision = current.manifestRevision,
            outcome = CommandOutcome.ACCEPTED,
            kind = PipelineCommandEvidenceKind.ACCEPTED,
            message = message
        )
        recordEvidence(evidence)
        return PipelineCommandReceipt(
            commandId = command.commandId,
            runId = command.runId,
            outcome = CommandOutcome.ACCEPTED,
            revision = current.manifestRevision,
            message = message
        )
    }

    private suspend fun reject(
        command: PipelineCommand,
        decision: CommandDecision
    ): PipelineCommandReceipt {
        val current = stateMutex.withLock { _snapshot.value }
        recordEvidence(
            PipelineCommandEvidence(
                commandId = command.commandId,
                commandRunId = command.runId,
                snapshotRunId = current.runId,
                expectedRevision = command.precondition?.expectedRevision,
                actualRevision = current.manifestRevision,
                outcome = decision.outcome,
                kind = PipelineCommandEvidenceKind.REJECTED,
                reason = decision.reason,
                message = decision.message
            )
        )

        // A stale command against an active run can carry valid pipeline event identity. This
        // gives both headed and headless consumers durable state evidence. A run-less snapshot has
        // no legal PipelineEvent identity, so commandEvidence remains the evidence in that case.
        if (decision.outcome == CommandOutcome.REJECTED_STALE && current.runId != null) {
            reduceEvent(
                ErrorRaised(
                    runId = current.runId,
                    manifestRevision = current.manifestRevision,
                    error = PipelineError(
                        code = "STALE_COMMAND",
                        message = "command=${command.commandId.value}: ${decision.message ?: "stale command"}",
                        phase = current.phase,
                        retryable = true
                    )
                )
            )
        }
        return PipelineCommandReceipt(
            commandId = command.commandId,
            runId = command.runId,
            outcome = decision.outcome,
            revision = current.manifestRevision,
            message = decision.message
        )
    }

    private suspend fun publish(context: PipelineExecutionContext, event: PipelineEvent) {
        if (event.runId != context.runId) return
        reduceEvent(event)
    }

    private suspend fun recordEvidence(evidence: PipelineCommandEvidence) {
        _lastCommandEvidence.value = evidence
        _commandEvidence.emit(evidence)
    }

    private suspend fun reduceEvent(event: PipelineEvent) {
        val accepted = stateMutex.withLock {
            val current = _snapshot.value
            if (!isAcceptedEvent(current, event)) {
                false
            } else {
                val base = if (
                    event is RunStarted &&
                    current.runId != null &&
                    current.runId != event.runId &&
                    current.phase in terminalPhases
                ) {
                    PipelineSnapshot()
                } else {
                    current
                }
                _snapshot.value = base.apply(event)
                true
            }
        }
        if (accepted) _events.emit(event)
    }

    private fun isAcceptedEvent(
        current: PipelineSnapshot,
        event: PipelineEvent
    ): Boolean {
        if (event is RunStarted) {
            val compatibleRun = current.runId == null || current.runId == event.runId || current.phase in terminalPhases
            return compatibleRun && event.manifestRevision.value >= current.manifestRevision.value
        }
        return current.runId == event.runId && event.manifestRevision.value >= current.manifestRevision.value
    }

    private suspend fun publishFailure(context: PipelineExecutionContext, failure: Throwable) {
        val current = stateMutex.withLock { _snapshot.value }
        if (current.runId != context.runId) return
        val revision = current.manifestRevision
        publish(
            context,
            ErrorRaised(
                runId = context.runId,
                manifestRevision = revision,
                error = PipelineError(
                    code = "EXECUTOR_FAILURE",
                    message = failure.message ?: failure::class.simpleName.orEmpty(),
                    phase = current.phase,
                    retryable = true
                )
            )
        )
        if (current.phase !in terminalPhases) {
            publish(
                context,
                RunFinished(
                    runId = context.runId,
                    manifestRevision = revision,
                    phase = PipelinePhase.FAILED,
                    message = failure.message
                )
            )
        }
    }

    private fun verifyResultIdentity(context: PipelineExecutionContext, result: PipelineExecutionResult) {
        check(result.commandId == context.commandId) {
            "Executor returned command ${result.commandId.value} for ${context.commandId.value}"
        }
        check(result.runId == context.runId) {
            "Executor returned run ${result.runId.value} for ${context.runId.value}"
        }
    }

    override fun close() {
        scope.coroutineContext[Job]?.cancel()
    }

    private companion object {
        val terminalPhases = setOf(
            PipelinePhase.COMPLETED,
            PipelinePhase.FAILED,
            PipelinePhase.CANCELLED
        )
    }
}
