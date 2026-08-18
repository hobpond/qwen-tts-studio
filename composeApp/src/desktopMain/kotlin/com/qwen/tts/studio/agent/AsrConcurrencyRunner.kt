package com.qwen.tts.studio.agent

import com.qwen.tts.studio.batch.BatchAudioStore
import com.qwen.tts.studio.batch.BatchAsrValidator
import com.qwen.tts.studio.batch.BatchIdentity
import com.qwen.tts.studio.batch.BatchChunkStatus
import com.qwen.tts.studio.batch.AsrWindow
import com.qwen.tts.studio.engine.NativeBackendPreference
import com.qwen.tts.studio.engine.AsrAudioWindow
import com.qwen.tts.studio.engine.QwenAsrEngine
import com.qwen.tts.studio.engine.QwenEngine
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.system.exitProcess

/** A deliberately separate profile from the normal headless batch verification runner. */
enum class AsrConcurrencyProfile(
    val id: String,
    val defaultBackend: NativeBackendPreference,
    val engineCount: Int,
    val parallel: Boolean,
    val requiresSerialBaseline: Boolean,
    val requiresTts: Boolean,
    val activeTts: Boolean,
    val eligibleForGate: Boolean
) {
    SerialCpu1("serial-cpu-1", NativeBackendPreference.Cpu, 1, false, false, false, false, false),
    SerialCuda1("serial-cuda-1", NativeBackendPreference.Cuda, 1, false, false, false, false, false),
    ParallelCpu2("parallel-cpu-2", NativeBackendPreference.Cpu, 2, true, true, false, false, false),
    ParallelCuda2("parallel-cuda-2", NativeBackendPreference.Cuda, 2, true, true, false, false, true),
    ParallelCuda3("parallel-cuda-3", NativeBackendPreference.Cuda, 3, true, true, false, false, false),
    ReuseCuda2("reuse-cuda-2", NativeBackendPreference.Cuda, 2, true, true, false, false, true),
    TtsResidentAsr2("tts-resident-asr-2", NativeBackendPreference.Cuda, 2, true, true, true, false, true),
    TtsActiveAsr1("tts-active-asr-1", NativeBackendPreference.Cuda, 1, true, false, true, true, false);

    companion object {
        fun parse(value: String): AsrConcurrencyProfile = entries.firstOrNull {
            it.id.equals(value, ignoreCase = true)
        } ?: error("Unknown ASR concurrency profile '$value'. Expected one of: ${entries.joinToString { it.id }}")
    }
}

enum class AsrWindowSelection(val id: String) {
    Prefix("prefix"),
    Suffix("suffix"),
    Both("both");

    fun windows(): List<AsrWindow> = when (this) {
        Prefix -> listOf(AsrWindow.PREFIX)
        Suffix -> listOf(AsrWindow.SUFFIX)
        Both -> listOf(AsrWindow.PREFIX, AsrWindow.SUFFIX)
    }

    companion object {
        fun parse(value: String): AsrWindowSelection = entries.firstOrNull {
            it.id.equals(value, ignoreCase = true)
        } ?: error("Unknown ASR window selection '$value'. Expected prefix, suffix, or both.")
    }
}

data class AsrConcurrencyOptions(
    val profile: AsrConcurrencyProfile = AsrConcurrencyProfile.ParallelCuda2,
    val outputDirectory: Path = defaultAsrConcurrencyOutputDirectory(),
    val manifestPath: Path? = null,
    val asrModelPath: Path? = null,
    val backend: NativeBackendPreference? = null,
    val ttsModelDirectory: Path? = null,
    val ttsModelName: String? = null,
    val ttsText: String = "Read this short concurrency probe in a calm, clear voice.",
    val maxChunks: Int = 8,
    val warmupRounds: Int = 1,
    val measuredRounds: Int = 3,
    val threads: Int = 4,
    val maxTokens: Int = 1024,
    val timeoutMillis: Long = 120_000L,
    val windows: AsrWindowSelection = AsrWindowSelection.Both
) {
    val effectiveBackend: NativeBackendPreference get() = backend ?: profile.defaultBackend

    init {
        require(maxChunks > 0) { "maxChunks must be positive" }
        require(warmupRounds >= 0) { "warmupRounds must not be negative" }
        require(measuredRounds > 0) { "measuredRounds must be positive" }
        require(threads > 0) { "threads must be positive" }
        require(maxTokens > 0) { "maxTokens must be positive" }
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
    }
}

data class AsrConcurrencyWorkItem(
    val itemId: String,
    val chunkIndex: Int,
    val wavPath: Path,
    val expectedText: String,
    val window: AsrWindow
)

data class AsrConcurrencyMemorySample(
    val phase: String,
    val elapsedMillis: Long,
    val freeBytes: Long?,
    val totalBytes: Long?
) {
    val usedBytes: Long? get() = if (freeBytes != null && totalBytes != null) {
        (totalBytes - freeBytes).coerceAtLeast(0L)
    } else null
}

data class AsrConcurrencyEngineEvidence(
    val engineIndex: Int,
    val loadElapsedMillis: Long,
    val backend: String?,
    val gpuActive: Boolean?,
    val encoderWeightsOnGpu: Boolean?,
    val decoderWeightsOnGpu: Boolean?,
    val freeBytesAfterLoad: Long?,
    val totalBytesAfterLoad: Long?
)

data class AsrConcurrencyCallEvidence(
    val phase: String,
    val round: Int,
    val engineIndex: Int,
    val itemId: String,
    val chunkIndex: Int,
    val window: AsrWindow,
    val elapsedMillis: Long,
    val nativeElapsedMillis: Long?,
    val success: Boolean,
    val similarity: Double?,
    val baselineMatched: Boolean?,
    val transcript: String?,
    val error: String?
)

data class AsrConcurrencyPhaseEvidence(
    val phase: String,
    val elapsedMillis: Long,
    val callCount: Int,
    val successfulCalls: Int,
    val failedCalls: Int
)

data class AsrConcurrencyGateInput(
    val profile: AsrConcurrencyProfile,
    val backend: NativeBackendPreference,
    val allCallsSuccessful: Boolean,
    val baselineMismatches: Int,
    val minimumFreeBytes: Long?,
    val totalBytes: Long?,
    val serialElapsedMillis: Long?,
    val parallelElapsedMillis: Long?,
    val p95ParallelCallMillis: Long?,
    val p95SerialCallMillis: Long?,
    val memoryGrowthBytes: Long?
)

data class AsrConcurrencyGateDecision(
    val profilePassed: Boolean,
    val eligibleForConcurrentAsr: Boolean,
    val minimumFreeBytes: Long?,
    val minimumFreeRatio: Double?,
    val serialElapsedMillis: Long?,
    val parallelElapsedMillis: Long?,
    val speedupRatio: Double?,
    val p95LatencyRatio: Double?,
    val memoryGrowthBytes: Long?,
    val reasons: List<String>
)

/** Pure gate logic so safety decisions can be tested without a native model. */
object AsrConcurrencyGate {
    const val MIN_FREE_BYTES: Long = 1_610_612_736L // 1.5 GiB
    const val MIN_FREE_RATIO: Double = 0.15
    const val MIN_SPEEDUP_RATIO: Double = 1.10
    const val MAX_P95_LATENCY_RATIO: Double = 1.50
    const val MAX_MEMORY_GROWTH_RATIO: Double = 0.10
    const val MAX_MEMORY_GROWTH_BYTES: Long = 256L * 1024L * 1024L

    fun evaluate(input: AsrConcurrencyGateInput): AsrConcurrencyGateDecision {
        val reasons = mutableListOf<String>()
        val minimumFreeRatio = if (input.minimumFreeBytes != null && input.totalBytes != null && input.totalBytes > 0L) {
            input.minimumFreeBytes.toDouble() / input.totalBytes.toDouble()
        } else null
        val speedup = if (input.serialElapsedMillis != null && input.parallelElapsedMillis != null &&
            input.parallelElapsedMillis > 0L
        ) {
            input.serialElapsedMillis.toDouble() / input.parallelElapsedMillis.toDouble()
        } else null
        val p95Ratio = if (input.p95SerialCallMillis != null && input.p95ParallelCallMillis != null &&
            input.p95SerialCallMillis > 0L
        ) {
            input.p95ParallelCallMillis.toDouble() / input.p95SerialCallMillis.toDouble()
        } else null

        val memorySafe = when {
            input.backend != NativeBackendPreference.Cuda -> true
            input.minimumFreeBytes == null || input.totalBytes == null || input.totalBytes <= 0L -> {
                reasons += "CUDA device memory evidence is unavailable."
                false
            }
            input.minimumFreeBytes < MIN_FREE_BYTES -> {
                reasons += "Minimum CUDA free memory was below 1.5 GiB."
                false
            }
            minimumFreeRatio!! < MIN_FREE_RATIO -> {
                reasons += "Minimum CUDA free-memory ratio was below 15%."
                false
            }
            else -> true
        }
        if (!input.allCallsSuccessful) reasons += "At least one transcription call failed."
        if (input.baselineMismatches > 0) reasons += "Parallel transcripts differed from the serial baseline."

        val growthSafe = when {
            input.memoryGrowthBytes == null -> true
            input.totalBytes == null || input.totalBytes <= 0L -> true
            input.memoryGrowthBytes <= MAX_MEMORY_GROWTH_BYTES -> true
            input.memoryGrowthBytes.toDouble() / input.totalBytes.toDouble() <= MAX_MEMORY_GROWTH_RATIO -> true
            else -> {
                reasons += "Loaded-round device usage grew beyond the leak threshold."
                false
            }
        }
        val speedSafe = when {
            !input.profile.eligibleForGate -> true
            speedup == null -> {
                reasons += "A serial and parallel wall-clock comparison is unavailable."
                false
            }
            speedup < MIN_SPEEDUP_RATIO -> {
                reasons += "Parallel throughput did not improve by at least 10%."
                false
            }
            else -> true
        }
        val latencySafe = when {
            !input.profile.eligibleForGate || p95Ratio == null -> true
            p95Ratio > MAX_P95_LATENCY_RATIO -> {
                reasons += "Parallel p95 latency exceeded 1.5x the serial baseline."
                false
            }
            else -> true
        }

        val profilePassed = input.allCallsSuccessful && input.baselineMismatches == 0 && growthSafe && memorySafe &&
            (input.backend != NativeBackendPreference.Cuda || input.minimumFreeBytes != null)
        if (input.profile.eligibleForGate && input.backend != NativeBackendPreference.Cuda) {
            reasons += "The concurrent-ASR admission gate is CUDA-only."
        }
        val eligible = input.profile.eligibleForGate && input.backend == NativeBackendPreference.Cuda &&
            profilePassed && memorySafe && speedSafe && latencySafe
        if (eligible) reasons += "Profile met the concurrent-ASR admission gate."
        if (reasons.isEmpty()) reasons += "Profile completed without a gate decision."

        return AsrConcurrencyGateDecision(
            profilePassed = profilePassed,
            eligibleForConcurrentAsr = eligible,
            minimumFreeBytes = input.minimumFreeBytes,
            minimumFreeRatio = minimumFreeRatio,
            serialElapsedMillis = input.serialElapsedMillis,
            parallelElapsedMillis = input.parallelElapsedMillis,
            speedupRatio = speedup,
            p95LatencyRatio = p95Ratio,
            memoryGrowthBytes = input.memoryGrowthBytes,
            reasons = reasons
        )
    }
}

data class AsrConcurrencyReport(
    val schemaVersion: Int,
    val status: String,
    val profile: String,
    val startedAt: String,
    val finishedAt: String,
    val durationMillis: Long,
    val backendRequested: String,
    val engineCount: Int,
    val keepEnginesLoaded: Boolean,
    val manifestPath: String?,
    val asrModelPath: String?,
    val workloadCount: Int,
    val warmupRounds: Int,
    val measuredRounds: Int,
    val metadata: Map<String, String>,
    val engines: List<AsrConcurrencyEngineEvidence>,
    val memorySamples: List<AsrConcurrencyMemorySample>,
    val phases: List<AsrConcurrencyPhaseEvidence>,
    val calls: List<AsrConcurrencyCallEvidence>,
    val decision: AsrConcurrencyGateDecision?,
    val error: String?
) {
    val passed: Boolean get() = status == "passed"

    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("  \"schemaVersion\": $schemaVersion,")
        appendLine("  \"status\": ${asrJsonString(status)},")
        appendLine("  \"profile\": ${asrJsonString(profile)},")
        appendLine("  \"startedAt\": ${asrJsonString(startedAt)},")
        appendLine("  \"finishedAt\": ${asrJsonString(finishedAt)},")
        appendLine("  \"durationMillis\": $durationMillis,")
        appendLine("  \"backendRequested\": ${asrJsonString(backendRequested)},")
        appendLine("  \"engineCount\": $engineCount,")
        appendLine("  \"keepEnginesLoaded\": $keepEnginesLoaded,")
        appendLine("  \"manifestPath\": ${asrJsonString(manifestPath)},")
        appendLine("  \"asrModelPath\": ${asrJsonString(asrModelPath)},")
        appendLine("  \"workloadCount\": $workloadCount,")
        appendLine("  \"warmupRounds\": $warmupRounds,")
        appendLine("  \"measuredRounds\": $measuredRounds,")
        appendLine("  \"metadata\": ${asrStringMapJson(metadata)},")
        appendLine("  \"engines\": ${engines.joinToString(",", "[", "]", transform = ::engineJson)},")
        appendLine("  \"memorySamples\": ${memorySamples.joinToString(",", "[", "]", transform = ::memoryJson)},")
        appendLine("  \"phases\": ${phases.joinToString(",", "[", "]", transform = ::phaseJson)},")
        appendLine("  \"calls\": ${calls.joinToString(",", "[", "]", transform = ::callJson)},")
        appendLine("  \"decision\": ${decision?.let(::decisionJson) ?: "null"},")
        appendLine("  \"error\": ${asrJsonString(error)}")
        appendLine("}")
    }
}

internal interface AsrConcurrencyEngine : AutoCloseable {
    fun load(modelFile: File)
    fun backendInfo(): QwenAsrEngine.BackendInfo?
    fun transcribe(wav: File, window: AsrWindow, maxTokens: Int, threads: Int): QwenAsrEngine.NativeResult
    override fun close()
}

internal fun interface AsrConcurrencyEngineFactory {
    fun create(backend: NativeBackendPreference): AsrConcurrencyEngine
}

private class NativeAsrConcurrencyEngine(
    private val engine: QwenAsrEngine
) : AsrConcurrencyEngine {
    override fun load(modelFile: File) = engine.load(modelFile)
    override fun backendInfo(): QwenAsrEngine.BackendInfo? = engine.backendInfo()
    override fun transcribe(wav: File, window: AsrWindow, maxTokens: Int, threads: Int) =
        engine.transcribe(
            wav,
            if (window == AsrWindow.PREFIX) AsrAudioWindow.PREFIX else AsrAudioWindow.SUFFIX,
            maxTokens,
            threads
        )
    override fun close() = engine.close()
}

private val nativeAsrConcurrencyEngineFactory = AsrConcurrencyEngineFactory { backend ->
    NativeAsrConcurrencyEngine(QwenAsrEngine(backend))
}

/**
 * Headless ASR concurrency instrumentation. It intentionally does not share
 * an engine between workers and does not alter the normal serialized validator.
 */
object AsrConcurrencyRunner {
    private const val REPORT_FILE_NAME = "asr-concurrency-report.json"
    private const val PARTIAL_REPORT_FILE_NAME = "asr-concurrency-report.partial.json"
    private const val DEFAULT_MAX_CHUNKS = 8
    private const val DEFAULT_WARMUP_ROUNDS = 1
    private const val DEFAULT_MEASURED_ROUNDS = 3
    private const val DEFAULT_THREADS = 4
    private const val DEFAULT_MAX_TOKENS = 1024
    private const val DEFAULT_TIMEOUT_MILLIS = 120_000L

    fun run(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
        return try {
            val options = parseArgs(args)
            if (options == null) {
                out.println(usage())
                0
            } else {
                val report = execute(options)
                val output = options.outputDirectory.toAbsolutePath().normalize()
                Files.createDirectories(output)
                Files.writeString(output.resolve(REPORT_FILE_NAME), report.toJson(), StandardCharsets.UTF_8)
                out.println("ASR_CONCURRENCY_STATUS=${report.status}")
                out.println("ASR_CONCURRENCY_REPORT=${output.resolve(REPORT_FILE_NAME)}")
                if (report.error != null) err.println(report.error)
                if (report.passed) 0 else 1
            }
        } catch (error: IllegalArgumentException) {
            err.println("ASR concurrency argument error: ${error.message}")
            2
        } catch (error: Throwable) {
            err.println("ASR concurrency runner error: ${error.message ?: error::class.simpleName}")
            2
        }
    }

    fun execute(options: AsrConcurrencyOptions): AsrConcurrencyReport =
        execute(options, nativeAsrConcurrencyEngineFactory)

    internal fun execute(
        options: AsrConcurrencyOptions,
        engineFactory: AsrConcurrencyEngineFactory
    ): AsrConcurrencyReport {
        val startedInstant = Instant.now()
        val startedNanos = System.nanoTime()
        val memory = MemoryRecorder(startedNanos)
        val engines = mutableListOf<AsrConcurrencyEngine>()
        val engineEvidence = mutableListOf<AsrConcurrencyEngineEvidence>()
        val phases = mutableListOf<AsrConcurrencyPhaseEvidence>()
        val calls = ConcurrentLinkedQueue<AsrConcurrencyCallEvidence>()
        var ttsEngine: QwenEngine? = null
        var workloadCount = 0
        var metadata = linkedMapOf<String, String>()
        var status = "failed"
        var errorMessage: String? = null
        var decision: AsrConcurrencyGateDecision? = null

        try {
            val manifestPath = requireFile(options.manifestPath, "manifest")
            val asrModelPath = requireFile(options.asrModelPath, "ASR model")
            val workload = loadWorkload(manifestPath, options)
            workloadCount = workload.size
            Files.createDirectories(options.outputDirectory.toAbsolutePath().normalize())

            if (options.profile.requiresTts) {
                val ttsDirectory = requireDirectory(options.ttsModelDirectory, "TTS model directory")
                ttsEngine = QwenEngine()
                val ttsLoad = ttsEngine.loadDetailed(ttsDirectory.path, options.ttsModelName, options.effectiveBackend)
                require(ttsLoad.success && ttsEngine.executionMode() == com.qwen.tts.studio.engine.QwenEngineExecutionMode.Native) {
                    ttsLoad.errorMsg ?: "TTS resident profile could not load a native TTS engine."
                }
                metadata["ttsModelDirectory"] = ttsDirectory.absoluteFile.normalize().path
                metadata["ttsModelName"] = options.ttsModelName.orEmpty()
                metadata.putAll(
                    BatchIdentity.modelArtifactMetadata(ttsDirectory.path, options.ttsModelName)
                        .mapKeys { (key, _) -> "tts$key" }
                )
                metadata.putAll(ttsEngine.runtimeIdentity()?.let { BatchIdentity.nativeRuntimeMetadata(it, null) }.orEmpty())
                observeMemory(memory, "tts-loaded", null, ttsEngine)
            }

            observeMemory(memory, "before-asr-load", null, ttsEngine)
            repeat(options.profile.engineCount) { index ->
                val engine = engineFactory.create(options.effectiveBackend)
                val loadStarted = System.nanoTime()
                try {
                    engine.load(asrModelPath.toFile())
                } catch (loadError: Throwable) {
                    runCatching { engine.close() }
                    throw IllegalStateException("ASR engine $index failed to load: ${loadError.message}", loadError)
                }
                engines += engine
                val info = engine.backendInfo()
                requireBackendEvidence(options.effectiveBackend, index, info)
                val loadElapsed = elapsedMillis(loadStarted)
                engineEvidence += AsrConcurrencyEngineEvidence(
                    engineIndex = index,
                    loadElapsedMillis = loadElapsed,
                    backend = info?.name,
                    gpuActive = info?.gpuActive,
                    encoderWeightsOnGpu = info?.encoderWeightsOnGpu,
                    decoderWeightsOnGpu = info?.decoderWeightsOnGpu,
                    freeBytesAfterLoad = info?.freeBytes,
                    totalBytesAfterLoad = info?.totalBytes
                )
                observeMemory(memory, "asr-engine-$index-loaded", info, ttsEngine)
                if (options.effectiveBackend == NativeBackendPreference.Cuda &&
                    memory.minimumFreeBytes() != null && memory.totalBytes() != null &&
                    memory.minimumFreeBytes()!! < safetyFloor(memory.totalBytes()!!)
                ) {
                    throw SafetyStop("CUDA free memory fell below the preflight safety floor after loading ASR engine $index.")
                }
            }
            if (engines.isNotEmpty()) {
                observeMemory(memory, "asr-all-engines-loaded", engines.last().backendInfo(), ttsEngine)
            }

            metadata.putAll(BatchIdentity.auxiliaryArtifactMetadata("asrModelFile", asrModelPath.toFile()))
            metadata["manifestPath"] = manifestPath.toAbsolutePath().normalize().toString()
            metadata["profile"] = options.profile.id
            metadata["backendRequested"] = options.effectiveBackend.id
            metadata["engineCount"] = options.profile.engineCount.toString()
            metadata["keepEnginesLoaded"] = "true"
            metadata["workloadCount"] = workloadCount.toString()
            metadata["nativeRuntimeFingerprint"] = metadata["nativeRuntimeFingerprint"]
                ?: QwenEngine().runtimeIdentity()?.let { BatchIdentity.nativeRuntimeMetadata(it, null)["nativeRuntimeFingerprint"] }
                ?: ""

            val memoryBeforeCalls = memory.minimumFreeBytes()
            val baseline = mutableMapOf<String, String>()
            if (options.warmupRounds > 0) {
                val warmupPhase = runParallelRounds(
                    phaseName = "warmup",
                    engines = engines,
                    workload = workload,
                    rounds = options.warmupRounds,
                    options = options,
                    calls = calls,
                    memory = memory,
                    ttsEngine = ttsEngine,
                    baseline = null,
                    beforeStart = null
                )
                phases += warmupPhase
            }

            val serialElapsed: Long?
            if (options.profile.requiresSerialBaseline) {
                val baselinePhase = runSerialBaseline(
                    engines.first(), workload, options, calls, memory, ttsEngine, baseline
                )
                phases += baselinePhase
                serialElapsed = baselinePhase.elapsedMillis
            } else if (!options.profile.parallel) {
                val serialPhase = runSerialBaseline(
                    engines.first(), workload, options, calls, memory, ttsEngine, baseline
                )
                phases += serialPhase
                serialElapsed = serialPhase.elapsedMillis
            } else {
                serialElapsed = null
            }

            val parallelElapsed: Long?
            if (options.profile.parallel) {
                val ttsStart = if (options.profile.activeTts) CountDownLatch(1) else null
                val ttsFuture = if (ttsStart != null) {
                    Executors.newSingleThreadExecutor().let { executor ->
                        val future = executor.submit {
                            ttsStart.await(options.timeoutMillis, TimeUnit.MILLISECONDS)
                            val result = ttsEngine!!.generateDetailed(
                                text = options.ttsText,
                                languageId = 2050,
                                maxAudioTokens = 256
                            )
                            require(result.success) { result.errorMsg ?: "Active TTS probe failed." }
                        }
                        future to executor
                    }
                } else null
                val parallelPhase = try {
                    runParallelRounds(
                        phaseName = "parallel",
                        engines = engines,
                        workload = workload,
                        rounds = options.measuredRounds,
                        options = options,
                        calls = calls,
                        memory = memory,
                        ttsEngine = ttsEngine,
                        baseline = baseline.takeIf { it.isNotEmpty() },
                        beforeStart = { ttsStart?.countDown() }
                    )
                } finally {
                    if (ttsFuture != null) {
                        val (future, executor) = ttsFuture
                        var activeTtsError: Throwable? = null
                        try {
                            ttsStart?.countDown()
                            future.get(options.timeoutMillis, TimeUnit.MILLISECONDS)
                        } catch (failure: Throwable) {
                            activeTtsError = failure
                        } finally {
                            executor.shutdownNow()
                        }
                        activeTtsError?.let {
                            throw IllegalStateException("Active TTS probe failed: ${it.message}", it)
                        }
                    }
                }
                phases += parallelPhase
                parallelElapsed = parallelPhase.elapsedMillis
            } else {
                parallelElapsed = null
            }

            val sortedCalls = calls.toList().sortedWith(
                compareBy<AsrConcurrencyCallEvidence>({ phaseOrder(it.phase) }, { it.round }, { it.engineIndex }, { it.itemId })
            )
            val measuredSerialCalls = sortedCalls.filter { it.phase == "serial-baseline" || it.phase == "serial" }
            val measuredParallelCalls = sortedCalls.filter { it.phase == "parallel" }
            val memoryGrowth = memory.usedGrowthBytesAfter("asr-all-engines-loaded")
            val gateInput = AsrConcurrencyGateInput(
                profile = options.profile,
                backend = options.effectiveBackend,
                allCallsSuccessful = sortedCalls.filter { it.phase != "warmup" }.all { it.success },
                baselineMismatches = sortedCalls.count { it.baselineMatched == false },
                minimumFreeBytes = memory.minimumFreeBytes(),
                totalBytes = memory.totalBytes(),
                serialElapsedMillis = serialElapsed,
                parallelElapsedMillis = parallelElapsed,
                p95ParallelCallMillis = percentile95(measuredParallelCalls.map { it.elapsedMillis }),
                p95SerialCallMillis = percentile95(measuredSerialCalls.map { it.elapsedMillis }),
                memoryGrowthBytes = memoryGrowth
            )
            val evaluatedDecision = AsrConcurrencyGate.evaluate(gateInput)
            decision = evaluatedDecision
            status = when {
                options.effectiveBackend == NativeBackendPreference.Cuda &&
                    (memory.totalBytes() == null || memory.minimumFreeBytes() == null) -> "inconclusive"
                evaluatedDecision.profilePassed -> "passed"
                else -> "failed"
            }
        } catch (stop: SafetyStop) {
            status = "inconclusive"
            errorMessage = stop.message
        } catch (failure: Throwable) {
            status = "failed"
            errorMessage = failure.message ?: failure::class.simpleName
        } finally {
            engines.asReversed().forEach { runCatching { it.close() } }
            ttsEngine?.let { runCatching { it.release() } }
        }

        val finishedInstant = Instant.now()
        val report = AsrConcurrencyReport(
            schemaVersion = 1,
            status = status,
            profile = options.profile.id,
            startedAt = startedInstant.toString(),
            finishedAt = finishedInstant.toString(),
            durationMillis = elapsedMillis(startedNanos),
            backendRequested = options.effectiveBackend.id,
            engineCount = options.profile.engineCount,
            keepEnginesLoaded = true,
            manifestPath = options.manifestPath?.toAbsolutePath()?.normalize()?.toString(),
            asrModelPath = options.asrModelPath?.toAbsolutePath()?.normalize()?.toString(),
            workloadCount = workloadCount,
            warmupRounds = options.warmupRounds,
            measuredRounds = options.measuredRounds,
            metadata = metadata,
            engines = engineEvidence,
            memorySamples = memory.samples(),
            phases = phases,
            calls = calls.toList().sortedWith(compareBy({ phaseOrder(it.phase) }, { it.round }, { it.engineIndex }, { it.itemId })),
            decision = decision,
            error = errorMessage
        )
        runCatching {
            Files.createDirectories(options.outputDirectory.toAbsolutePath().normalize())
            Files.writeString(
                options.outputDirectory.toAbsolutePath().normalize().resolve(PARTIAL_REPORT_FILE_NAME),
                report.toJson(),
                StandardCharsets.UTF_8
            )
        }
        return report
    }

    private fun runSerialBaseline(
        engine: AsrConcurrencyEngine,
        workload: List<AsrConcurrencyWorkItem>,
        options: AsrConcurrencyOptions,
        calls: ConcurrentLinkedQueue<AsrConcurrencyCallEvidence>,
        memory: MemoryRecorder,
        ttsEngine: QwenEngine?,
        baseline: MutableMap<String, String>
    ): AsrConcurrencyPhaseEvidence {
        val started = System.nanoTime()
        var successful = 0
        var failed = 0
        repeat(options.measuredRounds) { round ->
            workload.forEach { item ->
                val evidence = transcribeOne(
                    phase = "serial-baseline",
                    round = round,
                    engineIndex = 0,
                    engine = engine,
                    item = item,
                    options = options,
                    baseline = null,
                    memory = memory,
                    ttsEngine = ttsEngine
                )
                calls += evidence
                if (evidence.success) {
                    successful++
                    if (round == 0 && evidence.transcript != null) baseline[item.itemId] = evidence.transcript
                } else failed++
            }
        }
        return AsrConcurrencyPhaseEvidence(
            phase = "serial-baseline",
            elapsedMillis = elapsedMillis(started),
            callCount = successful + failed,
            successfulCalls = successful,
            failedCalls = failed
        )
    }

    private fun runParallelRounds(
        phaseName: String,
        engines: List<AsrConcurrencyEngine>,
        workload: List<AsrConcurrencyWorkItem>,
        rounds: Int,
        options: AsrConcurrencyOptions,
        calls: ConcurrentLinkedQueue<AsrConcurrencyCallEvidence>,
        memory: MemoryRecorder,
        ttsEngine: QwenEngine?,
        baseline: Map<String, String>?,
        beforeStart: (() -> Unit)?
    ): AsrConcurrencyPhaseEvidence {
        val started = System.nanoTime()
        var successful = 0
        var failed = 0
        repeat(rounds) { round ->
            val roundCalls = ConcurrentLinkedQueue<AsrConcurrencyCallEvidence>()
            val assignments = Array(engines.size) { index ->
                workload.filterIndexed { itemIndex, _ -> itemIndex % engines.size == index }
            }
            val executor = Executors.newFixedThreadPool(engines.size)
            val ready = CountDownLatch(engines.size)
            val start = CountDownLatch(1)
            val futures = engines.mapIndexed { engineIndex, engine ->
                executor.submit {
                    ready.countDown()
                    if (!ready.await(options.timeoutMillis, TimeUnit.MILLISECONDS)) {
                        error("ASR worker $engineIndex did not reach the start barrier.")
                    }
                    require(start.await(options.timeoutMillis, TimeUnit.MILLISECONDS)) {
                        "ASR worker $engineIndex timed out at the start barrier."
                    }
                    assignments[engineIndex].forEach { item ->
                        val evidence = transcribeOne(
                            phase = phaseName,
                            round = round,
                            engineIndex = engineIndex,
                            engine = engine,
                            item = item,
                            options = options,
                            baseline = baseline,
                            memory = memory,
                            ttsEngine = ttsEngine
                        )
                        roundCalls += evidence
                    }
                }
            }
            try {
                require(ready.await(options.timeoutMillis, TimeUnit.MILLISECONDS)) {
                    "ASR workers did not reach the start barrier."
                }
                beforeStart?.invoke()
                start.countDown()
                futures.forEach { future ->
                    future.get(options.timeoutMillis, TimeUnit.MILLISECONDS)
                }
            } catch (timeout: TimeoutException) {
                futures.forEach { it.cancel(true) }
                throw IllegalStateException("ASR $phaseName round $round timed out.", timeout)
            } catch (failure: ExecutionException) {
                futures.forEach { it.cancel(true) }
                throw IllegalStateException("ASR $phaseName round $round failed: ${failure.cause?.message}", failure.cause)
            } finally {
                executor.shutdownNow()
            }
            calls.addAll(roundCalls)
            successful += roundCalls.count { it.success }
            failed += roundCalls.count { !it.success }
        }
        return AsrConcurrencyPhaseEvidence(
            phase = phaseName,
            elapsedMillis = elapsedMillis(started),
            callCount = successful + failed,
            successfulCalls = successful,
            failedCalls = failed
        )
    }

    private fun transcribeOne(
        phase: String,
        round: Int,
        engineIndex: Int,
        engine: AsrConcurrencyEngine,
        item: AsrConcurrencyWorkItem,
        options: AsrConcurrencyOptions,
        baseline: Map<String, String>?,
        memory: MemoryRecorder,
        ttsEngine: QwenEngine?
    ): AsrConcurrencyCallEvidence {
        val started = System.nanoTime()
        return try {
            val result = engine.transcribe(item.wavPath.toFile(), item.window, options.maxTokens, options.threads)
            val comparison = BatchAsrValidator.compare(item.expectedText, result.text, item.window)
            val baselineText = baseline?.get(item.itemId)
            val baselineMatched = baselineText?.let { canonicalTranscript(it) == canonicalTranscript(result.text) }
            val info = engine.backendInfo()
            memory.observe("$phase-round-$round-engine-$engineIndex-call", info, ttsEngine)
            AsrConcurrencyCallEvidence(
                phase = phase,
                round = round,
                engineIndex = engineIndex,
                itemId = item.itemId,
                chunkIndex = item.chunkIndex,
                window = item.window,
                elapsedMillis = elapsedMillis(started),
                nativeElapsedMillis = result.timeMs,
                success = result.success,
                similarity = comparison.similarity,
                baselineMatched = baselineMatched,
                transcript = result.text,
                error = result.errorMsg
            )
        } catch (failure: Throwable) {
            memory.observe("$phase-round-$round-engine-$engineIndex-error", engine.backendInfo(), ttsEngine)
            AsrConcurrencyCallEvidence(
                phase = phase,
                round = round,
                engineIndex = engineIndex,
                itemId = item.itemId,
                chunkIndex = item.chunkIndex,
                window = item.window,
                elapsedMillis = elapsedMillis(started),
                nativeElapsedMillis = null,
                success = false,
                similarity = null,
                baselineMatched = false,
                transcript = null,
                error = failure.message ?: failure::class.simpleName
            )
        }
    }

    private fun loadWorkload(manifestPath: Path, options: AsrConcurrencyOptions): List<AsrConcurrencyWorkItem> {
        val normalizedManifest = manifestPath.toAbsolutePath().normalize()
        val store = BatchAudioStore(normalizedManifest.parent)
        val manifest = store.loadManifest(normalizedManifest)
        val chunks = manifest.chunks
            .filter { it.status == BatchChunkStatus.COMPLETE }
            .sortedBy { it.index }
            .take(options.maxChunks)
        require(chunks.isNotEmpty()) { "Manifest contains no complete chunks for ASR concurrency testing." }
        return chunks.flatMap { chunk ->
            val wav = normalizedManifest.parent.resolve(chunk.fileName).normalize()
            require(wav.parent == normalizedManifest.parent && Files.isRegularFile(wav)) {
                "Manifest chunk WAV is missing or escapes the manifest directory: $wav"
            }
            options.windows.windows().map { window ->
                AsrConcurrencyWorkItem(
                    itemId = "chunk-${chunk.index}-${window.name.lowercase()}",
                    chunkIndex = chunk.index,
                    wavPath = wav,
                    expectedText = chunk.text,
                    window = window
                )
            }
        }
    }

    private fun requireBackendEvidence(
        backend: NativeBackendPreference,
        index: Int,
        info: QwenAsrEngine.BackendInfo?
    ) {
        require(info != null) { "ASR engine $index returned no backend evidence." }
        if (backend == NativeBackendPreference.Cuda) {
            require(info.gpuActive && info.encoderWeightsOnGpu && info.decoderWeightsOnGpu) {
                "ASR engine $index did not prove CUDA execution and device-resident encoder/decoder weights."
            }
        }
    }

    private fun observeMemory(
        memory: MemoryRecorder,
        phase: String,
        info: QwenAsrEngine.BackendInfo?,
        ttsEngine: QwenEngine?
    ) {
        val asrFree = info?.freeBytes
        val asrTotal = info?.totalBytes
        val ttsMemory = if (asrTotal == null) ttsEngine?.backendMemory() else null
        memory.observe(
            phase,
            asrFree ?: ttsMemory?.freeBytes,
            asrTotal ?: ttsMemory?.totalBytes
        )
    }

    private fun safetyFloor(totalBytes: Long): Long = max(
        AsrConcurrencyGate.MIN_FREE_BYTES,
        ceil(totalBytes.toDouble() * AsrConcurrencyGate.MIN_FREE_RATIO).toLong()
    )

    private class MemoryRecorder(private val startedNanos: Long) {
        private val entries = mutableListOf<AsrConcurrencyMemorySample>()

        @Synchronized
        fun observe(phase: String, info: QwenAsrEngine.BackendInfo?, ttsEngine: QwenEngine?) {
            val ttsMemory = if (info == null) ttsEngine?.backendMemory() else null
            observe(phase, info?.freeBytes ?: ttsMemory?.freeBytes, info?.totalBytes ?: ttsMemory?.totalBytes)
        }

        @Synchronized
        fun observe(phase: String, freeBytes: Long?, totalBytes: Long?) {
            entries += AsrConcurrencyMemorySample(phase, elapsedMillis(startedNanos), freeBytes, totalBytes)
        }

        @Synchronized
        fun samples(): List<AsrConcurrencyMemorySample> = entries.toList()

        @Synchronized
        fun minimumFreeBytes(): Long? = entries.mapNotNull { it.freeBytes }.minOrNull()

        @Synchronized
        fun totalBytes(): Long? = entries.mapNotNull { it.totalBytes }.maxOrNull()

        @Synchronized
        fun usedGrowthBytesAfter(anchorPhase: String): Long? {
            val anchor = entries.firstOrNull { it.phase == anchorPhase }?.usedBytes ?: return null
            val latest = entries.lastOrNull { it.usedBytes != null }?.usedBytes ?: return null
            return (latest - anchor).coerceAtLeast(0L)
        }
    }

    private class SafetyStop(message: String) : IllegalStateException(message)

    private fun parseArgs(args: Array<String>): AsrConcurrencyOptions? {
        var sawFlag = false
        var profile = AsrConcurrencyProfile.ParallelCuda2
        var outputDirectory: Path? = null
        var manifestPath: Path? = null
        var asrModelPath: Path? = null
        var backend: NativeBackendPreference? = null
        var ttsModelDirectory: Path? = null
        var ttsModelName: String? = null
        var ttsText = "Read this short concurrency probe in a calm, clear voice."
        var maxChunks = DEFAULT_MAX_CHUNKS
        var warmupRounds = DEFAULT_WARMUP_ROUNDS
        var measuredRounds = DEFAULT_MEASURED_ROUNDS
        var threads = DEFAULT_THREADS
        var maxTokens = DEFAULT_MAX_TOKENS
        var timeoutMillis = DEFAULT_TIMEOUT_MILLIS
        var windows = AsrWindowSelection.Both
        var index = 0

        fun nextValue(option: String): String {
            require(index + 1 < args.size) { "$option requires a value." }
            index++
            return args[index]
        }

        while (index < args.size) {
            val argument = args[index]
            when {
                argument == "--help" || argument == "-h" -> return null
                argument == "--agent-asr-concurrency" -> sawFlag = true
                argument == "--profile" -> profile = AsrConcurrencyProfile.parse(nextValue(argument))
                argument.startsWith("--profile=") -> profile = AsrConcurrencyProfile.parse(argument.substringAfter('='))
                argument == "--output-dir" -> outputDirectory = Path.of(nextValue(argument))
                argument.startsWith("--output-dir=") -> outputDirectory = Path.of(argument.substringAfter('='))
                argument == "--manifest" -> manifestPath = Path.of(nextValue(argument))
                argument.startsWith("--manifest=") -> manifestPath = Path.of(argument.substringAfter('='))
                argument == "--asr-model" -> asrModelPath = Path.of(nextValue(argument))
                argument.startsWith("--asr-model=") -> asrModelPath = Path.of(argument.substringAfter('='))
                argument == "--backend" -> backend = NativeBackendPreference.fromId(nextValue(argument))
                argument.startsWith("--backend=") -> backend = NativeBackendPreference.fromId(argument.substringAfter('='))
                argument == "--tts-model-dir" -> ttsModelDirectory = Path.of(nextValue(argument))
                argument.startsWith("--tts-model-dir=") -> ttsModelDirectory = Path.of(argument.substringAfter('='))
                argument == "--tts-model-name" -> ttsModelName = nextValue(argument)
                argument.startsWith("--tts-model-name=") -> ttsModelName = argument.substringAfter('=')
                argument == "--tts-text" -> ttsText = nextValue(argument)
                argument.startsWith("--tts-text=") -> ttsText = argument.substringAfter('=')
                argument == "--max-chunks" -> maxChunks = nextValue(argument).toInt()
                argument.startsWith("--max-chunks=") -> maxChunks = argument.substringAfter('=').toInt()
                argument == "--warmup-rounds" -> warmupRounds = nextValue(argument).toInt()
                argument.startsWith("--warmup-rounds=") -> warmupRounds = argument.substringAfter('=').toInt()
                argument == "--rounds" -> measuredRounds = nextValue(argument).toInt()
                argument.startsWith("--rounds=") -> measuredRounds = argument.substringAfter('=').toInt()
                argument == "--threads" -> threads = nextValue(argument).toInt()
                argument.startsWith("--threads=") -> threads = argument.substringAfter('=').toInt()
                argument == "--max-tokens" -> maxTokens = nextValue(argument).toInt()
                argument.startsWith("--max-tokens=") -> maxTokens = argument.substringAfter('=').toInt()
                argument == "--timeout-ms" -> timeoutMillis = nextValue(argument).toLong()
                argument.startsWith("--timeout-ms=") -> timeoutMillis = argument.substringAfter('=').toLong()
                argument == "--windows" -> windows = AsrWindowSelection.parse(nextValue(argument))
                argument.startsWith("--windows=") -> windows = AsrWindowSelection.parse(argument.substringAfter('='))
                else -> error("Unknown ASR concurrency option '$argument'.")
            }
            index++
        }
        require(sawFlag) { "The ASR concurrency runner requires --agent-asr-concurrency." }
        require(manifestPath != null) { "--manifest is required." }
        require(asrModelPath != null) { "--asr-model is required." }
        if (profile.requiresTts) require(ttsModelDirectory != null) { "--tts-model-dir is required for ${profile.id}." }
        return AsrConcurrencyOptions(
            profile = profile,
            outputDirectory = outputDirectory ?: defaultAsrConcurrencyOutputDirectory(),
            manifestPath = manifestPath,
            asrModelPath = asrModelPath,
            backend = backend,
            ttsModelDirectory = ttsModelDirectory,
            ttsModelName = ttsModelName,
            ttsText = ttsText,
            maxChunks = maxChunks,
            warmupRounds = warmupRounds,
            measuredRounds = measuredRounds,
            threads = threads,
            maxTokens = maxTokens,
            timeoutMillis = timeoutMillis,
            windows = windows
        )
    }

    private fun usage(): String = """
        Qwen-TTS Studio ASR concurrency runner

        Usage:
          --agent-asr-concurrency --profile PROFILE --manifest PATH --asr-model PATH
              [--output-dir PATH] [--backend cpu|cuda]
              [--tts-model-dir PATH] [--tts-model-name FILE] [--tts-text TEXT]
              [--max-chunks N] [--warmup-rounds N] [--rounds N]
              [--threads N] [--max-tokens N] [--timeout-ms N] [--windows prefix|suffix|both]

        Profiles: ${AsrConcurrencyProfile.entries.joinToString(", ") { it.id }}
    """.trimIndent()
}

private fun defaultAsrConcurrencyOutputDirectory(): Path = Path.of(
    System.getProperty("java.io.tmpdir"),
    "qwen-asr-concurrency-${UUID.randomUUID()}"
)

private fun requireFile(path: Path?, label: String): Path {
    val normalized = requireNotNull(path) { "$label path is required." }.toAbsolutePath().normalize()
    require(Files.isRegularFile(normalized)) { "$label does not exist: $normalized" }
    return normalized
}

private fun requireDirectory(path: Path?, label: String): File {
    val normalized = requireNotNull(path) { "$label path is required." }.toAbsolutePath().normalize().toFile()
    require(normalized.isDirectory) { "$label does not exist: ${normalized.absolutePath}" }
    return normalized
}

private fun elapsedMillis(startedNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startedNanos).coerceAtLeast(0L))

private fun percentile95(values: List<Long>): Long? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val index = min(sorted.lastIndex, max(0, ceil(sorted.size * 0.95).toInt() - 1))
    return sorted[index]
}

private fun phaseOrder(phase: String): Int = when (phase) {
    "warmup" -> 0
    "serial-baseline" -> 1
    "serial" -> 1
    "parallel" -> 2
    else -> 3
}

private fun canonicalTranscript(value: String): String =
    value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun engineJson(value: AsrConcurrencyEngineEvidence): String =
    "{" +
        "\"engineIndex\":${value.engineIndex}," +
        "\"loadElapsedMillis\":${value.loadElapsedMillis}," +
        "\"backend\":${asrJsonString(value.backend)}," +
        "\"gpuActive\":${asrJsonBoolean(value.gpuActive)}," +
        "\"encoderWeightsOnGpu\":${asrJsonBoolean(value.encoderWeightsOnGpu)}," +
        "\"decoderWeightsOnGpu\":${asrJsonBoolean(value.decoderWeightsOnGpu)}," +
        "\"freeBytesAfterLoad\":${value.freeBytesAfterLoad ?: "null"}," +
        "\"totalBytesAfterLoad\":${value.totalBytesAfterLoad ?: "null"}" +
        "}"

private fun memoryJson(value: AsrConcurrencyMemorySample): String =
    "{" +
        "\"phase\":${asrJsonString(value.phase)}," +
        "\"elapsedMillis\":${value.elapsedMillis}," +
        "\"freeBytes\":${value.freeBytes ?: "null"}," +
        "\"totalBytes\":${value.totalBytes ?: "null"}," +
        "\"usedBytes\":${value.usedBytes ?: "null"}" +
        "}"

private fun phaseJson(value: AsrConcurrencyPhaseEvidence): String =
    "{" +
        "\"phase\":${asrJsonString(value.phase)}," +
        "\"elapsedMillis\":${value.elapsedMillis}," +
        "\"callCount\":${value.callCount}," +
        "\"successfulCalls\":${value.successfulCalls}," +
        "\"failedCalls\":${value.failedCalls}" +
        "}"

private fun callJson(value: AsrConcurrencyCallEvidence): String =
    "{" +
        "\"phase\":${asrJsonString(value.phase)}," +
        "\"round\":${value.round}," +
        "\"engineIndex\":${value.engineIndex}," +
        "\"itemId\":${asrJsonString(value.itemId)}," +
        "\"chunkIndex\":${value.chunkIndex}," +
        "\"window\":${asrJsonString(value.window.name)}," +
        "\"elapsedMillis\":${value.elapsedMillis}," +
        "\"nativeElapsedMillis\":${value.nativeElapsedMillis ?: "null"}," +
        "\"success\":${value.success}," +
        "\"similarity\":${value.similarity?.toString() ?: "null"}," +
        "\"baselineMatched\":${asrJsonBoolean(value.baselineMatched)}," +
        "\"transcript\":${asrJsonString(value.transcript)}," +
        "\"error\":${asrJsonString(value.error)}" +
        "}"

private fun decisionJson(value: AsrConcurrencyGateDecision): String =
    "{" +
        "\"profilePassed\":${value.profilePassed}," +
        "\"eligibleForConcurrentAsr\":${value.eligibleForConcurrentAsr}," +
        "\"minimumFreeBytes\":${value.minimumFreeBytes ?: "null"}," +
        "\"minimumFreeRatio\":${value.minimumFreeRatio?.toString() ?: "null"}," +
        "\"serialElapsedMillis\":${value.serialElapsedMillis ?: "null"}," +
        "\"parallelElapsedMillis\":${value.parallelElapsedMillis ?: "null"}," +
        "\"speedupRatio\":${value.speedupRatio?.toString() ?: "null"}," +
        "\"p95LatencyRatio\":${value.p95LatencyRatio?.toString() ?: "null"}," +
        "\"memoryGrowthBytes\":${value.memoryGrowthBytes ?: "null"}," +
        "\"reasons\":${value.reasons.joinToString(",", "[", "]", transform = ::asrJsonString)}" +
        "}"

private fun asrStringMapJson(value: Map<String, String>): String =
    value.entries.sortedBy { it.key }.joinToString(",", "{", "}") {
        "${asrJsonString(it.key)}:${asrJsonString(it.value)}"
    }

private fun asrJsonBoolean(value: Boolean?): String = value?.toString() ?: "null"

private fun asrJsonString(value: String?): String {
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
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
