package com.qwen.tts.studio.agent

import androidx.lifecycle.ViewModelStore
import com.qwen.tts.studio.batch.BatchAudioStore
import com.qwen.tts.studio.batch.BatchEngine
import com.qwen.tts.studio.batch.BatchGenerationRequest
import com.qwen.tts.studio.batch.BatchGenerationStatus
import com.qwen.tts.studio.batch.BatchStreamingResult
import com.qwen.tts.studio.batch.BatchVoiceMode
import com.qwen.tts.studio.batch.BatchVoiceParameters
import com.qwen.tts.studio.batch.BatchValidationReport
import com.qwen.tts.studio.batch.QwenBatchEngine
import com.qwen.tts.studio.batch.TextBatching
import com.qwen.tts.studio.engine.NativeBackendPreference
import com.qwen.tts.studio.engine.QwenEngine
import com.qwen.tts.studio.viewmodel.SettingsViewModel
import com.qwen.tts.studio.viewmodel.BatchIncrementalValidationSettings
import com.qwen.tts.studio.viewmodel.StudioBatchUiState
import com.qwen.tts.studio.viewmodel.StudioViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlin.math.max
import kotlin.system.exitProcess

enum class AgentSmokeMode(val id: String) {
    Fake("fake"),
    Native("native");

    companion object {
        fun parse(value: String): AgentSmokeMode = entries.firstOrNull { it.id.equals(value, ignoreCase = true) }
            ?: error("Unknown batch verification mode '$value'. Expected fake or native.")
    }
}

data class AgentSmokeOptions(
    val mode: AgentSmokeMode = AgentSmokeMode.Fake,
    val outputDirectory: Path = defaultAgentOutputDirectory(),
    val modelDirectory: Path? = null,
    val modelName: String? = null,
    val backend: NativeBackendPreference = NativeBackendPreference.Cpu,
    val textFile: Path? = null,
    val texts: List<String>? = null,
    val speaker: String? = null,
    val instruction: String? = null,
    /** Language name/code passed through to the native synthesis request. */
    val language: String? = null,
    val maxChunks: Int? = null,
    val chunkCharacters: Int? = null,
    val skipAsr: Boolean = false,
    /** Validates and publishes each chunk before the next chunk is admitted. */
    val sequentialValidation: Boolean = false,
    /** Number of in-place regeneration attempts after a sequential validation failure. */
    val validationRetries: Int = 1,
    val flatOutput: Boolean = false,
    /** Loads an existing manifest and invokes the BatchScreen Resume action. */
    val resume: Boolean = false,
    /** Loads an existing manifest and validates its durable WAVs without TTS resume. */
    val validateManifestOnly: Boolean = false,
    /** Loads an existing manifest and invokes the BatchScreen row regenerate action for these indexes. */
    val regenerateChunks: Set<Int> = emptySet(),
    /** Splits failed manifest chunks in-place before invoking the BatchScreen Resume action. */
    val rechunkFailed: Boolean = false,
    val rechunkCharacters: Int = TextBatching.DEFAULT_RECHUNK_CHARACTERS,
    /** Explicit manifest control-plane path; when present, replay never re-chunks source text. */
    val manifestPath: Path? = null,
    /** Whether the backend value was explicitly supplied by the caller rather than read from a manifest. */
    val backendExplicit: Boolean = false
)

data class AgentValidationFinding(
    val severity: String,
    val code: String,
    val chunkIndex: Int?,
    val message: String
)

data class AgentStateSnapshot(
    val completed: Int,
    val generated: Int,
    val total: Int,
    val currentIndex: Int,
    val savingChunkIndex: Int?,
    val isRunning: Boolean,
    val isRecombining: Boolean,
    val statusMessage: String?,
    val error: String?,
    val validationPassed: Boolean?,
    val resultStatus: String?,
    val combinedFile: String?
)

data class AgentEngineEvidence(
    val requestedMode: String,
    val executionMode: String?,
    val reusableSession: Boolean?,
    val loadCalls: Int,
    val generationCalls: Int,
    val generatedTexts: List<String>,
    val lastLoadSuccess: Boolean?,
    val lastLoadError: String?
)

data class AgentAsrEvidence(
    val backend: String?,
    val nativeName: String?,
    val gpuActive: Boolean?,
    val encoderWeightsOnGpu: Boolean?,
    val decoderWeightsOnGpu: Boolean?,
    val deviceFreeBytes: Long?,
    val deviceTotalBytes: Long?,
    val windowCount: Int,
    val chunkCount: Int,
    val totalElapsedMillis: Long
) {
    companion object {
        fun from(report: BatchValidationReport?): AgentAsrEvidence? {
            if (report == null || (report.asrMetrics == null && report.asrBackendEvidence == null)) return null
            val metrics = report.asrMetrics
            val evidence = report.asrBackendEvidence
            return AgentAsrEvidence(
                backend = evidence?.backend?.name ?: metrics?.backend?.name,
                nativeName = evidence?.nativeName,
                gpuActive = evidence?.gpuActive,
                encoderWeightsOnGpu = evidence?.encoderWeightsOnGpu,
                decoderWeightsOnGpu = evidence?.decoderWeightsOnGpu,
                deviceFreeBytes = evidence?.deviceFreeBytes,
                deviceTotalBytes = evidence?.deviceTotalBytes,
                windowCount = metrics?.windowCount ?: 0,
                chunkCount = metrics?.chunkCount ?: 0,
                totalElapsedMillis = metrics?.totalElapsedMillis ?: 0L
            )
        }
    }
}

data class AgentRunReport(
    val schemaVersion: Int,
    val status: String,
    val startedAt: String,
    val finishedAt: String,
    val durationMillis: Long,
    val mode: String,
    val requestedModelName: String?,
    val requestedSpeaker: String?,
    val requestedInstruction: String?,
    val asrValidationSkipped: Boolean,
    val sequentialValidation: Boolean,
    val manifestValidationOnly: Boolean = false,
    val validationRetries: Int,
    val outputDirectory: String,
    val inputFile: String?,
    val manifestFile: String?,
    val combinedFile: String?,
    val expectedChunkCount: Int,
    val completedChunkCount: Int,
    val validationPassed: Boolean,
    val validationFindings: List<AgentValidationFinding>,
    val stateEvents: List<AgentStateSnapshot>,
    val uiActions: List<AgentUiActionEvidence>,
    val engine: AgentEngineEvidence,
    val asrEvidence: AgentAsrEvidence? = null,
    val telemetry: AgentTelemetrySnapshot,
    val error: String?
) {
    val passed: Boolean get() = status == "passed"

    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("  \"schemaVersion\": $schemaVersion,")
        appendLine("  \"status\": ${jsonString(status)},")
        appendLine("  \"startedAt\": ${jsonString(startedAt)},")
        appendLine("  \"finishedAt\": ${jsonString(finishedAt)},")
        appendLine("  \"durationMillis\": $durationMillis,")
        appendLine("  \"mode\": ${jsonString(mode)},")
        appendLine("  \"requestedModelName\": ${jsonString(requestedModelName)},")
        appendLine("  \"requestedSpeaker\": ${jsonString(requestedSpeaker)},")
        appendLine("  \"requestedInstruction\": ${jsonString(requestedInstruction)},")
        appendLine("  \"asrValidationSkipped\": $asrValidationSkipped,")
        appendLine("  \"sequentialValidation\": $sequentialValidation,")
        appendLine("  \"manifestValidationOnly\": $manifestValidationOnly,")
        appendLine("  \"validationRetries\": $validationRetries,")
        appendLine("  \"outputDirectory\": ${jsonString(outputDirectory)},")
        appendLine("  \"inputFile\": ${jsonString(inputFile)},")
        appendLine("  \"manifestFile\": ${jsonString(manifestFile)},")
        appendLine("  \"combinedFile\": ${jsonString(combinedFile)},")
        appendLine("  \"expectedChunkCount\": $expectedChunkCount,")
        appendLine("  \"completedChunkCount\": $completedChunkCount,")
        appendLine("  \"validationPassed\": $validationPassed,")
        appendLine("  \"validationFindings\": ${validationFindingsJson(validationFindings)},")
        appendLine("  \"stateEvents\": ${stateEventsJson(stateEvents)},")
        appendLine("  \"uiActions\": ${uiActionsJson(uiActions)},")
        appendLine("  \"engine\": {")
        appendLine("    \"requestedMode\": ${jsonString(engine.requestedMode)},")
        appendLine("    \"executionMode\": ${jsonString(engine.executionMode)},")
        appendLine("    \"reusableSession\": ${jsonBoolean(engine.reusableSession)},")
        appendLine("    \"loadCalls\": ${engine.loadCalls},")
        appendLine("    \"generationCalls\": ${engine.generationCalls},")
        appendLine("    \"generatedTexts\": ${stringsJson(engine.generatedTexts)},")
        appendLine("    \"lastLoadSuccess\": ${jsonBoolean(engine.lastLoadSuccess)},")
        appendLine("    \"lastLoadError\": ${jsonString(engine.lastLoadError)}")
        appendLine("  },")
        appendLine("  \"asrEvidence\": ${asrEvidenceJson(asrEvidence)},")
        appendLine("  \"telemetry\": ${telemetry.toJson()},")
        appendLine("  \"error\": ${jsonString(error)}")
        appendLine("}")
    }
}

/**
 * Runs the same batch action, validation action, and recombination action that
 * the desktop UI exposes, without creating a Compose window. The result is a
 * durable report so an agent can make a pass/fail decision from files rather
 * than interpreting terminal logs or screenshots.
 */
object HeadlessBatchVerificationRunner {
    private const val REPORT_FILE_NAME = "batch-verification-report.json"
    private const val LEGACY_REPORT_FILE_NAME = "agent-report.json"
    private const val INPUT_FILE_NAME = "agent-input.txt"
    private const val BATCH_DIRECTORY_NAME = "batch"
    private const val COMBINED_FILE_NAME = "combined.wav"
    private const val DEFAULT_MODEL_NAME = "agent-fake.gguf"
    private const val MAX_STATE_EVENTS = 256

    fun run(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
        return try {
            val parsed = parseArgs(args)
            if (parsed == null) {
                out.println(usage())
                0
            } else {
                val report = execute(parsed)
                val reportPath = parsed.outputDirectory.toAbsolutePath().normalize().resolve(REPORT_FILE_NAME)
                val legacyReportPath = reportPath.resolveSibling(LEGACY_REPORT_FILE_NAME)
                Files.createDirectories(reportPath.parent)
                Files.writeString(reportPath, report.toJson(), StandardCharsets.UTF_8)
                if (legacyReportPath != reportPath) {
                    Files.writeString(legacyReportPath, report.toJson(), StandardCharsets.UTF_8)
                }
                out.println("BATCH_VERIFICATION_STATUS=${report.status}")
                out.println("BATCH_VERIFICATION_REPORT=${reportPath}")
                report.manifestFile?.let { out.println("BATCH_VERIFICATION_MANIFEST=$it") }
                report.combinedFile?.let { out.println("BATCH_VERIFICATION_COMBINED=$it") }
                // Preserve the old machine-readable keys for existing scripts.
                out.println("AGENT_STATUS=${report.status}")
                out.println("AGENT_REPORT=${legacyReportPath}")
                report.manifestFile?.let { out.println("AGENT_MANIFEST=$it") }
                report.combinedFile?.let { out.println("AGENT_COMBINED=$it") }
                if (!report.passed && report.error != null) {
                    err.println("BATCH_VERIFICATION_ERROR=${report.error}")
                    err.println("AGENT_ERROR=${report.error}")
                }
                if (report.passed) 0 else 1
            }
        } catch (error: Throwable) {
            err.println("AGENT_ERROR=${error.message ?: error::class.simpleName}")
            2
        }
    }

    fun execute(options: AgentSmokeOptions): AgentRunReport {
        val started = Instant.now()
        val telemetry = AgentTelemetry()
        telemetry.sample("run-start")
        val outputDirectory = options.outputDirectory.toAbsolutePath().normalize()
        var inputFile: Path? = null
        var manifestFile: Path? = null
        var combinedFile: Path? = null
        var expectedChunkCount = 0
        var completedChunkCount = 0
        var initialCompletedChunkCount = 0
        var validationPassed = false
        var validationFindings = emptyList<AgentValidationFinding>()
        val stateEvents = Collections.synchronizedList(mutableListOf<AgentStateSnapshot>())
        val uiActions = Collections.synchronizedList(mutableListOf<AgentUiActionEvidence>())
        var recorder: RecordingBatchEngine? = null
        var workflowError: String? = null
        var finalState: StudioBatchUiState? = null
        var reportModelName: String? = options.modelName
        var reportSpeaker: String? = options.speaker
        var reportInstruction: String? = options.instruction

        try {
            require(!options.rechunkFailed || options.resume) {
                "Failed-chunk rechunking requires manifest replay with resume enabled."
            }
            require(!options.rechunkFailed || options.manifestPath != null) {
                "Failed-chunk rechunking requires --manifest so the existing manifest remains the control plane."
            }
            require(!options.rechunkFailed || options.regenerateChunks.isEmpty()) {
                "Failed-chunk rechunking cannot be combined with selected chunk regeneration."
            }
            require(options.rechunkCharacters > 0) { "rechunkCharacters must be positive." }
            Files.createDirectories(outputDirectory)
            val replayManifestPath = if (options.resume) {
                options.manifestPath?.toAbsolutePath()?.normalize()
                    ?: outputDirectory.resolve("manifest.json")
            } else {
                null
            }
            val replayManifest = replayManifestPath?.let { path ->
                require(Files.isRegularFile(path)) {
                    "Manifest-first replay requires an existing manifest: $path"
                }
                BatchAudioStore(path.parent).loadManifest(path)
            }
            if (replayManifest != null) {
                require(options.maxChunks == null) {
                    "--max-chunks cannot be used when the manifest is the control plane."
                }
                require(options.chunkCharacters == null) {
                    "--chunk-characters cannot be used when the manifest is the control plane."
                }
                require(outputDirectory == replayManifestPath.parent) {
                    "Manifest-first replay output must be the manifest directory: ${replayManifestPath.parent}"
                }
            }
            val source = if (replayManifest != null) null else loadSource(options)
            // When the run consumes a complete source file, pass that exact
            // file into the BatchScreen validation action so the durable
            // manifest also proves source round-trip conservation. A bounded
            // --max-chunks run intentionally validates only its selected
            // subset and must not compare it with the full source file.
            val validationSourceFile = options.textFile?.let { sourcePath ->
                val normalized = sourcePath.toAbsolutePath().normalize()
                require(Files.isRegularFile(normalized)) { "Text file does not exist: $normalized" }
                normalized.toFile()
            }?.takeIf { options.maxChunks == null }
            val texts = replayManifest?.chunks
                ?.sortedBy { it.index }
                ?.map { it.text }
                ?: run {
                    val sourcePlan = source ?: error("Manifest/source input was not available.")
                    options.maxChunks?.let { limit ->
                        require(limit > 0) { "--max-chunks must be greater than zero." }
                        sourcePlan.texts.take(limit)
                    } ?: sourcePlan.texts
                }
            require(texts.isNotEmpty()) { "Manifest/source input produced no non-blank chunks." }
            if (replayManifest == null) {
                val sourcePlan = source ?: error("Source input was not available.")
                val inputText = if (options.maxChunks == null) sourcePlan.text else texts.joinToString("")
                val writtenInputFile = outputDirectory.resolve(INPUT_FILE_NAME)
                inputFile = writtenInputFile
                Files.writeString(writtenInputFile, inputText, StandardCharsets.UTF_8)
            }
            expectedChunkCount = texts.size

            val persistedMetadata = replayManifest?.metadata.orEmpty()
            val persistedBackend = persistedMetadata["backendPreference"]
                ?.let(NativeBackendPreference::fromId)
            val effectiveBackend = if (replayManifest != null && !options.backendExplicit) {
                persistedBackend ?: options.backend
            } else {
                options.backend
            }
            val persistedModelDirectory = persistedMetadata["modelDir"]
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it).toAbsolutePath().normalize() }
            val modelDirectory = options.modelDirectory?.toAbsolutePath()?.normalize()
                ?: persistedModelDirectory
                ?: outputDirectory.resolve("fake-model").also { Files.createDirectories(it) }
            if (replayManifest != null) {
                options.modelDirectory?.let { supplied ->
                    require(supplied.toAbsolutePath().normalize() == persistedModelDirectory) {
                        "Manifest is authoritative for modelDir; supplied model directory does not match it."
                    }
                }
                if (options.backendExplicit) {
                    require(persistedBackend == null || persistedBackend == options.backend) {
                        "Manifest is authoritative for backendPreference; supplied backend does not match it."
                    }
                }
            }
            val requestedModelName = options.modelName
                ?: persistedMetadata["modelName"]?.takeIf { it.isNotBlank() }
                ?: if (options.mode == AgentSmokeMode.Fake) DEFAULT_MODEL_NAME else null
            if (replayManifest != null) {
                options.modelName?.let { supplied ->
                    require(supplied == requestedModelName) {
                        "Manifest is authoritative for modelName; supplied model name does not match it."
                    }
                }
            }
            val requestedSpeaker = (options.speaker
                ?: persistedMetadata["speaker"])
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
            val requestedInstruction = (options.instruction
                ?: persistedMetadata["instruction"])
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val persistedLanguageId = persistedMetadata["languageId"]?.toIntOrNull()
            val requestedLanguage = options.language
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "English"
            val requestedLanguageId = options.language?.let { supplied ->
                QwenEngine.mapLanguageToId(supplied)
            } ?: persistedLanguageId ?: QwenEngine.mapLanguageToId(requestedLanguage)
            if (replayManifest != null) {
                options.speaker?.let { supplied ->
                    require(supplied.equals(requestedSpeaker, ignoreCase = true)) {
                        "Manifest is authoritative for speaker; supplied speaker does not match it."
                    }
                }
                options.instruction?.let { supplied ->
                    require(supplied.trim() == requestedInstruction) {
                        "Manifest is authoritative for instruction; supplied instruction does not match it."
                    }
                }
                options.language?.let { supplied ->
                    require(QwenEngine.mapLanguageToId(supplied) == persistedLanguageId) {
                        "Manifest is authoritative for language; supplied language does not match it."
                    }
                }
            }
            reportModelName = requestedModelName
            reportSpeaker = if (replayManifest == null) options.speaker else requestedSpeaker
            reportInstruction = requestedInstruction
            if (options.mode == AgentSmokeMode.Native) {
                require(Files.isDirectory(modelDirectory)) {
                    "Native mode requires an existing model directory: $modelDirectory"
                }
            }

            val batchDirectory = replayManifestPath?.parent
                ?: if (options.flatOutput) outputDirectory else outputDirectory.resolve(BATCH_DIRECTORY_NAME)
            Files.createDirectories(batchDirectory)
            val chunkVoices = replayManifest?.chunks?.associate { chunk ->
                chunk.index to BatchVoiceParameters(
                    name = chunk.voiceName ?: requestedSpeaker ?: "Default Voice",
                    modelName = chunk.modelName ?: requestedModelName,
                    voicePrompt = chunk.voicePrompt ?: requestedInstruction,
                    speaker = chunk.voiceName
                )
            } ?: requestedSpeaker?.let { speaker ->
                texts.indices.associateWith { index ->
                    BatchVoiceParameters(
                        name = speaker,
                        modelName = requestedModelName,
                        voicePrompt = requestedInstruction,
                        speaker = speaker
                    )
                }
            } ?: emptyMap()
            val asrModelFile = if (options.mode == AgentSmokeMode.Native && !options.skipAsr) {
                val persistedAsrPath = replayManifest?.metadata?.get("asrModelFilePath")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Path.of(it) }
                (persistedAsrPath ?: modelDirectory.resolve(SettingsViewModel.ASR_MODEL_NAME))
                    .takeIf(Files::isRegularFile)
                    ?.toFile()
            } else {
                null
            }
            val incrementalValidation = if (options.sequentialValidation) {
                require(options.mode != AgentSmokeMode.Native || options.skipAsr || asrModelFile != null) {
                    "Sequential native validation requires the installed ASR model unless --skip-asr is supplied."
                }
                BatchIncrementalValidationSettings(
                    asrModelFile = asrModelFile,
                    asrBackend = effectiveBackend,
                    maxRetries = options.validationRetries
                )
            } else {
                null
            }
            val request = BatchGenerationRequest(
                batchId = "headless-batch-verification-${UUID.randomUUID()}",
                modelDir = modelDirectory.toString(),
                modelName = requestedModelName,
                backendPreference = effectiveBackend,
                texts = texts,
                languageId = requestedLanguageId,
                instruction = requestedInstruction,
                speaker = requestedSpeaker,
                speakerEmbeddingPath = null,
                iclPromptPath = null,
                outputDirectory = batchDirectory,
                preserveChunkBoundaries = replayManifest != null || options.chunkCharacters != null,
                allowAdaptiveRechunking = true,
                voiceMode = requestedSpeaker?.let { BatchVoiceMode.NAMED_SPEAKER },
                defaultVoiceName = requestedSpeaker,
                chunkVoices = chunkVoices
            )

            val viewModel = StudioViewModel { engine ->
                val delegate = if (options.mode == AgentSmokeMode.Fake) {
                    DeterministicBatchEngine()
                } else {
                    QwenBatchEngine(engine)
                }
                RecordingBatchEngine(delegate, telemetry) {
                    if (options.mode == AgentSmokeMode.Fake) "Fake" else engine.executionMode().name
                }.also { recorder = it }
            }
            val ui = StudioUiInstrumentation(viewModel) { evidence -> uiActions += evidence }
            val viewModelStore = ViewModelStore()
            viewModelStore.put("headless-batch-verification", viewModel)
            try {
                runBlocking {
                    val observationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    val observationJob = observationScope.launch {
                        viewModel.batchState.collect { state ->
                            recordState(stateEvents, state)
                        }
                    }
                    try {
                        val writtenManifestFile = replayManifestPath ?: batchDirectory.resolve("manifest.json")
                        manifestFile = writtenManifestFile
                        if (options.validateManifestOnly) {
                            require(Files.isRegularFile(writtenManifestFile)) {
                                "--validate-manifest-only requires an existing manifest: $writtenManifestFile"
                            }
                            ui.loadManifest(writtenManifestFile.toFile())
                            expectedChunkCount = viewModel.batchState.value.manifest?.expectedChunkCount ?: expectedChunkCount
                            initialCompletedChunkCount = viewModel.batchState.value.manifest
                                ?.chunks
                                ?.count { it.status.name == "COMPLETE" }
                                ?: 0
                        } else if (options.resume) {
                            require(Files.isRegularFile(writtenManifestFile)) {
                                "--resume requires an existing manifest: $writtenManifestFile"
                            }
                            ui.loadManifest(writtenManifestFile.toFile())
                            expectedChunkCount = viewModel.batchState.value.manifest?.expectedChunkCount ?: expectedChunkCount
                            initialCompletedChunkCount = viewModel.batchState.value.manifest
                                ?.chunks
                                ?.count { it.status.name == "COMPLETE" }
                                ?: 0
                            if (options.rechunkFailed) {
                                ui.rechunkFailed(writtenManifestFile.toFile(), options.rechunkCharacters)
                                expectedChunkCount = viewModel.batchState.value.manifest?.expectedChunkCount ?: expectedChunkCount
                                initialCompletedChunkCount = viewModel.batchState.value.manifest
                                    ?.chunks
                                    ?.count { it.status.name == "COMPLETE" }
                                    ?: 0
                                ui.resume(incrementalValidation)
                            } else if (options.regenerateChunks.isEmpty()) {
                                ui.resume(incrementalValidation)
                            } else {
                                options.regenerateChunks.sorted().forEach { chunkIndex ->
                                    ui.regenerateChunk(chunkIndex, incrementalValidation)
                                }
                            }
                        } else {
                            ui.generate(request, incrementalValidation)
                            require(Files.isRegularFile(writtenManifestFile)) {
                                "Batch generation completed without a manifest: $writtenManifestFile"
                            }
                            ui.loadManifest(writtenManifestFile.toFile())
                            expectedChunkCount = viewModel.batchState.value.manifest?.expectedChunkCount ?: expectedChunkCount
                        }
                        if (Files.isRegularFile(writtenManifestFile)) {
                            val validationStarted = telemetry.beginPhase("validation")
                            try {
                                ui.validateAll(
                                    manifestFile = writtenManifestFile.toFile(),
                                    asrModelFile = asrModelFile,
                                    sourceFile = validationSourceFile,
                                    asrBackend = effectiveBackend,
                                    reusePersistedValidation = options.sequentialValidation
                                )
                            } finally {
                                telemetry.finishValidation(
                                    validationStarted,
                                    viewModel.batchState.value.validationReport?.passed
                                )
                            }
                            if (viewModel.batchState.value.manifest != null) {
                                val destination = outputDirectory.resolve(COMBINED_FILE_NAME)
                                ui.combine(writtenManifestFile.toFile(), destination.toFile())
                                combinedFile = destination
                            }
                        }
                    } finally {
                        recordState(stateEvents, viewModel.batchState.value)
                        observationJob.cancelAndJoin()
                        observationScope.cancel()
                    }
                }
            } finally {
                finalState = viewModel.batchState.value
                viewModelStore.clear()
            }

            val manifest = manifestFile?.takeIf { Files.isRegularFile(it) }?.let {
                runCatching { BatchAudioStore(batchDirectory).loadManifest(it) }.getOrNull()
            }
            completedChunkCount = manifest?.chunks?.count { it.status.name == "COMPLETE" } ?: 0
            val completedState = finalState
            val reportValidation = completedState.validationReport
            validationPassed = reportValidation?.passed == true &&
                manifest?.chunks?.all { it.validationPassed == true } == true
            validationFindings = reportValidation?.findings.orEmpty().map { finding ->
                AgentValidationFinding(
                    severity = finding.severity.name,
                    code = finding.code,
                    chunkIndex = finding.chunkIndex,
                    message = finding.message
                )
            }

            val engine = recorder?.evidence(options.mode.id) ?: AgentEngineEvidence(
                requestedMode = options.mode.id,
                executionMode = null,
                reusableSession = null,
                loadCalls = 0,
                generationCalls = 0,
                generatedTexts = emptyList(),
                lastLoadSuccess = null,
                lastLoadError = null
            )
            val generationCompleted = if (options.validateManifestOnly) {
                completedChunkCount == expectedChunkCount
            } else {
                stateEvents.any { it.resultStatus == BatchGenerationStatus.COMPLETED.name }
            }
            val recordedUiActions = uiActions.toList()
            val expectedUiActions = if (options.validateManifestOnly) {
                listOf("load-manifest", "validate-all", "combine")
            } else if (!options.resume) {
                listOf("generate", "load-manifest", "validate-all", "combine")
            } else {
                listOf("load-manifest") +
                    (if (options.rechunkFailed) listOf("rechunk-failed", "resume")
                    else if (options.regenerateChunks.isEmpty()) listOf("resume")
                    else options.regenerateChunks.sorted().map { "regenerate-chunk" }) +
                    listOf("validate-all", "combine")
            }
            val uiActionsPassed = recordedUiActions.map { it.action } ==
                expectedUiActions &&
                recordedUiActions.all { it.completed }
            val nativeEvidencePassed = when {
                options.validateManifestOnly -> options.skipAsr || finalState?.validationReport?.asrBackendEvidence != null
                options.mode == AgentSmokeMode.Fake -> engine.lastLoadSuccess == true && engine.loadCalls == 1 && engine.reusableSession == true
                else -> engine.lastLoadSuccess == true && engine.executionMode == "Native" && engine.reusableSession == true
            }
            // Native generation may make additional sentence-boundary retry
            // calls when a chunk approaches the audio-token ceiling. Durable
            // manifest completion is the chunk-level contract; the engine
            // evidence must therefore require at least one successful call
            // per expected chunk, not an artificial one-call-per-chunk count.
            val requiredGenerationCalls = (expectedChunkCount - initialCompletedChunkCount).coerceAtLeast(0)
            val generatedAllChunks = if (options.validateManifestOnly) {
                completedChunkCount == expectedChunkCount
            } else {
                engine.generationCalls >= requiredGenerationCalls &&
                    engine.generatedTexts.size == engine.generationCalls &&
                    engine.generatedTexts.all(String::isNotBlank)
            }
            val passed = generationCompleted &&
                completedChunkCount == expectedChunkCount &&
                validationPassed &&
                combinedFile?.let(Files::isRegularFile) == true &&
                uiActionsPassed &&
                nativeEvidencePassed &&
                generatedAllChunks
            if (!passed) {
                workflowError = completedState.error
                    ?: completedState.recombineError
                    ?: "Agent workflow did not satisfy all completion checks: " +
                    "generationCompleted=$generationCompleted, " +
                    "chunks=$completedChunkCount/$expectedChunkCount, " +
                    "validationPassed=$validationPassed, " +
                    "combined=${combinedFile?.let(Files::isRegularFile) == true}, " +
                    "uiActionsPassed=$uiActionsPassed, " +
                    "nativeEvidencePassed=$nativeEvidencePassed, " +
                    "generatedAllChunks=$generatedAllChunks."
            }
        } catch (error: Throwable) {
            workflowError = error.message ?: error::class.simpleName ?: "Unknown agent workflow error"
        }

        telemetry.sample("run-end")
        val persistedAudioDurationSeconds = manifestAudioDurationSeconds(manifestFile)
        val telemetrySnapshot = telemetry.snapshot(persistedAudioDurationSeconds)
        val finished = Instant.now()
        val engine = recorder?.evidence(options.mode.id) ?: AgentEngineEvidence(
            requestedMode = options.mode.id,
            executionMode = null,
            reusableSession = null,
            loadCalls = 0,
            generationCalls = 0,
            generatedTexts = emptyList(),
            lastLoadSuccess = null,
            lastLoadError = null
        )
        val asrEvidence = AgentAsrEvidence.from(finalState?.validationReport)
        val passed = workflowError == null
        return AgentRunReport(
            schemaVersion = 1,
            status = if (passed) "passed" else "failed",
            startedAt = started.toString(),
            finishedAt = finished.toString(),
            durationMillis = (finished.toEpochMilli() - started.toEpochMilli()).coerceAtLeast(0L),
            mode = options.mode.id,
            requestedModelName = reportModelName,
            requestedSpeaker = reportSpeaker,
            requestedInstruction = reportInstruction,
            asrValidationSkipped = options.skipAsr,
            sequentialValidation = options.sequentialValidation,
            manifestValidationOnly = options.validateManifestOnly,
            validationRetries = options.validationRetries,
            outputDirectory = outputDirectory.toString(),
            inputFile = inputFile?.toString(),
            manifestFile = manifestFile?.toString(),
            combinedFile = combinedFile?.takeIf { Files.isRegularFile(it) }?.toString(),
            expectedChunkCount = expectedChunkCount,
            completedChunkCount = completedChunkCount,
            validationPassed = validationPassed,
            validationFindings = validationFindings,
            stateEvents = stateEvents.toList(),
            uiActions = uiActions.toList(),
            engine = engine,
            asrEvidence = asrEvidence,
            telemetry = telemetrySnapshot,
            error = workflowError
        )
    }

    private fun loadSource(options: AgentSmokeOptions): SourcePlan {
        options.textFile?.let { file ->
            val normalized = file.toAbsolutePath().normalize()
            require(Files.isRegularFile(normalized)) { "Text file does not exist: $normalized" }
            val text = Files.readString(normalized, StandardCharsets.UTF_8)
            val maxCharacters = options.chunkCharacters ?:
                TextBatching.defaultProfileCharacters(options.language, options.speaker)
            require(maxCharacters > 0) { "--chunk-characters must be greater than zero." }
            return SourcePlan(text, TextBatching.packParagraphsForGeneration(text, maxCharacters))
        }
        options.texts?.let { texts ->
            require(texts.isNotEmpty() && texts.all(String::isNotBlank)) {
                "--text must be supplied at least once and may not be blank."
            }
            return SourcePlan(texts.joinToString(""), texts)
        }
        val texts = listOf(
            "Agent instrumentation chunk one.\n\n",
            "Agent instrumentation chunk two."
        )
        return SourcePlan(texts.joinToString(""), texts)
    }

    private fun recordState(events: MutableList<AgentStateSnapshot>, state: StudioBatchUiState) {
        val snapshot = AgentStateSnapshot(
            completed = state.completed,
            generated = state.generated,
            total = state.total,
            currentIndex = state.currentIndex,
            savingChunkIndex = state.savingChunkIndex,
            isRunning = state.isRunning,
            isRecombining = state.isRecombining,
            statusMessage = state.statusMessage,
            error = state.error ?: state.recombineError,
            validationPassed = state.validationReport?.passed,
            resultStatus = state.result?.status?.name,
            combinedFile = state.combinedFile?.absolutePath
        )
        synchronized(events) {
            if (events.lastOrNull() == snapshot) return
            if (events.size >= MAX_STATE_EVENTS) {
                // Keep the bounded history useful for long batches: preserve the
                // initial trace and replace the tail with the newest observation
                // so terminal COMPLETED/FAILED state cannot be evicted.
                events.removeAt(events.lastIndex)
            }
            events += snapshot
        }
    }

    private data class SourcePlan(val text: String, val texts: List<String>)

    private class DeterministicBatchEngine : BatchEngine {
        override fun loadDetailed(
            modelDir: String,
            modelName: String?,
            backendPreference: NativeBackendPreference
        ) = QwenEngine.NativeOperationResult(success = true)

        override fun supportsReusableBufferedSession() = true

        override fun modelCapabilities() = QwenEngine.NativeCapabilities(
            loaded = true,
            supportsCloning = true,
            supportsNamedSpeakers = false,
            supportsInstruction = false,
            speakerEmbeddingDim = 1024,
            modelKind = QwenEngine.MODEL_KIND_BASE,
            speakerCount = 0
        )

        override fun generateDetailed(
            text: String,
            speakerEmbeddingPath: String?,
            iclPromptPath: String?,
            languageId: Int,
            instruction: String?,
            speaker: String?,
            maxAudioTokens: Int
        ): QwenEngine.NativeResult {
            val sampleCount = max(8_000, text.length * 800)
            val samples = FloatArray(sampleCount) { index -> if (index % 80 < 40) 0.04f else -0.04f }
            return QwenEngine.NativeResult(samples, 24_000, true, null, 1L)
        }
    }

    private class RecordingBatchEngine(
        private val delegate: BatchEngine,
        private val telemetry: AgentTelemetry,
        private val executionModeProvider: () -> String
    ) : BatchEngine {
        var loadCalls: Int = 0
            private set
        var generationCalls: Int = 0
            private set
        val generatedTexts = mutableListOf<String>()
        var reusableSession: Boolean? = null
            private set
        var executionMode: String? = null
            private set
        var lastLoad: QwenEngine.NativeOperationResult? = null
            private set

        init {
            telemetry.attachBackendMemoryProvider { delegate.backendMemory() }
        }

        override fun loadDetailed(
            modelDir: String,
            modelName: String?,
            backendPreference: NativeBackendPreference
        ): QwenEngine.NativeOperationResult {
            loadCalls++
            val startedAt = telemetry.beginPhase("load")
            val result = try {
                delegate.loadDetailed(modelDir, modelName, backendPreference)
            } catch (error: Throwable) {
                telemetry.finishPhase(startedAt, "load", false)
                throw error
            }
            telemetry.finishPhase(startedAt, "load", result.success)
            lastLoad = result
            executionMode = executionModeProvider()
            return result
        }

        override fun supportsReusableBufferedSession(): Boolean = delegate.supportsReusableBufferedSession().also {
            reusableSession = it
        }

        override fun modelCapabilities() = delegate.modelCapabilities()

        override fun availableSpeakers() = delegate.availableSpeakers()

        override fun runtimeIdentity() = delegate.runtimeIdentity()

        override fun backendMemory(): QwenEngine.BackendMemory? {
            val memory = delegate.backendMemory()
            telemetry.sample("backend-memory", memory)
            return memory
        }

        override fun textTokenCount(text: String) = delegate.textTokenCount(text)

        override fun generateDetailed(
            text: String,
            speakerEmbeddingPath: String?,
            iclPromptPath: String?,
            languageId: Int,
            instruction: String?,
            speaker: String?,
            maxAudioTokens: Int
        ): QwenEngine.NativeResult {
            generationCalls++
            generatedTexts += text
            val startedAt = telemetry.beginPhase("generation-buffered")
            return try {
                delegate.generateDetailed(
                    text,
                    speakerEmbeddingPath,
                    iclPromptPath,
                    languageId,
                    instruction,
                    speaker,
                    maxAudioTokens
                ).also { result ->
                    telemetry.finishGeneration(
                        startedAtNanos = startedAt,
                        kind = "buffered",
                        text = text,
                        audioSampleCount = result.audio?.size,
                        sampleRate = result.sampleRate,
                        reportedTimeMillis = result.timeMs,
                        success = result.success
                    )
                }
            } catch (error: Throwable) {
                telemetry.finishGeneration(
                    startedAtNanos = startedAt,
                    kind = "buffered",
                    text = text,
                    audioSampleCount = null,
                    sampleRate = null,
                    reportedTimeMillis = null,
                    success = false
                )
                throw error
            }
        }

        override fun generateStreaming(
            text: String,
            speakerEmbeddingPath: String?,
            iclPromptPath: String?,
            languageId: Int,
            instruction: String?,
            speaker: String?,
            maxAudioTokens: Int
        ): BatchStreamingResult? {
            val startedAt = telemetry.beginPhase("generation-streaming")
            return try {
                val result = delegate.generateStreaming(
                    text,
                    speakerEmbeddingPath,
                    iclPromptPath,
                    languageId,
                    instruction,
                    speaker,
                    maxAudioTokens
                )
                if (result != null) {
                    generationCalls++
                    generatedTexts += text
                }
                telemetry.finishGeneration(
                    startedAtNanos = startedAt,
                    kind = "streaming",
                    text = text,
                    audioSampleCount = result?.audio?.size,
                    sampleRate = result?.sampleRate,
                    reportedTimeMillis = null,
                    success = result != null
                )
                result
            } catch (error: Throwable) {
                telemetry.finishGeneration(
                    startedAtNanos = startedAt,
                    kind = "streaming",
                    text = text,
                    audioSampleCount = null,
                    sampleRate = null,
                    reportedTimeMillis = null,
                    success = false
                )
                throw error
            }
        }

        fun evidence(requestedMode: String) = AgentEngineEvidence(
            requestedMode = requestedMode,
            executionMode = executionMode,
            reusableSession = reusableSession,
            loadCalls = loadCalls,
            generationCalls = generationCalls,
            generatedTexts = generatedTexts.toList(),
            lastLoadSuccess = lastLoad?.success,
            lastLoadError = lastLoad?.errorMsg
        )
    }

    private fun parseArgs(args: Array<String>): AgentSmokeOptions? {
        var mode = AgentSmokeMode.Fake
        var outputDirectory: Path? = null
        var manifestPath: Path? = null
        var modelDirectory: Path? = null
        var modelName: String? = null
        var backend = NativeBackendPreference.Cpu
        var backendExplicit = false
        var textFile: Path? = null
        var speaker: String? = null
        var instruction: String? = null
        var language: String? = null
        var maxChunks: Int? = null
        var chunkCharacters: Int? = null
        var skipAsr = false
        var sequentialValidation = false
        var validationRetries = 1
        var flatOutput = false
        var resume = false
        var validateManifestOnly = false
        var rechunkFailed = false
        var rechunkCharacters = TextBatching.DEFAULT_RECHUNK_CHARACTERS
        val regenerateChunks = mutableSetOf<Int>()
        val texts = mutableListOf<String>()
        var sawVerificationFlag = false
        var index = 0

        fun nextValue(option: String): String {
            require(index + 1 < args.size) { "$option requires a value." }
            index++
            return args[index]
        }

        fun addRegenerateChunks(value: String) {
            value.split(',').map(String::trim).filter(String::isNotBlank).forEach {
                regenerateChunks += it.toInt()
            }
        }

        while (index < args.size) {
            val argument = args[index]
            when {
                argument == "--help" || argument == "-h" -> return null
                argument == "--headless-batch-verify" || argument == "--agent-smoke" -> sawVerificationFlag = true
                argument == "--native" -> mode = AgentSmokeMode.Native
                argument == "--fake" -> mode = AgentSmokeMode.Fake
                argument == "--mode" -> mode = AgentSmokeMode.parse(nextValue(argument))
                argument.startsWith("--mode=") -> mode = AgentSmokeMode.parse(argument.substringAfter('='))
                argument == "--output-dir" -> outputDirectory = Path.of(nextValue(argument))
                argument.startsWith("--output-dir=") -> outputDirectory = Path.of(argument.substringAfter('='))
                argument == "--manifest" -> manifestPath = Path.of(nextValue(argument))
                argument.startsWith("--manifest=") -> manifestPath = Path.of(argument.substringAfter('='))
                argument == "--model-dir" -> modelDirectory = Path.of(nextValue(argument))
                argument.startsWith("--model-dir=") -> modelDirectory = Path.of(argument.substringAfter('='))
                argument == "--model-name" -> modelName = nextValue(argument)
                argument.startsWith("--model-name=") -> modelName = argument.substringAfter('=')
                argument == "--backend" -> {
                    backend = NativeBackendPreference.fromId(nextValue(argument))
                    backendExplicit = true
                }
                argument.startsWith("--backend=") -> {
                    backend = NativeBackendPreference.fromId(argument.substringAfter('='))
                    backendExplicit = true
                }
                argument == "--text-file" -> textFile = Path.of(nextValue(argument))
                argument.startsWith("--text-file=") -> textFile = Path.of(argument.substringAfter('='))
                argument == "--speaker" || argument == "--voice" -> speaker = nextValue(argument)
                argument.startsWith("--speaker=") -> speaker = argument.substringAfter('=')
                argument.startsWith("--voice=") -> speaker = argument.substringAfter('=')
                argument == "--instruction" || argument == "--prompt" -> instruction = nextValue(argument)
                argument.startsWith("--instruction=") -> instruction = argument.substringAfter('=')
                argument.startsWith("--prompt=") -> instruction = argument.substringAfter('=')
                argument == "--language" || argument == "--lang" -> language = nextValue(argument)
                argument.startsWith("--language=") -> language = argument.substringAfter('=')
                argument.startsWith("--lang=") -> language = argument.substringAfter('=')
                argument == "--max-chunks" -> maxChunks = nextValue(argument).toInt()
                argument.startsWith("--max-chunks=") -> maxChunks = argument.substringAfter('=').toInt()
                argument == "--chunk-characters" -> chunkCharacters = nextValue(argument).toInt()
                argument.startsWith("--chunk-characters=") -> chunkCharacters = argument.substringAfter('=').toInt()
                argument == "--skip-asr" -> skipAsr = true
                argument == "--queued-validate" || argument == "--sequential-validate" || argument == "--incremental-validate" -> sequentialValidation = true
                argument == "--validation-retries" -> validationRetries = nextValue(argument).toInt()
                argument.startsWith("--validation-retries=") -> validationRetries = argument.substringAfter('=').toInt()
                argument == "--flat-output" -> flatOutput = true
                argument == "--resume" -> resume = true
                argument == "--validate-manifest-only" -> validateManifestOnly = true
                argument == "--rechunk-failed" -> rechunkFailed = true
                argument == "--rechunk-characters" -> rechunkCharacters = nextValue(argument).toInt()
                argument.startsWith("--rechunk-characters=") -> rechunkCharacters = argument.substringAfter('=').toInt()
                argument == "--regenerate-chunks" -> addRegenerateChunks(nextValue(argument))
                argument.startsWith("--regenerate-chunks=") -> addRegenerateChunks(argument.substringAfter('='))
                argument == "--text" -> texts += nextValue(argument)
                argument.startsWith("--text=") -> texts += argument.substringAfter('=')
                else -> error("Unknown headless batch verification option '$argument'.")
            }
            index++
        }

        if (!sawVerificationFlag && args.isNotEmpty()) {
            error("The headless batch verification runner requires --headless-batch-verify.")
        }
        require(manifestPath == null || resume) {
            "--manifest requires --resume so the manifest remains the control plane."
        }
        require(!validateManifestOnly || resume) {
            "--validate-manifest-only requires --resume so the existing manifest remains the control plane."
        }
        require(!validateManifestOnly || manifestPath != null) {
            "--validate-manifest-only requires --manifest so validation cannot select an unrelated output."
        }
        require(!validateManifestOnly || regenerateChunks.isEmpty()) {
            "--validate-manifest-only cannot be combined with --regenerate-chunks."
        }
        require(!validateManifestOnly || !rechunkFailed) {
            "--validate-manifest-only cannot be combined with --rechunk-failed."
        }
        require(resume || regenerateChunks.isEmpty()) {
            "--regenerate-chunks requires --resume so the existing manifest can be loaded."
        }
        require(!rechunkFailed || resume) {
            "--rechunk-failed requires --resume so the existing manifest can be updated in-place."
        }
        require(!rechunkFailed || manifestPath != null) {
            "--rechunk-failed requires --manifest so the existing manifest remains the control plane."
        }
        require(!rechunkFailed || regenerateChunks.isEmpty()) {
            "--rechunk-failed cannot be combined with --regenerate-chunks."
        }
        require(rechunkCharacters > 0) { "--rechunk-characters must be greater than zero." }
        require(validationRetries >= 0) { "--validation-retries must not be negative." }
        val resolvedOutputDirectory = outputDirectory
            ?: manifestPath?.toAbsolutePath()?.normalize()?.parent
            ?: defaultAgentOutputDirectory()
        return AgentSmokeOptions(
            mode = mode,
            outputDirectory = resolvedOutputDirectory,
            modelDirectory = modelDirectory,
            modelName = modelName,
            backend = backend,
            textFile = textFile,
            texts = texts.takeIf { it.isNotEmpty() },
            speaker = speaker,
            instruction = instruction,
            language = language,
            maxChunks = maxChunks,
            chunkCharacters = chunkCharacters,
            skipAsr = skipAsr,
            sequentialValidation = sequentialValidation,
            validationRetries = validationRetries,
            flatOutput = flatOutput,
            resume = resume,
            validateManifestOnly = validateManifestOnly,
            regenerateChunks = regenerateChunks,
            rechunkFailed = rechunkFailed,
            rechunkCharacters = rechunkCharacters,
            manifestPath = manifestPath,
            backendExplicit = backendExplicit
        )
    }

    private fun usage(): String = """
        Qwen-TTS Studio headless batch verification

        Runs the Studio batch workflow headlessly and writes batch-verification-report.json.

        Usage:
          --headless-batch-verify [--mode fake|native] [--output-dir PATH]
                       [--model-dir PATH] [--model-name FILE] [--backend auto|cpu|cuda]
                       [--manifest PATH]
                       [--text-file PATH | --text TEXT ...]
                       [--speaker NAME|--voice NAME] [--language NAME|--lang NAME]
                       [--instruction TEXT|--prompt TEXT]
                       [--max-chunks N] [--chunk-characters N] [--skip-asr]
                       [--queued-validate] [--validation-retries N] [--flat-output] [--resume]
                       [--validate-manifest-only]
                       [--rechunk-failed] [--rechunk-characters N]
                       [--regenerate-chunks INDEX[,INDEX...]]

        Legacy compatibility alias: --agent-smoke

        Fake mode is deterministic and needs no GGUF model. Native mode requires
        the built native runtime and an existing model directory.
    """.trimIndent()
}

/**
 * Source-compatibility facade for callers that used the former runner name.
 * New code should use [HeadlessBatchVerificationRunner].
 */
object AgentSmokeRunner {
    fun run(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int =
        HeadlessBatchVerificationRunner.run(args, out, err)

    fun execute(options: AgentSmokeOptions): AgentRunReport =
        HeadlessBatchVerificationRunner.execute(options)
}

private fun defaultAgentOutputDirectory(): Path = Path.of(
    System.getProperty("java.io.tmpdir"),
    "qwen-tts-headless-batch-verification-${UUID.randomUUID()}"
)

private fun manifestAudioDurationSeconds(manifestFile: Path?): Double? = runCatching {
    val file = manifestFile?.takeIf { Files.isRegularFile(it) } ?: return@runCatching null
    val manifest = BatchAudioStore(file.toAbsolutePath().normalize().parent).loadManifest(file)
    manifest.chunks
        .filter { it.status.name == "COMPLETE" }
        .sumOf { chunk ->
            val frames = chunk.frameCount
            val sampleRate = chunk.sampleRate
            if (frames != null && frames > 0L && sampleRate != null && sampleRate > 0) {
                frames.toDouble() / sampleRate.toDouble()
            } else {
                0.0
            }
        }
}.getOrNull()?.takeIf { it > 0.0 && it.isFinite() }

private fun asrEvidenceJson(value: AgentAsrEvidence?): String = value?.let {
    "{" +
        "\"backend\":${jsonString(it.backend)}," +
        "\"nativeName\":${jsonString(it.nativeName)}," +
        "\"gpuActive\":${jsonBoolean(it.gpuActive)}," +
        "\"encoderWeightsOnGpu\":${jsonBoolean(it.encoderWeightsOnGpu)}," +
        "\"decoderWeightsOnGpu\":${jsonBoolean(it.decoderWeightsOnGpu)}," +
        "\"deviceFreeBytes\":${it.deviceFreeBytes ?: "null"}," +
        "\"deviceTotalBytes\":${it.deviceTotalBytes ?: "null"}," +
        "\"windowCount\":${it.windowCount}," +
        "\"chunkCount\":${it.chunkCount}," +
        "\"totalElapsedMillis\":${it.totalElapsedMillis}" +
        "}"
} ?: "null"

private fun validationFindingsJson(findings: List<AgentValidationFinding>): String = findings.joinToString(",", "[", "]") { finding ->
    "{\"severity\":${jsonString(finding.severity)},\"code\":${jsonString(finding.code)}," +
        "\"chunkIndex\":${finding.chunkIndex ?: "null"},\"message\":${jsonString(finding.message)}}"
}

private fun stateEventsJson(states: List<AgentStateSnapshot>): String = states.joinToString(",", "[", "]") { state ->
    "{" +
        "\"completed\":${state.completed}," +
        "\"generated\":${state.generated}," +
        "\"total\":${state.total}," +
        "\"currentIndex\":${state.currentIndex}," +
        "\"savingChunkIndex\":${state.savingChunkIndex ?: "null"}," +
        "\"isRunning\":${state.isRunning}," +
        "\"isRecombining\":${state.isRecombining}," +
        "\"statusMessage\":${jsonString(state.statusMessage)}," +
        "\"error\":${jsonString(state.error)}," +
        "\"validationPassed\":${jsonBoolean(state.validationPassed)}," +
        "\"resultStatus\":${jsonString(state.resultStatus)}," +
        "\"combinedFile\":${jsonString(state.combinedFile)}" +
        "}"
}

private fun uiActionsJson(actions: List<AgentUiActionEvidence>): String = actions.joinToString(",", "[", "]") { action ->
    "{" +
        "\"action\":${jsonString(action.action)}," +
        "\"target\":${jsonString(action.target)}," +
        "\"completed\":${action.completed}," +
        "\"before\":${uiSnapshotJson(action.before)}," +
        "\"after\":${uiSnapshotJson(action.after)}," +
        "\"error\":${jsonString(action.error)}" +
        "}"
}

private fun uiSnapshotJson(snapshot: AgentUiSnapshot): String =
    "{" +
        "\"surface\":${jsonString(snapshot.surface)}," +
        "\"manifestPath\":${jsonString(snapshot.manifestPath)}," +
        "\"expectedChunks\":${snapshot.expectedChunks}," +
        "\"completedChunks\":${snapshot.completedChunks}," +
        "\"validation\":${validationMapJson(snapshot.validation)}," +
        "\"isRunning\":${snapshot.isRunning}," +
        "\"isRecombining\":${snapshot.isRecombining}," +
        "\"statusMessage\":${jsonString(snapshot.statusMessage)}," +
        "\"error\":${jsonString(snapshot.error)}," +
        "\"combinedFile\":${jsonString(snapshot.combinedFile)}" +
        "}"

private fun validationMapJson(validation: Map<Int, Boolean?>): String =
    validation.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (index, passed) ->
        "\"$index\":${jsonBoolean(passed)}"
    }

private fun stringsJson(values: List<String>): String = values.joinToString(",", "[", "]", transform = ::jsonString)

private fun jsonBoolean(value: Boolean?): String = value?.toString() ?: "null"

private fun jsonString(value: String?): String {
    if (value == null) return "null"
    return buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}

fun main(args: Array<String>) {
    exitProcess(HeadlessBatchVerificationRunner.run(args))
}
