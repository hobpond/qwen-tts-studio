package com.qwen.tts.studio.screens

import com.qwen.tts.studio.orchestration.AudiobookPipelineAdapter
import com.qwen.tts.studio.orchestration.ChunkSelection
import com.qwen.tts.studio.orchestration.ManifestPrecondition
import com.qwen.tts.studio.orchestration.PipelineCommand
import com.qwen.tts.studio.orchestration.PipelineCommandId
import com.qwen.tts.studio.orchestration.PipelineCommandReceipt
import com.qwen.tts.studio.orchestration.PipelineEvent
import com.qwen.tts.studio.orchestration.PipelineLease
import com.qwen.tts.studio.orchestration.PipelineLeaseId
import com.qwen.tts.studio.orchestration.PipelinePhase
import com.qwen.tts.studio.orchestration.PipelineRunId
import com.qwen.tts.studio.orchestration.PipelineSnapshot
import com.qwen.tts.studio.orchestration.ManifestRevision
import com.qwen.tts.studio.orchestration.ResumePipeline
import com.qwen.tts.studio.orchestration.StartPipeline
import com.qwen.tts.studio.orchestration.SourceInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BatchPipelineBindingTest {
    @Test
    fun headedAndInstrumentationClientsShareSnapshotAndEvents() = runBlocking {
        val adapter = FakeAdapter()
        val headed = BatchPipelineBinding(adapter)
        val instrumentation = BatchPipelineBinding(adapter)
        val headedEvents = async { headed.events.take(2).toList() }
        val instrumentationEvents = async { instrumentation.events.take(2).toList() }
        try {
            val command = StartPipeline(
                PipelineCommandId("start-1"),
                PipelineRunId("run-1"),
                SourceInput("book.txt")
            )
            val headedResult = headed.dispatch(command)
            val instrumentationResult = instrumentation.dispatch(command)
            val headedObserved = headedEvents.await()
            val instrumentationObserved = instrumentationEvents.await()

            assertTrue(headedResult.accepted)
            assertTrue(instrumentationResult.accepted)
            assertEquals(headed.snapshot.value, instrumentation.snapshot.value)
            assertEquals(headedObserved, instrumentationObserved)
            assertEquals(2, headedObserved.size)
            assertEquals(PipelinePhase.COMPLETED, headedResult.snapshot.phase)
        } finally {
            headedEvents.cancel()
            instrumentationEvents.cancel()
            headed.close()
            instrumentation.close()
        }
    }

    @Test
    fun staleReceiptIsReturnedWithTheSnapshotEvidence() = runBlocking {
        val adapter = FakeAdapter()
        val binding = BatchPipelineBinding(adapter)
        try {
            val result = binding.dispatch(
                ResumePipeline(
                    PipelineCommandId("stale-1"),
                    ManifestPrecondition(
                        PipelineRunId("run-1"),
                        ManifestRevision(0),
                        PipelineLease(PipelineLeaseId("lease-1"), PipelineRunId("run-1"), ManifestRevision(0))
                    )
                )
            )
            assertTrue(result.stale)
            assertFalse(result.accepted)
            assertNotNull(result.snapshot)
            assertEquals(ManifestRevision(1), result.snapshot.manifestRevision)
        } finally {
            binding.close()
        }
    }

    @Test
    fun closingStopsEventCollectionWithoutChangingSnapshot() = runBlocking {
        val adapter = FakeAdapter()
        val binding = BatchPipelineBinding(adapter)
        val events = mutableListOf<PipelineEvent>()
        val collector = launch { binding.events.collect { events += it } }
        binding.close()
        adapter.emitCompletion()
        delay(50)
        collector.cancel()
        assertTrue(events.isEmpty())
        assertEquals(PipelinePhase.COMPLETED, binding.snapshot.value.phase)
    }

    private class FakeAdapter : AudiobookPipelineAdapter {
        private val run = PipelineRunId("run-1")
        private val lease = PipelineLease(PipelineLeaseId("lease-1"), run, ManifestRevision(0))
        override val snapshot = MutableStateFlow(PipelineSnapshot())
        override val events = MutableSharedFlow<PipelineEvent>(replay = 16, extraBufferCapacity = 16)

        override suspend fun dispatch(command: PipelineCommand): PipelineCommandReceipt {
            if (command !is StartPipeline) {
                snapshot.value = snapshot.value.copy(manifestRevision = ManifestRevision(1), runId = run)
                return PipelineCommandReceipt(command.commandId, run, com.qwen.tts.studio.orchestration.CommandOutcome.REJECTED_STALE, ManifestRevision(1), "stale")
            }
            val started = com.qwen.tts.studio.orchestration.RunStarted(run, ManifestRevision(0), emptyList(), lease = lease)
            snapshot.value = snapshot.value.apply(started)
            events.emit(started)
            val finished = com.qwen.tts.studio.orchestration.RunFinished(run, ManifestRevision(1), PipelinePhase.COMPLETED)
            snapshot.value = snapshot.value.apply(finished)
            events.emit(finished)
            return PipelineCommandReceipt(command.commandId, run, com.qwen.tts.studio.orchestration.CommandOutcome.ACCEPTED, ManifestRevision(1))
        }

        suspend fun emitCompletion() {
            val finished = com.qwen.tts.studio.orchestration.RunFinished(run, ManifestRevision(1), PipelinePhase.COMPLETED)
            snapshot.value = snapshot.value.copy(runId = run, manifestRevision = ManifestRevision(1), phase = PipelinePhase.COMPLETED)
            events.emit(finished)
        }
    }
}
