package com.qwen.tts.studio.agent

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import com.qwen.tts.studio.batch.BatchAudioStore
import com.qwen.tts.studio.batch.TextBatching
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentSmokeRunnerTest {
    @Test
    fun descriptiveHeadlessBatchVerificationCliNameIsPrimaryAndLegacyAliasStillWorks() {
        val output = Files.createTempDirectory("headless-batch-verification-cli")
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exitCode = AgentSmokeRunner.run(
            arrayOf(
                "--headless-batch-verify",
                "--fake",
                "--skip-asr",
                "--output-dir",
                output.toString()
            ),
            PrintStream(stdout),
            PrintStream(stderr)
        )

        assertEquals(0, exitCode, stderr.toString(Charsets.UTF_8))
        assertTrue(Files.isRegularFile(output.resolve("batch-verification-report.json")))
        assertTrue(Files.isRegularFile(output.resolve("agent-report.json")))
        assertTrue(stdout.toString(Charsets.UTF_8).contains("BATCH_VERIFICATION_REPORT="))

        val legacyOutput = Files.createTempDirectory("agent-smoke-compatibility")
        val legacyExitCode = AgentSmokeRunner.run(
            arrayOf(
                "--agent-smoke",
                "--fake",
                "--skip-asr",
                "--output-dir",
                legacyOutput.toString()
            ),
            PrintStream(ByteArrayOutputStream()),
            PrintStream(ByteArrayOutputStream())
        )

        assertEquals(0, legacyExitCode)
        assertTrue(Files.isRegularFile(legacyOutput.resolve("batch-verification-report.json")))
    }

    @Test
    fun fakeRunnerDrivesStudioGenerationValidationAndRecombinationToCompletion() {
        val output = Files.createTempDirectory("headless-batch-verification")
        val texts = listOf("first agent chunk\n\n", "second agent chunk")

        val report = AgentSmokeRunner.execute(
            AgentSmokeOptions(
                outputDirectory = output,
                texts = texts
            )
        )

        assertTrue(report.passed, "${report.error ?: "batch verification failed"}\n${report.toJson()}")
        assertEquals(2, report.expectedChunkCount)
        assertEquals(2, report.completedChunkCount)
        assertTrue(report.validationPassed)
        assertEquals(1, report.engine.loadCalls)
        assertEquals(2, report.engine.generationCalls)
        assertEquals(texts.map(TextBatching::cleanForSynthesis), report.engine.generatedTexts)
        assertEquals("Fake", report.engine.executionMode)
        assertTrue(report.stateEvents.any { it.isRunning })
        assertTrue(report.stateEvents.any { it.validationPassed == true })
        assertTrue(report.stateEvents.any { it.isRecombining })
        assertTrue(report.telemetry.elapsedMillis >= 0L)
        assertTrue(report.telemetry.generation.attemptedCalls >= report.engine.generationCalls)
        assertTrue(report.telemetry.generation.audioDurationSeconds != null)
        assertEquals(1, report.telemetry.validation.runs)
        assertEquals("host_physical_system_memory", report.telemetry.memory.hostPhysical.memoryDomain)
        assertEquals(
            listOf("generate", "load-manifest", "validate-all", "combine"),
            report.uiActions.map { it.action }
        )
        assertTrue(report.uiActions.all { it.completed && it.target.startsWith("BatchScreen/") })
        assertTrue(report.uiActions.last().after.combinedFile != null)
        assertTrue(report.manifestFile?.let { Files.isRegularFile(Path.of(it)) } == true)
        assertTrue(report.combinedFile?.let { Files.isRegularFile(Path.of(it)) } == true)

        val json = report.toJson()
        assertTrue(json.contains("\"status\": \"passed\""))
        assertTrue(json.contains("\"validationPassed\": true"))
        assertTrue(json.contains("\"telemetry\""))
        assertTrue(json.contains("deviceBackend"))
        assertTrue(json.contains("first agent chunk"))
    }

    @Test
    fun sequentialValidationPublishesEachFakeChunkBeforeTheFinalAudit() {
        val output = Files.createTempDirectory("headless-sequential-validation")
        val report = AgentSmokeRunner.execute(
            AgentSmokeOptions(
                outputDirectory = output,
                texts = listOf("first sequential chunk", "second sequential chunk"),
                sequentialValidation = true,
                validationRetries = 1,
                skipAsr = true,
                flatOutput = true
            )
        )

        assertTrue(report.passed, "${report.error ?: "sequential validation failed"}\n${report.toJson()}")
        assertTrue(report.sequentialValidation)
        assertEquals(1, report.validationRetries)
        val manifest = BatchAudioStore(output).loadManifest()
        assertTrue(manifest.chunks.all { it.validationPassed == true })
        assertTrue(
            report.stateEvents.any { event ->
                event.statusMessage?.contains("validated and ready to play") == true
            }
        )
    }

    @Test
    fun manifestFirstReplayDoesNotNeedSourceTextOrGenerationOverrides() {
        val output = Files.createTempDirectory("agent-manifest-first")
        val initial = AgentSmokeRunner.execute(
            AgentSmokeOptions(
                outputDirectory = output,
                texts = listOf("manifest control chunk one.", "manifest control chunk two."),
                speaker = "Vivian",
                instruction = "Read as a calm narrator.",
                flatOutput = true
            )
        )
        assertTrue(initial.passed, "${initial.error ?: "initial fake run failed"}\n${initial.toJson()}")

        val replay = AgentSmokeRunner.execute(
            AgentSmokeOptions(
                mode = AgentSmokeMode.Fake,
                outputDirectory = output,
                resume = true,
                manifestPath = output.resolve("manifest.json")
            )
        )

        assertTrue(replay.passed, "${replay.error ?: "manifest-first replay failed"}\n${replay.toJson()}")
        assertEquals(null, replay.inputFile)
        assertEquals(initial.expectedChunkCount, replay.expectedChunkCount)
        assertEquals(initial.completedChunkCount, replay.completedChunkCount)
        assertEquals("vivian", replay.requestedSpeaker)
        assertEquals("Read as a calm narrator.", replay.requestedInstruction)
        assertEquals(
            listOf("load-manifest", "resume", "validate-all", "combine"),
            replay.uiActions.map { it.action }
        )
    }

    @Test
    fun manifestReplayCanRechunkFailedChildrenBeforeResume() {
        val output = Files.createTempDirectory("agent-rechunk-replay")
        val failedText = "Failed chunk sentence one. Failed chunk sentence two. Failed chunk sentence three."
        val initial = AgentSmokeRunner.execute(
            AgentSmokeOptions(
                outputDirectory = output,
                texts = listOf("complete head", failedText, "complete tail"),
                flatOutput = true,
                skipAsr = true
            )
        )
        assertTrue(initial.passed, "${initial.error ?: "initial fake run failed"}\n${initial.toJson()}")

        val store = BatchAudioStore(output)
        store.markFailed(store.loadManifest(), 1, "ASR suffix mismatch")
        val replay = AgentSmokeRunner.execute(
            AgentSmokeOptions(
                mode = AgentSmokeMode.Fake,
                outputDirectory = output,
                resume = true,
                manifestPath = output.resolve("manifest.json"),
                rechunkFailed = true,
                rechunkCharacters = 20,
                skipAsr = true
            )
        )

        assertTrue(replay.passed, "${replay.error ?: "rechunk replay failed"}\n${replay.toJson()}")
        assertTrue(replay.expectedChunkCount > initial.expectedChunkCount)
        assertEquals(
            listOf("load-manifest", "rechunk-failed", "resume", "validate-all", "combine"),
            replay.uiActions.map { it.action }
        )
        val migrated = store.loadManifest()
        assertTrue(migrated.chunks.any { it.displayIndex == "1.1" })
        assertTrue(migrated.chunks.all { it.status.name == "COMPLETE" && it.validationPassed == true })
    }

    @Test
    fun nativeModeFailsClosedBeforeStartingWhenModelDirectoryIsMissing() {
        val output = Files.createTempDirectory("agent-native-missing")
        val missingModel = output.resolve("missing-model")

        val report = AgentSmokeRunner.execute(
            AgentSmokeOptions(
                mode = AgentSmokeMode.Native,
                outputDirectory = output,
                modelDirectory = missingModel
            )
        )

        assertTrue(!report.passed)
        assertTrue(report.error.orEmpty().contains("model directory", ignoreCase = true))
        assertEquals(0, report.engine.loadCalls)
        assertTrue(report.combinedFile == null)
    }

    @Test
    fun configuredVoicePromptAndFlatOutputArePersistedByTheInstrumentedPath() {
        val output = Files.createTempDirectory("agent-voice-config")
        val report = AgentSmokeRunner.execute(
            AgentSmokeOptions(
                outputDirectory = output,
                texts = listOf("A configured voice chunk."),
                speaker = "Vivian",
                instruction = "Read as a calm, expressive audiobook narrator.",
                skipAsr = true,
                flatOutput = true
            )
        )

        assertTrue(report.passed, "${report.error ?: "batch verification failed"}\n${report.toJson()}")
        assertEquals("Vivian", report.requestedSpeaker)
        assertEquals("Read as a calm, expressive audiobook narrator.", report.requestedInstruction)
        assertTrue(report.asrValidationSkipped)
        val manifest = BatchAudioStore(output).loadManifest()
        assertEquals("vivian", manifest.chunks.single().voiceName)
        assertEquals("Read as a calm, expressive audiobook narrator.", manifest.chunks.single().voicePrompt)
        assertEquals("named-speaker", manifest.metadata["voiceMode"])
        assertTrue(Files.isRegularFile(output.resolve("manifest.json")))
        assertTrue(Files.isRegularFile(output.resolve("combined.wav")))
    }

    @Test
    fun textFileRunProvesSourceRoundTripThroughTheInstrumentedValidationAction() {
        val output = Files.createTempDirectory("agent-source-round-trip")
        val source = output.resolve("source.txt")
        val sourceText = "First paragraph.\n\nSecond paragraph with a lossless tail."
        Files.writeString(source, sourceText)

        val report = AgentSmokeRunner.execute(
            AgentSmokeOptions(
                outputDirectory = output.resolve("run"),
                textFile = source,
                chunkCharacters = 24,
                skipAsr = true,
                flatOutput = true
            )
        )

        assertTrue(report.passed, "${report.error ?: "batch verification failed"}\n${report.toJson()}")
        assertTrue(report.validationFindings.none { it.code == "SOURCE_ROUND_TRIP_FAILED" })
        assertTrue(report.validationFindings.any { it.code == "VALID" })
    }

    @Test
    fun chineseVivianProfileDefaultsToEightyCharactersAndKeepsFortyForRecovery() {
        val output = Files.createTempDirectory("agent-chinese-vivian-profile")
        val source = output.resolve("source.txt")
        Files.writeString(source, "中".repeat(240))

        val report = AgentSmokeRunner.execute(
            AgentSmokeOptions(
                outputDirectory = output.resolve("run"),
                textFile = source,
                language = "Chinese",
                speaker = "Vivian",
                skipAsr = true,
                flatOutput = true
            )
        )

        assertTrue(report.passed, "${report.error ?: "profile run failed"}\n${report.toJson()}")
        val manifest = BatchAudioStore(output.resolve("run")).loadManifest()
        assertEquals(3, manifest.expectedChunkCount)
        assertTrue(manifest.chunks.all { it.text.length <= TextBatching.DEFAULT_CHINESE_VIVIAN_CHARACTERS })
        assertEquals(80, TextBatching.DEFAULT_CHINESE_VIVIAN_CHARACTERS)
        assertEquals(40, TextBatching.DEFAULT_RECHUNK_CHARACTERS)
    }

    @Test
    fun longFakeBatchRetainsTerminalCompletionEvidence() {
        val output = Files.createTempDirectory("headless-batch-verification-long")
        val report = AgentSmokeRunner.execute(
            AgentSmokeOptions(
                outputDirectory = output,
                texts = List(100) { index -> "long-run agent chunk $index." }
            )
        )

        assertTrue(report.passed, "${report.error ?: "batch verification failed"}\n${report.toJson()}")
        assertEquals(100, report.expectedChunkCount)
        assertEquals(100, report.completedChunkCount)
        assertTrue(report.stateEvents.any { it.resultStatus == "COMPLETED" })
        assertTrue(report.combinedFile?.let { Files.isRegularFile(Path.of(it)) } == true)
    }
}
