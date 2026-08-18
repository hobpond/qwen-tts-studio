package com.qwen.tts.studio.screens

/** Stable semantic targets used by the in-process headed UI validator. */
object BatchUiTestTags {
    const val navigationBatch = "batch.navigation"
    const val surface = "batch.surface"
    const val textFile = "batch.text-file"
    const val pickText = "batch.pick-text"
    const val loadManifest = "batch.load-manifest"
    const val generateManifest = "batch.generate-manifest"
    const val outputDirectory = "batch.output-directory"
    const val pickOutputDirectory = "batch.pick-output-directory"
    const val start = "batch.start"
    const val progress = "batch.progress"
    const val operationStatus = "batch.operation-status"
    const val validateAll = "batch.validate-all"
    const val rechunkFailed = "batch.rechunk-failed"
    const val resume = "batch.resume"
    const val regenerateAll = "batch.regenerate-all"
    const val validationSummary = "batch.validation-summary"
    const val combine = "batch.combine"
    const val chunkSearch = "batch.chunk-search"
    const val chunkFilter = "batch.chunk-filter"
    const val chunkList = "batch.chunk-list"
    const val chunkListSummary = "batch.chunk-list-summary"

    fun chunk(index: Int): String = "batch.chunk.$index"
    fun validateChunk(index: Int): String = "batch.validate-chunk.$index"
    fun validationState(index: Int): String = "batch.validation-state.$index"
    fun chunkFilterOption(filter: String): String = "batch.chunk-filter-option.$filter"
}

/**
 * File-system actions supplied by a headed UI test. Production leaves these
 * null and uses the normal FileKit pickers; tests provide immediate callbacks
 * so a semantic click never opens an OS dialog or needs computer-use input.
 */
data class BatchScreenFileActions(
    val pickTextFile: (() -> Unit)? = null,
    val loadManifest: (() -> Unit)? = null,
    val pickOutputDirectory: (() -> Unit)? = null,
    val saveCombined: (() -> Unit)? = null
)
