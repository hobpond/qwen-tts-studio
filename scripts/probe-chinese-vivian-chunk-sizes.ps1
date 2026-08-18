param(
    [string] $SourceText = 'D:\work\qwen-tts-studio\build\chinese-vivian-20260816\source.txt',
    [string] $OutputDirectory = 'D:\work\qwen-tts-studio\build\chinese-vivian-chunk-probe-20260817',
    [string] $ModelDirectory = 'D:\qwen-tts-studio\models',
    [string] $ModelName = 'qwen-talker-1.7b-customvoice-Q8_0.gguf',
    [string] $NativeRuntimeRoot = 'D:\work\qwen-tts-studio\artifacts\native-cuda-runtime-asr',
    [ValidateSet('cuda', 'cpu')] [string] $Backend = 'cuda',
    [string] $Language = 'Chinese',
    [string] $Speaker = 'Vivian',
    [string] $Instruction = 'Warm,clear,Mandarin,audiobook,narration,measured,pacing,crisp,diction,natural,pauses,gentle,emotion,restrained,character,distinction.',
    [int[]] $ChunkCharacters = @(40, 80, 120),
    [int] $Rounds = 3,
    [int] $Bands = 3,
    [int] $ChunksPerBand = 15,
    [int] $ProbeWindowCharacters = 12000
)

$ErrorActionPreference = 'Stop'

function Resolve-RequiredFile {
    param([string] $PathValue, [string] $Label)
    $resolved = Resolve-Path -LiteralPath $PathValue -ErrorAction Stop
    if (-not (Test-Path -LiteralPath $resolved.Path -PathType Leaf)) {
        throw "$Label is not a file: $($resolved.Path)"
    }
    return $resolved.Path
}

function Resolve-RequiredDirectory {
    param([string] $PathValue, [string] $Label)
    $resolved = Resolve-Path -LiteralPath $PathValue -ErrorAction Stop
    if (-not (Test-Path -LiteralPath $resolved.Path -PathType Container)) {
        throw "$Label is not a directory: $($resolved.Path)"
    }
    return $resolved.Path
}

function Quote-AgentArgument {
    param([string] $Value)
    if ($Value -notmatch '[\s"]') {
        return $Value
    }
    return '"' + $Value.Replace('"', '\\"') + '"'
}

function Get-MessageCount {
    param([object[]] $Chunks, [string] $Pattern)
    return @($Chunks | Where-Object {
        $_.validationPassed -ne $true -and
            ([string]$_.validationMessage) -match $Pattern
    }).Count
}

if ($ChunkCharacters.Count -eq 0 -or ($ChunkCharacters | Where-Object { $_ -le 0 }).Count -gt 0) {
    throw 'ChunkCharacters must contain only positive values.'
}
if ($Rounds -le 0 -or $Bands -le 0 -or $ChunksPerBand -le 0 -or $ProbeWindowCharacters -le 0) {
    throw 'Rounds, Bands, ChunksPerBand, and ProbeWindowCharacters must be positive.'
}

$source = Resolve-RequiredFile $SourceText 'Source text'
$modelDirectoryResolved = Resolve-RequiredDirectory $ModelDirectory 'Model directory'
$null = Resolve-RequiredFile (Join-Path $modelDirectoryResolved $ModelName) 'TTS model'
$nativeRoot = Resolve-RequiredDirectory $NativeRuntimeRoot 'Native runtime root'
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $output | Out-Null

$sourceEncoding = [Text.UTF8Encoding]::new($false)
$sourceText = [IO.File]::ReadAllText($source, $sourceEncoding)
if ([string]::IsNullOrWhiteSpace($sourceText)) {
    throw 'Source text is empty.'
}

$startedAt = (Get-Date).ToUniversalTime()
$records = [System.Collections.Generic.List[object]]::new()
$uniqueSizes = @($ChunkCharacters | Select-Object -Unique | Sort-Object)
$availableWindowStart = [Math]::Max(0, $sourceText.Length - $ProbeWindowCharacters)
$sessionId = $startedAt.ToString('yyyyMMddTHHmmssfffZ')
$sessionOutput = Join-Path $output ("probe-$sessionId")
if (Test-Path -LiteralPath $sessionOutput) {
    throw "Probe session directory already exists; refusing to reuse artifacts: $sessionOutput"
}
New-Item -ItemType Directory -Force -Path $sessionOutput | Out-Null

function Get-BandStart {
    param([int] $Band, [int] $Round)
    if ($availableWindowStart -eq 0) { return 0 }
    $bandRatio = if ($Bands -eq 1) { 0.5 } else { $Band / [double]($Bands - 1) }
    $base = [int][Math]::Round($availableWindowStart * $bandRatio)
    $roundStride = [int][Math]::Max(1, [Math]::Floor($availableWindowStart / [double]($Rounds + 1)))
    $shifted = $base + (($Round - 1) * $roundStride)
    if ($shifted -le $availableWindowStart) { return $shifted }
    return [Math]::Max(0, $base - (($Round - 1) * $roundStride))
}

for ($round = 1; $round -le $Rounds; $round++) {
    foreach ($size in $uniqueSizes) {
        for ($band = 0; $band -lt $Bands; $band++) {
            $runName = ('size-{0:D3}-round-{1:D2}-band-{2:D2}' -f $size, $round, ($band + 1))
            $runDirectory = Join-Path $sessionOutput $runName
            New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null
            $probeSource = Join-Path $runDirectory 'probe-source.txt'
            $start = Get-BandStart $band $round
            $length = [Math]::Min($ProbeWindowCharacters, $sourceText.Length - $start)
            [IO.File]::WriteAllText($probeSource, $sourceText.Substring($start, $length), $sourceEncoding)
            $logPath = Join-Path $runDirectory 'launcher.log'

            $runnerArgs = @(
                '--headless-batch-verify',
                '--mode', 'native',
                '--output-dir', $runDirectory,
                '--flat-output',
                '--model-dir', $modelDirectoryResolved,
                '--model-name', $ModelName,
                '--backend', $Backend,
                '--text-file', $probeSource,
                '--language', $Language,
                '--speaker', $Speaker,
                '--instruction', $Instruction,
                '--chunk-characters', $size,
                '--max-chunks', $ChunksPerBand
            )
            $runnerArgumentString = ($runnerArgs | ForEach-Object { Quote-AgentArgument ([string] $_) }) -join ' '
            $gradleArguments = @(
                "-PnativeRuntimeRoot=$nativeRoot",
                ':composeApp:run',
                '--no-daemon',
                '--console', 'plain',
                "--args=$runnerArgumentString"
            )

            Write-Host "=== Chinese/Vivian probe: $runName ==="
            & .\gradlew.bat @gradleArguments 2>&1 | Tee-Object -FilePath $logPath
            $exitCode = $LASTEXITCODE
            $manifestPath = Join-Path $runDirectory 'manifest.json'
            $reportPath = Join-Path $runDirectory 'batch-verification-report.json'
            $manifest = $null
            $report = $null
            if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
                $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
            }
            if (Test-Path -LiteralPath $reportPath -PathType Leaf) {
                $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
            }

            $chunks = @($manifest.chunks)
            $invalid = @($chunks | Where-Object {
                $_.status -ne 'COMPLETE' -or $_.validationPassed -ne $true
            })
            $completeCount = @($chunks | Where-Object { $_.status -eq 'COMPLETE' }).Count
            $manifestExpectedChunkCount = $null
            if ($null -ne $manifest -and $null -ne $manifest.expectedChunkCount) {
                $manifestExpectedChunkCount = [int]$manifest.expectedChunkCount
            }
            $reportExpectedChunkCount = $null
            if ($null -ne $report -and $null -ne $report.expectedChunkCount) {
                $reportExpectedChunkCount = [int]$report.expectedChunkCount
            }
            $countsConsistent = $chunks.Count -gt 0 -and
                $manifestExpectedChunkCount -eq $chunks.Count -and
                $reportExpectedChunkCount -eq $chunks.Count -and
                $chunks.Count -le $ChunksPerBand

            $agentInputPath = Join-Path $runDirectory 'agent-input.txt'
            $selectedInputText = $null
            if (Test-Path -LiteralPath $agentInputPath -PathType Leaf) {
                $selectedInputText = [IO.File]::ReadAllText($agentInputPath, $sourceEncoding)
            }
            $selectedInputCharacters = if ($null -ne $selectedInputText) { $selectedInputText.Length } else { 0 }
            $inputPrefixMatches = $null -ne $selectedInputText -and
                $selectedInputCharacters -gt 0 -and
                $selectedInputCharacters -le $length -and
                $sourceText.Substring($start, $selectedInputCharacters) -ceq $selectedInputText
            $combinedPath = Join-Path $runDirectory 'combined.wav'
            $combinedExists = Test-Path -LiteralPath $combinedPath -PathType Leaf
            $probeErrors = [System.Collections.Generic.List[string]]::new()
            if (-not $countsConsistent) { $probeErrors.Add('runner chunk counts are missing, inconsistent, or exceed the bounded sample') }
            if (-not $inputPrefixMatches) { $probeErrors.Add('agent-input.txt is not the exact prefix of the intended probe window') }
            if (-not $combinedExists) { $probeErrors.Add('combined.wav is missing') }
            $passed = $chunks.Count -gt 0 -and
                $invalid.Count -eq 0 -and
                $completeCount -eq $chunks.Count -and
                $exitCode -eq 0 -and
                $countsConsistent -and
                $inputPrefixMatches -and
                $combinedExists
            $telemetry = $report.telemetry
            $records.Add([pscustomobject]@{
                size = $size
                round = $round
                band = $band + 1
                run = $runName
                exitCode = $exitCode
                status = if ($report) { $report.status } else { 'process-failed' }
                passed = $passed
                expectedChunks = $chunks.Count
                completeChunks = $completeCount
                invalidChunks = $invalid.Count
                manifestExpectedChunks = $manifestExpectedChunkCount
                reportExpectedChunks = $reportExpectedChunkCount
                maxChunks = $ChunksPerBand
                sourceWindowStart = $start
                sourceWindowCharacters = $length
                selectedInputCharacters = $selectedInputCharacters
                selectedInputCoverageRatio = if ($length -gt 0) { [Math]::Round($selectedInputCharacters / [double]$length, 4) } else { 0 }
                selectedInputMatchesWindowPrefix = $inputPrefixMatches
                combinedExists = $combinedExists
                nativeFailures = @($chunks | Where-Object { $_.status -ne 'COMPLETE' }).Count
                capFailures = Get-MessageCount $chunks 'aggregate safe audio budget|audio budget|truncat|cap'
                asrFailures = Get-MessageCount $chunks 'ASR|similarity'
                deterministicFailures = Get-MessageCount $chunks 'deterministic|checksum|WAV'
                generationMillis = if ($telemetry) { $telemetry.generation.totalElapsedMillis } else { $null }
                validationMillis = if ($telemetry) { $telemetry.validation.totalElapsedMillis } else { $null }
                deviceUsedHighWaterBytes = if ($telemetry) { $telemetry.memory.deviceBackend.usedBytesHighWater } else { $null }
                reportPath = $reportPath
                manifestPath = $manifestPath
                logPath = $logPath
                error = if ($probeErrors.Count -gt 0) { $probeErrors -join '; ' } elseif ($report) { $report.error } else { "Process exited with code $exitCode. See $logPath" }
            })
        }
    }
}

$summaries = foreach ($size in $uniqueSizes) {
    $rows = @($records | Where-Object { $_.size -eq $size })
    $passedRows = @($rows | Where-Object passed)
    [pscustomobject]@{
        chunkCharacters = $size
        runs = $rows.Count
        passedRuns = $passedRows.Count
        passRate = if ($rows.Count -gt 0) { [Math]::Round($passedRows.Count / [double]$rows.Count, 4) } else { 0 }
        totalInvalidChunks = [int](($rows | Measure-Object invalidChunks -Sum).Sum)
        totalNativeFailures = [int](($rows | Measure-Object nativeFailures -Sum).Sum)
        totalCapFailures = [int](($rows | Measure-Object capFailures -Sum).Sum)
        totalAsrFailures = [int](($rows | Measure-Object asrFailures -Sum).Sum)
        totalDeterministicFailures = [int](($rows | Measure-Object deterministicFailures -Sum).Sum)
        totalGenerationMillis = [long](($rows | Where-Object { $_.generationMillis -ne $null } | Measure-Object generationMillis -Sum).Sum)
        totalValidationMillis = [long](($rows | Where-Object { $_.validationMillis -ne $null } | Measure-Object validationMillis -Sum).Sum)
        maxDeviceUsedHighWaterBytes = ($rows | Where-Object { $_.deviceUsedHighWaterBytes -ne $null } | Measure-Object deviceUsedHighWaterBytes -Maximum).Maximum
    }
}

$eligible = @($summaries | Where-Object {
    $_.runs -gt 0 -and
        $_.passedRuns -eq $_.runs -and
        $_.totalNativeFailures -eq 0 -and
        $_.totalCapFailures -eq 0 -and
        $_.totalAsrFailures -eq 0
} | Sort-Object chunkCharacters -Descending)
$recommended = $eligible | Select-Object -First 1
$summary = [pscustomobject]@{
    schemaVersion = 2
    startedAt = $startedAt.ToString('o')
    finishedAt = (Get-Date).ToUniversalTime().ToString('o')
    probeSessionDirectory = $sessionOutput
    sourceText = $source
    sourceCharacterCount = $sourceText.Length
    chunkLengthUnit = 'UTF-16 code units (Kotlin String.length / .NET String.Length)'
    backend = $Backend
    language = $Language
    speaker = $Speaker
    instruction = $Instruction
    candidates = $uniqueSizes
    rounds = $Rounds
    bands = $Bands
    chunksPerBand = $ChunksPerBand
    probeWindowCharacters = $ProbeWindowCharacters
    boundedSampleChunksPerRun = $ChunksPerBand
    boundedSampleRequiresExactWindowPrefix = $true
    recoveryFallbackCharacters = 40
    recommendedChunkCharacters = if ($recommended) { $recommended.chunkCharacters } else { $null }
    recommendationStatus = if ($recommended) { 'all-rounds-passed' } else { 'no-candidate-passed-all-rounds' }
    summaries = @($summaries)
    runs = @($records)
}
$summaryPath = Join-Path $output 'chunk-size-probe-report.json'
$summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $summaryPath -Encoding UTF8

Write-Host "CHUNK_SIZE_PROBE_REPORT=$summaryPath"
Write-Host "CHUNK_SIZE_PROBE_RECOMMENDATION=$($summary.recommendedChunkCharacters)"
if (-not $recommended) {
    Write-Host 'CHUNK_SIZE_PROBE_STATUS=no-candidate-passed-all-rounds'
    exit 1
}
Write-Host 'CHUNK_SIZE_PROBE_STATUS=passed'
exit 0
