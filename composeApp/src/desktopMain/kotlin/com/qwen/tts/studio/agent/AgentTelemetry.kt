package com.qwen.tts.studio.agent

import com.qwen.tts.studio.engine.QwenEngine
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Best-effort process and host memory observation for the headless batch verification runner.
 *
 * The native backend memory values are kept separate from host process and host
 * physical-memory values. In particular, host physical memory is never used as
 * a proxy for device memory.
 */
data class AgentProcessMemorySnapshot(
    val rssBytes: Long? = null,
    val committedVirtualBytes: Long? = null,
    val hostPhysicalFreeBytes: Long? = null,
    val hostPhysicalTotalBytes: Long? = null
) {
    companion object {
        fun current(): AgentProcessMemorySnapshot {
            val operatingSystem = runCatching { ManagementFactory.getOperatingSystemMXBean() }.getOrNull()
            return AgentProcessMemorySnapshot(
                rssBytes = readLinuxRssBytes(),
                committedVirtualBytes = operatingSystem?.let {
                    readOptionalLong(it, "getCommittedVirtualMemorySize")
                },
                hostPhysicalFreeBytes = operatingSystem?.let {
                    readOptionalLong(it, "getFreePhysicalMemorySize")
                },
                hostPhysicalTotalBytes = operatingSystem?.let {
                    readOptionalLong(it, "getTotalPhysicalMemorySize")
                }
            )
        }

        private fun readOptionalLong(bean: Any, methodName: String): Long? = runCatching {
            bean.javaClass.methods
                .firstOrNull { method -> method.name == methodName && method.parameterCount == 0 }
                ?.invoke(bean)
                ?.let { value -> (value as? Number)?.toLong() }
                ?.takeIf { value -> value >= 0L }
        }.getOrNull()

        /** Linux exposes RSS without adding a platform-specific dependency. */
        private fun readLinuxRssBytes(): Long? = runCatching {
            val status = Path.of("/proc/self/status")
            if (!Files.isRegularFile(status)) return@runCatching null
            val line = Files.readAllLines(status).firstOrNull { it.startsWith("VmRSS:") } ?: return@runCatching null
            val kilobytes = line.substringAfter(':')
                .trim()
                .split(Regex("\\s+"))
                .firstOrNull()
                ?.toLongOrNull()
                ?: return@runCatching null
            Math.multiplyExact(kilobytes, 1024L)
        }.getOrNull()?.takeIf { it >= 0L }
    }
}

data class AgentTelemetrySample(
    val phase: String,
    val elapsedMillis: Long,
    val deviceBackendFreeBytes: Long?,
    val deviceBackendTotalBytes: Long?,
    val processRssBytes: Long?,
    val processCommittedVirtualBytes: Long?,
    val hostPhysicalFreeBytes: Long?,
    val hostPhysicalTotalBytes: Long?
)

data class AgentPhaseTiming(
    val phase: String,
    val elapsedMillis: Long,
    val successful: Boolean?
)

data class AgentGenerationTiming(
    val callIndex: Int,
    val kind: String,
    val textCharacters: Int,
    val elapsedMillis: Long,
    val reportedTimeMillis: Long?,
    val success: Boolean,
    val audioDurationSeconds: Double?,
    val realTimeFactor: Double?,
    val reportedRealTimeFactor: Double?
)

data class AgentValidationTiming(
    val elapsedMillis: Long,
    val passed: Boolean?
)

data class AgentCapacityMemorySummary(
    val memoryDomain: String,
    val sampleCount: Int,
    val freeBytesMin: Long?,
    val freeBytesMax: Long?,
    val freeBytesLast: Long?,
    val totalBytesMax: Long?,
    val totalBytesLast: Long?,
    val usedBytesHighWater: Long?,
    val usedHighWaterRatio: Double?
)

data class AgentProcessMemorySummary(
    val memoryDomain: String,
    val rssSampleCount: Int,
    val rssBytesHighWater: Long?,
    val rssBytesLast: Long?,
    val committedVirtualSampleCount: Int,
    val committedVirtualBytesHighWater: Long?,
    val committedVirtualBytesLast: Long?
)

data class AgentMemorySummary(
    val deviceBackend: AgentCapacityMemorySummary,
    val hostProcess: AgentProcessMemorySummary,
    val hostPhysical: AgentCapacityMemorySummary
)

data class AgentValidationSummary(
    val runs: Int,
    val totalElapsedMillis: Long,
    val minElapsedMillis: Long?,
    val maxElapsedMillis: Long?,
    val lastPassed: Boolean?,
    val records: List<AgentValidationTiming>,
    val droppedRecordCount: Int
)

data class AgentGenerationSummary(
    val attemptedCalls: Int,
    val successfulCalls: Int,
    val failedCalls: Int,
    val totalElapsedMillis: Long,
    val minElapsedMillis: Long?,
    val maxElapsedMillis: Long?,
    val averageElapsedMillis: Double?,
    val reportedTimeMillis: Long?,
    val attemptedAudioDurationSeconds: Double?,
    val audioDurationSeconds: Double?,
    val audioDurationSource: String?,
    val realTimeFactor: Double?,
    val reportedRealTimeFactor: Double?,
    val records: List<AgentGenerationTiming>,
    val droppedRecordCount: Int
)

data class AgentTelemetrySnapshot(
    val elapsedMillis: Long,
    val sampleCount: Int,
    val droppedSampleCount: Int,
    val phaseTimings: List<AgentPhaseTiming>,
    val droppedPhaseCount: Int,
    val generation: AgentGenerationSummary,
    val validation: AgentValidationSummary,
    val memory: AgentMemorySummary,
    val samples: List<AgentTelemetrySample>
) {
    /** Returns a JSON object suitable for embedding under batch-verification-report.json.telemetry. */
    fun toJson(): String = buildString {
        append("{")
        append("\"elapsedMillis\":$elapsedMillis,")
        append("\"sampleCount\":$sampleCount,")
        append("\"droppedSampleCount\":$droppedSampleCount,")
        append("\"droppedPhaseCount\":$droppedPhaseCount,")
        append("\"droppedGenerationRecordCount\":${generation.droppedRecordCount},")
        append("\"droppedValidationRecordCount\":${validation.droppedRecordCount},")
        append("\"phaseTimings\":${phaseTimingsJson(phaseTimings)},")
        append("\"generation\":${generationJson(generation)},")
        append("\"validation\":${validationJson(validation)},")
        append("\"memory\":${memoryJson(memory)},")
        append("\"samples\":${samplesJson(samples)}")
        append("}")
    }
}

/**
 * Bounded, fail-safe telemetry collector. Aggregates are updated for every
 * observation, while raw samples and per-call records are capped so a long
 * batch cannot make the report grow without limit.
 */
internal class AgentTelemetry(
    private val nanoTime: () -> Long = System::nanoTime,
    private val processMemory: () -> AgentProcessMemorySnapshot = AgentProcessMemorySnapshot::current,
    maxSamples: Int = DEFAULT_MAX_SAMPLES,
    maxPhaseTimings: Int = DEFAULT_MAX_PHASE_TIMINGS,
    maxGenerationRecords: Int = DEFAULT_MAX_GENERATION_RECORDS,
    maxValidationRecords: Int = DEFAULT_MAX_VALIDATION_RECORDS
) {
    companion object {
        const val DEFAULT_MAX_SAMPLES = 256
        const val DEFAULT_MAX_PHASE_TIMINGS = 256
        const val DEFAULT_MAX_GENERATION_RECORDS = 512
        const val DEFAULT_MAX_VALIDATION_RECORDS = 32
    }

    private val sampleLimit = maxSamples.coerceAtLeast(0)
    private val phaseLimit = maxPhaseTimings.coerceAtLeast(0)
    private val generationLimit = maxGenerationRecords.coerceAtLeast(0)
    private val validationLimit = maxValidationRecords.coerceAtLeast(0)
    private val startedNanos = safeNow()
    private var backendMemoryProvider: () -> QwenEngine.BackendMemory? = { null }
    private val samples = mutableListOf<AgentTelemetrySample>()
    private val phaseTimings = mutableListOf<AgentPhaseTiming>()
    private val generationRecords = mutableListOf<AgentGenerationTiming>()
    private val validationRecords = mutableListOf<AgentValidationTiming>()
    private val memoryAccumulator = MemoryAccumulator()
    private var droppedSampleCount = 0
    private var droppedPhaseCount = 0
    private var droppedGenerationCount = 0
    private var droppedValidationCount = 0
    private var generationAttemptedCalls = 0
    private var generationSuccessfulCalls = 0
    private var generationTotalElapsedMillis = 0L
    private var generationMinElapsedMillis: Long? = null
    private var generationMaxElapsedMillis: Long? = null
    private var generationReportedTimeMillis = 0L
    private var generationReportedTimeCount = 0
    private var generationAudioDurationSeconds = 0.0
    private var validationRuns = 0
    private var validationTotalElapsedMillis = 0L
    private var validationMinElapsedMillis: Long? = null
    private var validationMaxElapsedMillis: Long? = null
    private var validationLastPassed: Boolean? = null

    /** Connects sampling to the engine wrapper without making telemetry part of the engine API. */
    fun attachBackendMemoryProvider(provider: () -> QwenEngine.BackendMemory?) {
        runCatching { backendMemoryProvider = provider }
    }

    fun sample(phase: String) {
        val backend = runCatching { backendMemoryProvider() }.getOrNull()
        sample(phase, backend)
    }

    fun sample(phase: String, backendMemory: QwenEngine.BackendMemory?) {
        runCatching {
            val host = runCatching { processMemory() }.getOrNull()
            val sample = AgentTelemetrySample(
                phase = phase,
                elapsedMillis = elapsedSince(startedNanos, safeNow()),
                deviceBackendFreeBytes = backendMemory?.freeBytes,
                deviceBackendTotalBytes = backendMemory?.totalBytes,
                processRssBytes = host?.rssBytes,
                processCommittedVirtualBytes = host?.committedVirtualBytes,
                hostPhysicalFreeBytes = host?.hostPhysicalFreeBytes,
                hostPhysicalTotalBytes = host?.hostPhysicalTotalBytes
            )
            synchronized(this) {
                memoryAccumulator.observe(sample)
                if (!appendBounded(samples, sample, sampleLimit)) droppedSampleCount++
            }
        }
    }

    fun beginPhase(phase: String): Long {
        sample("$phase-start")
        return safeNow()
    }

    fun finishPhase(startedAtNanos: Long, phase: String, successful: Boolean?): Long {
        val elapsed = elapsedSince(startedAtNanos, safeNow())
        synchronized(this) {
            if (!appendBounded(phaseTimings, AgentPhaseTiming(phase, elapsed, successful), phaseLimit)) {
                droppedPhaseCount++
            }
        }
        sample("$phase-end")
        return elapsed
    }

    fun finishValidation(startedAtNanos: Long, passed: Boolean?): Long {
        val elapsed = finishPhase(startedAtNanos, "validation", passed)
        synchronized(this) {
            validationRuns++
            validationTotalElapsedMillis = safeAdd(validationTotalElapsedMillis, elapsed)
            validationMinElapsedMillis = minOfNullable(validationMinElapsedMillis, elapsed)
            validationMaxElapsedMillis = maxOfNullable(validationMaxElapsedMillis, elapsed)
            validationLastPassed = passed
            if (!appendBounded(validationRecords, AgentValidationTiming(elapsed, passed), validationLimit)) {
                droppedValidationCount++
            }
        }
        return elapsed
    }

    fun finishGeneration(
        startedAtNanos: Long,
        kind: String,
        text: String,
        audioSampleCount: Int?,
        sampleRate: Int?,
        reportedTimeMillis: Long?,
        success: Boolean
    ): Long {
        val elapsed = elapsedSince(startedAtNanos, safeNow())
        val audioDuration = if (success && audioSampleCount != null && audioSampleCount > 0 && sampleRate != null && sampleRate > 0) {
            audioSampleCount.toDouble() / sampleRate.toDouble()
        } else {
            null
        }
        val reported = reportedTimeMillis?.takeIf { it > 0L }
        val timing = AgentGenerationTiming(
            callIndex = synchronized(this) { generationAttemptedCalls + 1 },
            kind = kind,
            textCharacters = text.length,
            elapsedMillis = elapsed,
            reportedTimeMillis = reported,
            success = success,
            audioDurationSeconds = audioDuration,
            realTimeFactor = realTimeFactor(elapsed, audioDuration),
            reportedRealTimeFactor = realTimeFactor(reported, audioDuration)
        )
        synchronized(this) {
            generationAttemptedCalls++
            if (success) generationSuccessfulCalls++
            generationTotalElapsedMillis = safeAdd(generationTotalElapsedMillis, elapsed)
            generationMinElapsedMillis = minOfNullable(generationMinElapsedMillis, elapsed)
            generationMaxElapsedMillis = maxOfNullable(generationMaxElapsedMillis, elapsed)
            if (reported != null) {
                generationReportedTimeMillis = safeAdd(generationReportedTimeMillis, reported)
                generationReportedTimeCount++
            }
            if (audioDuration != null) {
                generationAudioDurationSeconds += audioDuration
            }
            if (!appendBounded(generationRecords, timing, generationLimit)) droppedGenerationCount++
            if (!appendBounded(phaseTimings, AgentPhaseTiming("generation-$kind", elapsed, success), phaseLimit)) {
                droppedPhaseCount++
            }
        }
        sample("generation-$kind-end")
        return elapsed
    }

    fun snapshot(persistedAudioDurationSeconds: Double? = null): AgentTelemetrySnapshot {
        val elapsed = elapsedSince(startedNanos, safeNow())
        synchronized(this) {
            val attemptedAudio = generationAudioDurationSeconds.takeIf { it > 0.0 && it.isFinite() }
            val persistedAudio = persistedAudioDurationSeconds?.takeIf { it > 0.0 && it.isFinite() }
            val audioDuration = persistedAudio ?: attemptedAudio
            val audioSource = when {
                persistedAudio != null -> "persisted_manifest"
                attemptedAudio != null -> "generation_results"
                else -> null
            }
            val reportedTime = generationReportedTimeMillis.takeIf { generationReportedTimeCount > 0 }
            return AgentTelemetrySnapshot(
                elapsedMillis = elapsed,
                sampleCount = samples.size,
                droppedSampleCount = droppedSampleCount,
                phaseTimings = phaseTimings.toList(),
                droppedPhaseCount = droppedPhaseCount,
                generation = AgentGenerationSummary(
                    attemptedCalls = generationAttemptedCalls,
                    successfulCalls = generationSuccessfulCalls,
                    failedCalls = (generationAttemptedCalls - generationSuccessfulCalls).coerceAtLeast(0),
                    totalElapsedMillis = generationTotalElapsedMillis,
                    minElapsedMillis = generationMinElapsedMillis,
                    maxElapsedMillis = generationMaxElapsedMillis,
                    averageElapsedMillis = generationAttemptedCalls.takeIf { it > 0 }?.let {
                        generationTotalElapsedMillis.toDouble() / it.toDouble()
                    },
                    reportedTimeMillis = reportedTime,
                    attemptedAudioDurationSeconds = attemptedAudio,
                    audioDurationSeconds = audioDuration,
                    audioDurationSource = audioSource,
                    realTimeFactor = realTimeFactor(generationTotalElapsedMillis, audioDuration),
                    reportedRealTimeFactor = realTimeFactor(reportedTime, audioDuration),
                    records = generationRecords.toList(),
                    droppedRecordCount = droppedGenerationCount
                ),
                validation = AgentValidationSummary(
                    runs = validationRuns,
                    totalElapsedMillis = validationTotalElapsedMillis,
                    minElapsedMillis = validationMinElapsedMillis,
                    maxElapsedMillis = validationMaxElapsedMillis,
                    lastPassed = validationLastPassed,
                    records = validationRecords.toList(),
                    droppedRecordCount = droppedValidationCount
                ),
                memory = memoryAccumulator.snapshot(),
                samples = samples.toList()
            )
        }
    }

    private fun safeNow(): Long = runCatching { nanoTime() }.getOrElse { System.nanoTime() }

    private fun elapsedSince(start: Long, end: Long): Long =
        ((end - start).coerceAtLeast(0L)) / AGENT_NANOS_PER_MILLISECOND

    private fun realTimeFactor(elapsedMillis: Long?, audioDurationSeconds: Double?): Double? {
        if (elapsedMillis == null || audioDurationSeconds == null || audioDurationSeconds <= 0.0) return null
        val value = elapsedMillis.toDouble() / 1000.0 / audioDurationSeconds
        return value.takeIf { it.isFinite() }
    }

    private fun <T> appendBounded(target: MutableList<T>, value: T, limit: Int): Boolean {
        if (limit <= 0) return false
        if (target.size < limit) {
            target += value
            return true
        }
        target[target.lastIndex] = value
        return false
    }

    private class MemoryAccumulator {
        private val deviceBackend = CapacityAccumulator("device_backend_or_native_allocator")
        private val hostPhysical = CapacityAccumulator("host_physical_system_memory")
        private val hostProcess = ProcessAccumulator()

        fun observe(sample: AgentTelemetrySample) {
            deviceBackend.observe(sample.deviceBackendFreeBytes, sample.deviceBackendTotalBytes)
            hostPhysical.observe(sample.hostPhysicalFreeBytes, sample.hostPhysicalTotalBytes)
            hostProcess.observe(sample.processRssBytes, sample.processCommittedVirtualBytes)
        }

        fun snapshot() = AgentMemorySummary(
            deviceBackend = deviceBackend.snapshot(),
            hostProcess = hostProcess.snapshot(),
            hostPhysical = hostPhysical.snapshot()
        )
    }

    private class CapacityAccumulator(private val domain: String) {
        private var sampleCount = 0
        private var freeMin: Long? = null
        private var freeMax: Long? = null
        private var freeLast: Long? = null
        private var totalMax: Long? = null
        private var totalLast: Long? = null
        private var usedHighWater: Long? = null
        private var usedHighWaterRatio: Double? = null

        fun observe(freeBytes: Long?, totalBytes: Long?) {
            val total = totalBytes?.takeIf { it > 0L } ?: return
            val free = freeBytes?.takeIf { it >= 0L }?.coerceAtMost(total) ?: return
            val used = total - free
            val ratio = used.toDouble() / total.toDouble()
            sampleCount++
            freeMin = minOfNullable(freeMin, free)
            freeMax = maxOfNullable(freeMax, free)
            freeLast = free
            totalMax = maxOfNullable(totalMax, total)
            totalLast = total
            usedHighWater = maxOfNullable(usedHighWater, used)
            if (ratio.isFinite()) usedHighWaterRatio = maxOfNullable(usedHighWaterRatio, ratio)
        }

        fun snapshot() = AgentCapacityMemorySummary(
            memoryDomain = domain,
            sampleCount = sampleCount,
            freeBytesMin = freeMin,
            freeBytesMax = freeMax,
            freeBytesLast = freeLast,
            totalBytesMax = totalMax,
            totalBytesLast = totalLast,
            usedBytesHighWater = usedHighWater,
            usedHighWaterRatio = usedHighWaterRatio
        )
    }

    private class ProcessAccumulator {
        private var rssSampleCount = 0
        private var rssHighWater: Long? = null
        private var rssLast: Long? = null
        private var committedSampleCount = 0
        private var committedHighWater: Long? = null
        private var committedLast: Long? = null

        fun observe(rssBytes: Long?, committedVirtualBytes: Long?) {
            rssBytes?.takeIf { it >= 0L }?.let {
                rssSampleCount++
                rssHighWater = maxOfNullable(rssHighWater, it)
                rssLast = it
            }
            committedVirtualBytes?.takeIf { it >= 0L }?.let {
                committedSampleCount++
                committedHighWater = maxOfNullable(committedHighWater, it)
                committedLast = it
            }
        }

        fun snapshot() = AgentProcessMemorySummary(
            memoryDomain = "host_process",
            rssSampleCount = rssSampleCount,
            rssBytesHighWater = rssHighWater,
            rssBytesLast = rssLast,
            committedVirtualSampleCount = committedSampleCount,
            committedVirtualBytesHighWater = committedHighWater,
            committedVirtualBytesLast = committedLast
        )
    }

}

private const val AGENT_NANOS_PER_MILLISECOND = 1_000_000L

private fun minOfNullable(current: Long?, value: Long): Long =
    if (current == null) value else minOf(current, value)

private fun maxOfNullable(current: Long?, value: Long): Long =
    if (current == null) value else maxOf(current, value)

private fun maxOfNullable(current: Double?, value: Double): Double =
    if (current == null) value else maxOf(current, value)

private fun safeAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private fun phaseTimingsJson(values: List<AgentPhaseTiming>): String = values.joinToString(",", "[", "]") { value ->
    "{\"phase\":${telemetryJsonString(value.phase)},\"elapsedMillis\":${value.elapsedMillis}," +
        "\"successful\":${telemetryJsonBoolean(value.successful)}}"
}

private fun generationJson(value: AgentGenerationSummary): String = buildString {
    append("{")
    append("\"attemptedCalls\":${value.attemptedCalls},")
    append("\"successfulCalls\":${value.successfulCalls},")
    append("\"failedCalls\":${value.failedCalls},")
    append("\"totalElapsedMillis\":${value.totalElapsedMillis},")
    append("\"minElapsedMillis\":${value.minElapsedMillis ?: "null"},")
    append("\"maxElapsedMillis\":${value.maxElapsedMillis ?: "null"},")
    append("\"averageElapsedMillis\":${telemetryJsonDouble(value.averageElapsedMillis)},")
    append("\"reportedTimeMillis\":${value.reportedTimeMillis ?: "null"},")
    append("\"attemptedAudioDurationSeconds\":${telemetryJsonDouble(value.attemptedAudioDurationSeconds)},")
    append("\"audioDurationSeconds\":${telemetryJsonDouble(value.audioDurationSeconds)},")
    append("\"audioDurationSource\":${telemetryJsonString(value.audioDurationSource)},")
    append("\"realTimeFactor\":${telemetryJsonDouble(value.realTimeFactor)},")
    append("\"reportedRealTimeFactor\":${telemetryJsonDouble(value.reportedRealTimeFactor)},")
    append("\"droppedRecordCount\":${value.droppedRecordCount},")
    append("\"records\":${value.records.joinToString(",", "[", "]", transform = ::generationRecordJson)}")
    append("}")
}

private fun generationRecordJson(value: AgentGenerationTiming): String =
    "{\"callIndex\":${value.callIndex},\"kind\":${telemetryJsonString(value.kind)}," +
        "\"textCharacters\":${value.textCharacters},\"elapsedMillis\":${value.elapsedMillis}," +
        "\"reportedTimeMillis\":${value.reportedTimeMillis ?: "null"},\"success\":${value.success}," +
        "\"audioDurationSeconds\":${telemetryJsonDouble(value.audioDurationSeconds)}," +
        "\"realTimeFactor\":${telemetryJsonDouble(value.realTimeFactor)}," +
        "\"reportedRealTimeFactor\":${telemetryJsonDouble(value.reportedRealTimeFactor)}}"

private fun validationJson(value: AgentValidationSummary): String = buildString {
    append("{")
    append("\"runs\":${value.runs},")
    append("\"totalElapsedMillis\":${value.totalElapsedMillis},")
    append("\"minElapsedMillis\":${value.minElapsedMillis ?: "null"},")
    append("\"maxElapsedMillis\":${value.maxElapsedMillis ?: "null"},")
    append("\"lastPassed\":${telemetryJsonBoolean(value.lastPassed)},")
    append("\"droppedRecordCount\":${value.droppedRecordCount},")
    append("\"records\":${value.records.joinToString(",", "[", "]") { record ->
        "{\"elapsedMillis\":${record.elapsedMillis},\"passed\":${telemetryJsonBoolean(record.passed)}}"
    }}")
    append("}")
}

private fun memoryJson(value: AgentMemorySummary): String =
    "{\"deviceBackend\":${capacityMemoryJson(value.deviceBackend, "Backend-reported free/total memory; device allocator for GPU backends or native/backend allocator for CPU backends; never host physical memory.")}," +
        "\"hostProcess\":${processMemoryJson(value.hostProcess)}," +
        "\"hostPhysical\":${capacityMemoryJson(value.hostPhysical, "Host physical system memory (RAM); never interpreted as device memory.")}}"

private fun capacityMemoryJson(value: AgentCapacityMemorySummary, description: String): String =
    "{\"memoryDomain\":${telemetryJsonString(value.memoryDomain)},\"description\":${telemetryJsonString(description)}," +
        "\"sampleCount\":${value.sampleCount},\"freeBytesMin\":${value.freeBytesMin ?: "null"}," +
        "\"freeBytesMax\":${value.freeBytesMax ?: "null"},\"freeBytesLast\":${value.freeBytesLast ?: "null"}," +
        "\"totalBytesMax\":${value.totalBytesMax ?: "null"},\"totalBytesLast\":${value.totalBytesLast ?: "null"}," +
        "\"usedBytesHighWater\":${value.usedBytesHighWater ?: "null"}," +
        "\"usedHighWaterRatio\":${telemetryJsonDouble(value.usedHighWaterRatio)}}"

private fun processMemoryJson(value: AgentProcessMemorySummary): String =
    "{\"memoryDomain\":${telemetryJsonString(value.memoryDomain)}," +
        "\"description\":${telemetryJsonString("Process RSS/committed virtual memory; host process metrics, not device memory.")}," +
        "\"rssSampleCount\":${value.rssSampleCount},\"rssBytesHighWater\":${value.rssBytesHighWater ?: "null"}," +
        "\"rssBytesLast\":${value.rssBytesLast ?: "null"}," +
        "\"committedVirtualSampleCount\":${value.committedVirtualSampleCount}," +
        "\"committedVirtualBytesHighWater\":${value.committedVirtualBytesHighWater ?: "null"}," +
        "\"committedVirtualBytesLast\":${value.committedVirtualBytesLast ?: "null"}}"

private fun samplesJson(samples: List<AgentTelemetrySample>): String = samples.joinToString(",", "[", "]") { sample ->
    "{\"phase\":${telemetryJsonString(sample.phase)},\"elapsedMillis\":${sample.elapsedMillis}," +
        "\"deviceBackendFreeBytes\":${sample.deviceBackendFreeBytes ?: "null"}," +
        "\"deviceBackendTotalBytes\":${sample.deviceBackendTotalBytes ?: "null"}," +
        "\"processRssBytes\":${sample.processRssBytes ?: "null"}," +
        "\"processCommittedVirtualBytes\":${sample.processCommittedVirtualBytes ?: "null"}," +
        "\"hostPhysicalFreeBytes\":${sample.hostPhysicalFreeBytes ?: "null"}," +
        "\"hostPhysicalTotalBytes\":${sample.hostPhysicalTotalBytes ?: "null"}}"
}

private fun telemetryJsonBoolean(value: Boolean?): String = value?.toString() ?: "null"

private fun telemetryJsonDouble(value: Double?): String =
    value?.takeIf { it.isFinite() }?.toString() ?: "null"

private fun telemetryJsonString(value: String?): String {
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
