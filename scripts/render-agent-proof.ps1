param(
    [Parameter(Mandatory = $true)]
    [string]$ReportPath,
    [Parameter(Mandatory = $true)]
    [string]$ManifestPath,
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$report = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
$output = [IO.Path]::GetFullPath($OutputDirectory)
$manifestDirectory = [IO.Path]::GetFullPath((Split-Path -Parent $ManifestPath))
$combinedPath = Join-Path $manifestDirectory "combined.wav"
$manifestChunks = @($manifest.chunks)
$completeChunkCount = @($manifestChunks | Where-Object { $_.status -eq "COMPLETE" }).Count
$passChunkCount = @($manifestChunks | Where-Object { $_.validationPassed }).Count
$failedChunkCount = $manifestChunks.Count - $passChunkCount
$capFailureCount = @($manifestChunks | Where-Object { $_.validationMessage -match "safe audio budget" }).Count
$asrFailureCount = @($manifestChunks | Where-Object { $_.validationMessage -match "ASR similarity" }).Count
$runPassed = ($report.status -eq "passed" -and [bool]$report.validationPassed)
$isNative = ([string]$report.mode).ToLowerInvariant() -eq "native"
$modeLabel = if ($isNative) { "native" } else { "fake" }
$backendLabel = if ([string]::IsNullOrWhiteSpace([string]$manifest.metadata.backendPreference)) { "unspecified backend" } else { [string]$manifest.metadata.backendPreference }
$modelLabel = if ([string]::IsNullOrWhiteSpace([string]$report.requestedModelName)) { "not requested" } else { [string]$report.requestedModelName }
$voiceLabel = if ([string]::IsNullOrWhiteSpace([string]$report.requestedSpeaker)) { "not requested" } else { [string]$report.requestedSpeaker }
$voiceModeLabel = if ([string]::IsNullOrWhiteSpace([string]$manifest.metadata.voiceMode)) { "not specified" } else { [string]$manifest.metadata.voiceMode }
$asrEvidence = $report.asrEvidence
$modelHash = [string]$manifest.metadata.modelFileSha256
$nativeHash = [string]$manifest.metadata.nativeLibrarySha256
$asrModelHash = [string]$manifest.metadata.asrModelFileSha256
$modelHashLabel = if ($modelHash.Length -gt 16) { $modelHash.Substring(0, 16) + "..." } else { $modelHash }
$nativeHashLabel = if ($nativeHash.Length -gt 16) { $nativeHash.Substring(0, 16) + "..." } else { $nativeHash }
$asrModelHashLabel = if ($asrModelHash.Length -gt 16) { $asrModelHash.Substring(0, 16) + "..." } else { $asrModelHash }
$asrEvidenceLabel = if ($null -eq $asrEvidence) {
    "ASR evidence: unavailable"
} elseif ([bool]$asrEvidence.gpuActive -and [bool]$asrEvidence.encoderWeightsOnGpu -and [bool]$asrEvidence.decoderWeightsOnGpu) {
    $freeGiB = if ($null -ne $asrEvidence.deviceFreeBytes) { ([double]$asrEvidence.deviceFreeBytes / 1GB).ToString("0.00") } else { "?" }
    $totalGiB = if ($null -ne $asrEvidence.deviceTotalBytes) { ([double]$asrEvidence.deviceTotalBytes / 1GB).ToString("0.00") } else { "?" }
    "ASR: GPU $($asrEvidence.nativeName) | weights encoder+decoder GPU | windows $($asrEvidence.windowCount) | VRAM free/total $freeGiB/$totalGiB GiB"
} else {
    "ASR: requested GPU but residency evidence is incomplete"
}
New-Item -ItemType Directory -Force -Path $output | Out-Null

$fontFamily = "Segoe UI"
$background = [Drawing.Color]::FromArgb(247, 249, 252)
$ink = [Drawing.Color]::FromArgb(25, 35, 52)
$muted = [Drawing.Color]::FromArgb(91, 105, 125)
$line = [Drawing.Color]::FromArgb(215, 222, 232)
$blue = [Drawing.Color]::FromArgb(42, 91, 160)
$green = [Drawing.Color]::FromArgb(24, 126, 82)
$greenPale = [Drawing.Color]::FromArgb(226, 246, 236)
$red = [Drawing.Color]::FromArgb(173, 45, 45)
$redPale = [Drawing.Color]::FromArgb(255, 232, 232)
$amber = [Drawing.Color]::FromArgb(143, 94, 0)
$amberPale = [Drawing.Color]::FromArgb(255, 244, 211)
$white = [Drawing.Color]::White

function New-ProofBitmap([int]$width, [int]$height) {
    $bitmap = New-Object Drawing.Bitmap($width, $height)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.TextRenderingHint = [Drawing.Text.TextRenderingHint]::ClearTypeGridFit
    $graphics.Clear($background)
    return [pscustomobject]@{ Bitmap = $bitmap; Graphics = $graphics }
}

function New-Font([float]$size, [bool]$bold = $false) {
    $style = if ($bold) { [Drawing.FontStyle]::Bold } else { [Drawing.FontStyle]::Regular }
    return New-Object Drawing.Font($fontFamily, $size, $style)
}

function Draw-Text($graphics, [string]$text, $font, [Drawing.Color]$color, [float]$x, [float]$y, [float]$width, [float]$height) {
    $brush = New-Object Drawing.SolidBrush($color)
    $format = New-Object Drawing.StringFormat
    $format.Trimming = [Drawing.StringTrimming]::EllipsisCharacter
    $format.FormatFlags = [Drawing.StringFormatFlags]::LineLimit
    $graphics.DrawString($text, $font, $brush, [Drawing.RectangleF]::new($x, $y, $width, $height), $format)
    $format.Dispose()
    $brush.Dispose()
}

function Draw-Card($graphics, [float]$x, [float]$y, [float]$width, [float]$height, [Drawing.Color]$fill = $white) {
    $brush = New-Object Drawing.SolidBrush($fill)
    $pen = New-Object Drawing.Pen($line, 1)
    $graphics.FillRectangle($brush, $x, $y, $width, $height)
    $graphics.DrawRectangle($pen, $x, $y, $width, $height)
    $pen.Dispose()
    $brush.Dispose()
}

function Save-Proof($canvas, [string]$path) {
    $canvas.Graphics.Dispose()
    $canvas.Bitmap.Save($path, [Drawing.Imaging.ImageFormat]::Png)
    $canvas.Bitmap.Dispose()
}

$titleFont = New-Font 26 $true
$sectionFont = New-Font 15 $true
$bodyFont = New-Font 12
$smallFont = New-Font 10
$metricFont = New-Font 22 $true
$asrStatus = if (-not $isNative -and $runPassed) {
    "Deterministic fake-engine validation completed; no native ASR model was configured."
} elseif ($runPassed -and $report.asrValidationSkipped) {
    "ASR validation was explicitly skipped and is shown here, not silently omitted."
} elseif ($runPassed) {
    "Deterministic and ASR validation both completed successfully."
} elseif ($report.asrValidationSkipped) {
    "Run failed deterministic validation; ASR was explicitly skipped."
} else {
    "Run failed deterministic and/or ASR validation; failures are listed in the manifest."
}
$validationSummary = if (-not $isNative -and $runPassed) {
    "Validation: PASS (deterministic fake)"
} elseif ($runPassed -and $report.asrValidationSkipped) {
    "Validation: PASS (deterministic only)"
} elseif ($runPassed) {
    "Validation: PASS (deterministic + ASR)"
} else {
    "Validation: FAIL ($passChunkCount/$($manifestChunks.Count) chunks passed)"
}
$evidenceBoundary = if (-not $isNative -and $runPassed) {
    "The run proves deterministic engine orchestration, chunk persistence,"
} elseif ($runPassed -and $report.asrValidationSkipped) {
    "The run proves native $backendLabel loading, voice/prompt propagation, chunk persistence,"
} elseif ($runPassed) {
    "The run proves native $backendLabel loading, voice/prompt propagation, chunk persistence,"
} else {
    "The run proves $modeLabel $backendLabel setup and $completeChunkCount/$($manifestChunks.Count) persisted chunks,"
}
$evidenceBoundaryDetail = if (-not $isNative -and $runPassed) {
    "deterministic validation, and recombination. Native inference and ASR are outside this proof."
} elseif ($runPassed -and $report.asrValidationSkipped) {
    "deterministic validation, and recombination. ASR was explicitly skipped for this run."
} elseif ($runPassed) {
    "deterministic validation, ASR edge validation, and recombination."
} else {
    $failureText = [string]$report.error
    if ([string]::IsNullOrWhiteSpace($failureText)) {
        $failurePreview = "no error text was recorded"
    } elseif ($failureText -match "reached its allocated audio budget") {
        $capMatch = [regex]::Match($failureText, "reached its allocated audio budget \([^)]+\)")
        $failurePreview = if ($capMatch.Success) {
            "Aggregate cap guard fired: $($capMatch.Value)"
        } else {
            "Aggregate cap guard fired: reached its allocated audio budget"
        }
    } else {
        $failurePreview = $failureText.Substring(0, [Math]::Min(180, $failureText.Length))
    }
    $capEvidence = if ($capFailureCount -gt 0) { "$capFailureCount cap checks" } elseif ($failureText -match "reached its allocated audio budget") { "aggregate cap guard" } else { "no recorded cap checks" }
    "but $failedChunkCount chunks failed ($capEvidence and $asrFailureCount ASR checks); no combined WAV was produced. First error: $failurePreview"
}
$overviewFill = if ($runPassed) { $greenPale } else { $redPale }
$overviewStatusColor = if ($runPassed) { $green } else { $red }
$overviewStatus = if ($runPassed) { "PASS" } else { "FAIL" }

$overviewCanvas = New-ProofBitmap 1500 940
$g = $overviewCanvas.Graphics
Draw-Text $g "Qwen-TTS Studio | Headless batch verification evidence" $titleFont $ink 46 34 1000 44
Draw-Text $g "Rendered from batch-verification-report.json (or its legacy alias) and manifest.json; no window, pixels, or OS input were used." $smallFont $muted 48 78 1200 24

Draw-Card $g 46 122 1408 100 $overviewFill
Draw-Text $g $overviewStatus (New-Font 30 $true) $overviewStatusColor 72 145 150 42
Draw-Text $g $(if ($runPassed) { "$modeLabel $backendLabel batch completed through the instrumented BatchScreen action path." } else { "$modeLabel $backendLabel batch finished, but the completion contract failed." }) $sectionFont $ink 210 150 1050 30
Draw-Text $g $asrStatus $smallFont $(if ($runPassed) { $green } else { $red }) 210 184 1050 22

Draw-Card $g 46 248 680 178
Draw-Text $g "Intent" $sectionFont $blue 70 270 180 26
Draw-Text $g "Input artifact: $($report.inputFile)" $bodyFont $ink 70 308 610 24
Draw-Text $g "Model: $modelLabel" $bodyFont $ink 70 336 610 24
Draw-Text $g "Voice: $voiceLabel | mode: $voiceModeLabel" $bodyFont $ink 70 364 610 24
Draw-Text $g "Prompt: $($report.requestedInstruction)" $smallFont $muted 70 392 610 24

Draw-Card $g 750 248 704 178
Draw-Text $g "Completion contract" $sectionFont $blue 774 270 260 26
Draw-Text $g "Chunks: $completeChunkCount/$($manifestChunks.Count)" $metricFont $overviewStatusColor 774 310 250 34
Draw-Text $g $validationSummary $bodyFont $ink 1050 314 360 24
Draw-Text $g "Reusable engine session: $($report.engine.reusableSession)" $bodyFont $ink 774 362 610 24
Draw-Text $g "Engine loads: $($report.engine.loadCalls) | generation calls: $($report.engine.generationCalls) including retries" $smallFont $muted 774 392 650 22
Draw-Text $g $asrEvidenceLabel $smallFont $(if ($null -ne $asrEvidence -and [bool]$asrEvidence.gpuActive -and [bool]$asrEvidence.encoderWeightsOnGpu -and [bool]$asrEvidence.decoderWeightsOnGpu) { $green } else { $muted }) 774 414 650 14

Draw-Card $g 46 452 1408 230
Draw-Text $g "Instrumented action trace" $sectionFont $blue 70 474 300 26
$actionY = 520
foreach ($action in $report.uiActions) {
    $statusColor = if ($action.completed) { $green } else { [Drawing.Color]::Firebrick }
    Draw-Text $g ("{0,-16} {1}" -f $action.action, ($(if ($action.completed) { "COMPLETE" } else { "FAILED" }))) $bodyFont $statusColor 80 $actionY 290 24
    Draw-Text $g $action.target $smallFont $muted 390 $actionY 350 24
    Draw-Text $g ("before: {0}/{1}  ->  after: {2}/{3}" -f $action.before.completedChunks, $action.before.expectedChunks, $action.after.completedChunks, $action.after.expectedChunks) $smallFont $ink 780 $actionY 500 24
    $actionY += 34
}

Draw-Card $g 46 708 1408 174 $amberPale
Draw-Text $g "Evidence boundary" $sectionFont $amber 70 730 250 26
Draw-Text $g $evidenceBoundary $bodyFont $ink 70 770 1300 24
Draw-Text $g $evidenceBoundaryDetail $bodyFont $ink 70 800 1300 24
Draw-Text $g "Report: $ReportPath" $smallFont $muted 70 844 1200 20
Save-Proof $overviewCanvas (Join-Path $output "agent-proof-overview.png")

$combinedExists = Test-Path -LiteralPath $combinedPath
$probe = if ($combinedExists) {
    (& ffprobe -v error -show_entries format=duration,size -show_entries stream=codec_name,sample_rate,channels,bits_per_sample -of default=noprint_wrappers=1 $combinedPath) -join " | "
} else {
    "combined.wav is missing; the instrumented Combine action did not complete."
}
$manifestCanvas = New-ProofBitmap 1500 900
$g = $manifestCanvas.Graphics
Draw-Text $g "Durable manifest and audio proof" $titleFont $ink 46 34 1000 44
Draw-Text $g "The table below is read from the fresh manifest and filesystem under $(Split-Path $ManifestPath)." $smallFont $muted 48 78 1300 24
Draw-Card $g 46 122 1408 166 $white
Draw-Text $g "Manifest" $sectionFont $blue 70 144 160 26
Draw-Text $g "expected chunks: $($manifest.expectedChunkCount) | complete: $completeChunkCount | validation: $passChunkCount PASS / $failedChunkCount FAIL" $bodyFont $ink 250 146 1120 24
Draw-Text $g "model: $($manifest.metadata.modelName) | backend: $($manifest.metadata.backendPreference)" $smallFont $muted 250 178 1120 22
Draw-Text $g "prompt: $($manifest.chunks[0].voicePrompt)" $smallFont $muted 250 202 1120 22
Draw-Text $g "model SHA-256: $modelHashLabel | native DLL SHA-256: $nativeHashLabel" $smallFont $muted 250 226 1120 22
Draw-Text $g "ASR model SHA-256: $asrModelHashLabel | native runtime: $($manifest.metadata.nativeRuntimeFingerprint)" $smallFont $muted 250 250 1120 22

$tableY = 300
Draw-Card $g 46 $tableY 1408 66 $blue
Draw-Text $g "CHUNK" $smallFont $white 70 ($tableY + 22) 100 20
Draw-Text $g "TEXT CHARS" $smallFont $white 210 ($tableY + 22) 150 20
Draw-Text $g "STATUS" $smallFont $white 420 ($tableY + 22) 150 20
Draw-Text $g "VALIDATION" $smallFont $white 620 ($tableY + 22) 180 20
Draw-Text $g "WAV BYTES" $smallFont $white 850 ($tableY + 22) 180 20
Draw-Text $g "TEXT SIDE-CAR" $smallFont $white 1090 ($tableY + 22) 220 20
$rowY = $tableY + 66
$visibleChunks = if ($manifestChunks.Count -le 6) {
    $manifestChunks
} else {
    @($manifestChunks | Select-Object -First 3) + @($manifestChunks | Select-Object -Last 3)
}
foreach ($chunk in $visibleChunks) {
    Draw-Card $g 46 $rowY 1408 58 ($(if (($chunk.index % 2) -eq 0) { $white } else { [Drawing.Color]::FromArgb(239, 243, 248) }))
    $wav = Join-Path (Split-Path $ManifestPath) $chunk.fileName
    $txt = Join-Path (Split-Path $ManifestPath) ("chunk-{0:D6}.txt" -f $chunk.index)
    Draw-Text $g ("#{0}" -f $chunk.index) $bodyFont $ink 70 ($rowY + 18) 100 22
    Draw-Text $g "$($chunk.text.Length)" $bodyFont $ink 210 ($rowY + 18) 150 22
    Draw-Text $g $chunk.status $bodyFont $(if ($chunk.status -eq "COMPLETE") { $green } else { $red }) 420 ($rowY + 18) 150 22
    Draw-Text $g ($(if ($chunk.validationPassed) { "PASS" } else { "FAIL" })) $bodyFont $(if ($chunk.validationPassed) { $green } else { $red }) 620 ($rowY + 18) 180 22
    $wavBytes = if (Test-Path -LiteralPath $wav) { (Get-Item -LiteralPath $wav).Length.ToString("N0") } else { "missing" }
    Draw-Text $g $wavBytes $bodyFont $(if ($wavBytes -eq "missing") { $red } else { $ink }) 850 ($rowY + 18) 180 22
    Draw-Text $g ($(if (Test-Path -LiteralPath $txt) { "present" } else { "missing" })) $bodyFont $green 1090 ($rowY + 18) 220 22
    $rowY += 58
}

$summaryY = [Math]::Max(560, $rowY + 8)
$summaryFill = if ($combinedExists) { $greenPale } else { $amberPale }
$summaryColor = if ($combinedExists) { $green } else { $amber }
Draw-Card $g 46 $summaryY 1408 170 $summaryFill
Draw-Text $g $(if ($combinedExists) { "Combined WAV" } else { "Combined WAV missing" }) $sectionFont $summaryColor 70 ($summaryY + 24) 300 26
Draw-Text $g $(if ($combinedExists) { "combined.wav exists outside the chunk files and was produced by the instrumented Combine action." } else { "The instrumented Combine action did not produce combined.wav because batch validation failed." }) $bodyFont $ink 70 ($summaryY + 68) 1300 24
Draw-Text $g $probe $smallFont $muted 70 ($summaryY + 108) 1320 42
Draw-Text $g "manifest: $ManifestPath" $smallFont $muted 70 ($summaryY + 148) 700 20
Draw-Text $g "combined: $combinedPath" $smallFont $muted 760 ($summaryY + 148) 650 20
Save-Proof $manifestCanvas (Join-Path $output "agent-proof-manifest.png")

$titleFont.Dispose()
$sectionFont.Dispose()
$bodyFont.Dispose()
$smallFont.Dispose()
$metricFont.Dispose()
