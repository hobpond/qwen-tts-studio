package com.qwen.tts.studio.screens

import com.qwen.tts.studio.orchestration.AudiobookPipelineAdapter
import com.qwen.tts.studio.orchestration.CancelPipeline
import com.qwen.tts.studio.orchestration.CombinePipeline
import com.qwen.tts.studio.orchestration.CommandOutcome
import com.qwen.tts.studio.orchestration.PipelineCommand
import com.qwen.tts.studio.orchestration.PipelineCommandReceipt
import com.qwen.tts.studio.orchestration.PipelineEvent
import com.qwen.tts.studio.orchestration.PipelineSnapshot
import com.qwen.tts.studio.orchestration.RechunkPipeline
import com.qwen.tts.studio.orchestration.RenderPipeline
import com.qwen.tts.studio.orchestration.ResumePipeline
import com.qwen.tts.studio.orchestration.RetryChunks
import com.qwen.tts.studio.orchestration.StartPipeline
import com.qwen.tts.studio.orchestration.StreamPipeline
import com.qwen.tts.studio.orchestration.ValidatePipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * UI-facing lifecycle boundary for [AudiobookPipelineAdapter].
 *
 * This type deliberately knows nothing about engines, files, navigation, or filtering. Both a
 * headed screen and an instrumentation client can use the same binding and command objects.
 */
class BatchPipelineBinding(
    private val adapter: AudiobookPipelineAdapter,
    parentScope: CoroutineScope? = null
) : AutoCloseable {
    private val scope = parentScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ownsScope = parentScope == null
    @Volatile private var closed = false

    val snapshot: StateFlow<PipelineSnapshot> = adapter.snapshot
    val events: Flow<PipelineEvent> = adapter.events.takeWhile { !closed }

    suspend fun dispatch(command: PipelineCommand): BatchPipelineDispatchResult {
        val receipt = adapter.dispatch(command)
        return BatchPipelineDispatchResult(receipt, snapshot.value)
    }

    suspend fun start(command: StartPipeline) = dispatch(command)
    suspend fun resume(command: ResumePipeline) = dispatch(command)
    suspend fun cancel(command: CancelPipeline) = dispatch(command)
    suspend fun retry(command: RetryChunks) = dispatch(command)
    suspend fun rechunk(command: RechunkPipeline) = dispatch(command)
    suspend fun validate(command: ValidatePipeline) = dispatch(command)
    suspend fun combine(command: CombinePipeline) = dispatch(command)
    suspend fun render(command: RenderPipeline) = dispatch(command)
    suspend fun stream(command: StreamPipeline) = dispatch(command)

    /** Stops event collection; the last adapter snapshot remains available for inspection. */
    fun cancelCollection() {
        closed = true
        if (ownsScope) scope.cancel()
    }

    override fun close() = cancelCollection()
}

/** Receipt plus evidence captured from the adapter's snapshot at dispatch completion. */
data class BatchPipelineDispatchResult(
    val receipt: PipelineCommandReceipt,
    val snapshot: PipelineSnapshot
) {
    val accepted: Boolean get() = receipt.outcome == CommandOutcome.ACCEPTED
    val stale: Boolean get() = receipt.outcome == CommandOutcome.REJECTED_STALE
    val invalid: Boolean get() = receipt.outcome == CommandOutcome.REJECTED_INVALID
}
