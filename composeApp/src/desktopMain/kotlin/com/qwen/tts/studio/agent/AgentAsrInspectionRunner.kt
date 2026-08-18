package com.qwen.tts.studio.agent

import com.qwen.tts.studio.batch.AsrBackendEvidence
import com.qwen.tts.studio.batch.BatchAsrValidator
import com.qwen.tts.studio.batch.BatchAudioStore
import com.qwen.tts.studio.batch.NativeBatchAsrTranscriber
import com.qwen.tts.studio.engine.NativeBackendPreference
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.system.exitProcess

/**
 * Headless, bounded ASR inspection for existing generated chunks.
 *
 * This is deliberately separate from the full BatchScreen validation action:
 * it lets an agent inspect exact native transcripts for a small set of chunks
 * without re-running ASR over an entire batch. It never changes the manifest.
 */
data class AgentAsrInspectionOptions(
    val manifestPath: Path,
    val asrModelPath: Path,
    val outputDirectory: Path,
    val chunkIndices: Set<Int>,
    val backend: NativeBackendPreference = NativeBackendPreference.Cpu,
    val minimumSimilarity: Double = 0.75
)

data class AgentAsrInspectionReport(
    val schemaVersion: Int,
    val status: String,
    val startedAt: String,
    val finishedAt: String,
    val manifestPath: String,
    val asrModelPath: String,
    val backend: String,
    val minimumSimilarity: Double,
    val chunkIndices: List<Int>,
    val backendEvidence: AsrBackendEvidence?,
    val modelMetadata: Map<String, String>,
    val findings: List<AgentAsrInspectionFinding>,
    val error: String?
) {
    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("  \"schemaVersion\": $schemaVersion,")
        appendLine("  \"status\": ${jsonString(status)},")
        appendLine("  \"startedAt\": ${jsonString(startedAt)},")
        appendLine("  \"finishedAt\": ${jsonString(finishedAt)},")
        appendLine("  \"manifestPath\": ${jsonString(manifestPath)},")
        appendLine("  \"asrModelPath\": ${jsonString(asrModelPath)},")
        appendLine("  \"backend\": ${jsonString(backend)},")
        appendLine("  \"minimumSimilarity\": $minimumSimilarity,")
        appendLine("  \"chunkIndices\": ${chunkIndices.joinToString(",", "[", "]")},")
        appendLine("  \"backendEvidence\": ${backendEvidenceJson(backendEvidence)},")
        appendLine("  \"modelMetadata\": ${mapJson(modelMetadata)},")
        appendLine("  \"findings\": ${findings.joinToString(",", "[", "]") { it.toJson() }},")
        appendLine("  \"error\": ${jsonString(error)}")
        appendLine("}")
    }
}

data class AgentAsrInspectionFinding(
    val chunkIndex: Int,
    val window: String,
    val expectedText: String,
    val transcript: String?,
    val similarity: Double?,
    val passed: Boolean,
    val error: String?,
    val elapsedMillis: Long?,
    val backend: String
) {
    fun toJson(): String =
        "{" +
            "\"chunkIndex\":$chunkIndex," +
            "\"window\":${jsonString(window)}," +
            "\"expectedText\":${jsonString(expectedText)}," +
            "\"transcript\":${jsonString(transcript)}," +
            "\"similarity\":${similarity ?: "null"}," +
            "\"passed\":$passed," +
            "\"error\":${jsonString(error)}," +
            "\"elapsedMillis\":${elapsedMillis ?: "null"}," +
            "\"backend\":${jsonString(backend)}" +
            "}"
}

object AgentAsrInspectionRunner {
    private const val REPORT_FILE_NAME = "asr-inspection-report.json"

    fun run(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
        return try {
            val options = parseArgs(args)
            if (options == null) {
                out.println(usage())
                0
            } else {
                val report = execute(options)
                Files.createDirectories(options.outputDirectory)
                val reportPath = options.outputDirectory.resolve(REPORT_FILE_NAME)
                Files.writeString(reportPath, report.toJson(), StandardCharsets.UTF_8)
                out.println("ASR_INSPECTION_STATUS=${report.status}")
                out.println("ASR_INSPECTION_REPORT=$reportPath")
                if (report.status != "passed" && report.error != null) {
                    err.println("ASR_INSPECTION_ERROR=${report.error}")
                }
                if (report.status == "passed") 0 else 1
            }
        } catch (error: Throwable) {
            err.println("ASR_INSPECTION_ERROR=${error.message ?: error::class.simpleName}")
            2
        }
    }

    fun execute(options: AgentAsrInspectionOptions): AgentAsrInspectionReport {
        val startedAt = Instant.now()
        val manifestPath = options.manifestPath.toAbsolutePath().normalize()
        val asrModelPath = options.asrModelPath.toAbsolutePath().normalize()
        var evidence: AsrBackendEvidence? = null
        var modelMetadata = emptyMap<String, String>()
        return try {
            require(Files.isRegularFile(manifestPath)) { "Manifest does not exist: $manifestPath" }
            require(Files.isRegularFile(asrModelPath)) { "ASR model does not exist: $asrModelPath" }
            require(options.chunkIndices.isNotEmpty()) { "At least one chunk index is required." }
            val store = BatchAudioStore(manifestPath.parent)
            val manifest = store.loadManifest(manifestPath)
            val selected = options.chunkIndices.sorted()
            require(selected.all { index -> manifest.chunks.any { it.index == index } }) {
                "ASR inspection requested a chunk that is not present in the manifest: " +
                    selected.filterNot { index -> manifest.chunks.any { it.index == index } }.joinToString()
            }
            val transcriber = NativeBatchAsrTranscriber(asrModelPath.toFile(), backendPreference = options.backend)
            val result = try {
                evidence = transcriber.backendEvidence
                modelMetadata = transcriber.modelMetadata
                BatchAsrValidator.validateWithMetrics(
                    store = store,
                    manifest = manifest,
                    transcriber = transcriber,
                    minimumSimilarity = options.minimumSimilarity,
                    chunkIndices = selected.toSet()
                )
            } finally {
                transcriber.close()
            }
            val findings = result.findings.map { finding ->
                AgentAsrInspectionFinding(
                    chunkIndex = finding.chunkIndex,
                    window = finding.window.name,
                    expectedText = finding.expectedText,
                    transcript = finding.transcript,
                    similarity = finding.similarity,
                    passed = finding.passed,
                    error = finding.error,
                    elapsedMillis = finding.elapsedMs,
                    backend = finding.backend.name
                )
            }
            AgentAsrInspectionReport(
                schemaVersion = 1,
                status = if (result.passed) "passed" else "failed",
                startedAt = startedAt.toString(),
                finishedAt = Instant.now().toString(),
                manifestPath = manifestPath.toString(),
                asrModelPath = asrModelPath.toString(),
                backend = result.backendEvidence?.backend?.name ?: result.metrics.backend.name,
                minimumSimilarity = options.minimumSimilarity,
                chunkIndices = selected,
                backendEvidence = result.backendEvidence,
                modelMetadata = result.modelMetadata,
                findings = findings,
                error = findings.firstOrNull { !it.passed }?.error
            )
        } catch (error: Throwable) {
            AgentAsrInspectionReport(
                schemaVersion = 1,
                status = "failed",
                startedAt = startedAt.toString(),
                finishedAt = Instant.now().toString(),
                manifestPath = manifestPath.toString(),
                asrModelPath = asrModelPath.toString(),
                backend = evidence?.backend?.name ?: "UNKNOWN",
                minimumSimilarity = options.minimumSimilarity,
                chunkIndices = options.chunkIndices.sorted(),
                backendEvidence = evidence,
                modelMetadata = modelMetadata,
                findings = emptyList(),
                error = error.message ?: error::class.simpleName
            )
        }
    }

    private fun parseArgs(args: Array<String>): AgentAsrInspectionOptions? {
        var manifest: Path? = null
        var asrModel: Path? = null
        var output: Path? = null
        var backend = NativeBackendPreference.Cpu
        var minimumSimilarity = 0.75
        val chunks = mutableSetOf<Int>()
        var sawFlag = false
        var index = 0

        fun nextValue(option: String): String {
            require(index + 1 < args.size) { "$option requires a value." }
            index++
            return args[index]
        }

        fun addChunks(value: String) {
            value.split(',').map(String::trim).filter(String::isNotBlank).forEach { chunks += it.toInt() }
        }

        while (index < args.size) {
            val argument = args[index]
            when {
                argument == "--help" || argument == "-h" -> return null
                argument == "--agent-asr-inspect" -> sawFlag = true
                argument == "--manifest" -> manifest = Path.of(nextValue(argument))
                argument.startsWith("--manifest=") -> manifest = Path.of(argument.substringAfter('='))
                argument == "--asr-model" -> asrModel = Path.of(nextValue(argument))
                argument.startsWith("--asr-model=") -> asrModel = Path.of(argument.substringAfter('='))
                argument == "--output-dir" -> output = Path.of(nextValue(argument))
                argument.startsWith("--output-dir=") -> output = Path.of(argument.substringAfter('='))
                argument == "--backend" -> backend = NativeBackendPreference.fromId(nextValue(argument))
                argument.startsWith("--backend=") -> backend = NativeBackendPreference.fromId(argument.substringAfter('='))
                argument == "--chunks" -> addChunks(nextValue(argument))
                argument.startsWith("--chunks=") -> addChunks(argument.substringAfter('='))
                argument == "--minimum-similarity" -> minimumSimilarity = nextValue(argument).toDouble()
                argument.startsWith("--minimum-similarity=") -> minimumSimilarity = argument.substringAfter('=').toDouble()
                else -> error("Unknown ASR inspection option '$argument'.")
            }
            index++
        }
        if (!sawFlag && args.isNotEmpty()) error("The ASR inspection runner requires --agent-asr-inspect.")
        return if (!sawFlag) null else AgentAsrInspectionOptions(
            manifestPath = manifest ?: error("--manifest is required."),
            asrModelPath = asrModel ?: error("--asr-model is required."),
            outputDirectory = output ?: error("--output-dir is required."),
            chunkIndices = chunks,
            backend = backend,
            minimumSimilarity = minimumSimilarity
        )
    }

    private fun usage(): String = """
        Qwen-TTS Studio bounded ASR inspection

        Usage:
          --agent-asr-inspect --manifest PATH --asr-model PATH --output-dir PATH
          --chunks 23,112 [--backend auto|cpu|cuda] [--minimum-similarity 0.75]
    """.trimIndent()
}

private fun backendEvidenceJson(value: AsrBackendEvidence?): String = value?.let {
    "{" +
        "\"backend\":${jsonString(it.backend.name)}," +
        "\"nativeName\":${jsonString(it.nativeName)}," +
        "\"gpuActive\":${jsonBoolean(it.gpuActive)}," +
        "\"encoderWeightsOnGpu\":${jsonBoolean(it.encoderWeightsOnGpu)}," +
        "\"decoderWeightsOnGpu\":${jsonBoolean(it.decoderWeightsOnGpu)}," +
        "\"deviceFreeBytes\":${it.deviceFreeBytes ?: "null"}," +
        "\"deviceTotalBytes\":${it.deviceTotalBytes ?: "null"}" +
        "}"
} ?: "null"

private fun mapJson(values: Map<String, String>): String =
    values.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (key, value) ->
        "${jsonString(key)}:${jsonString(value)}"
    }

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
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}

fun main(args: Array<String>) {
    exitProcess(AgentAsrInspectionRunner.run(args))
}
