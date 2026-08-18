param(
    [Parameter(Mandatory = $true)] [string] $ManifestPath,
    [Parameter(Mandatory = $true)] [string] $AsrModelPath,
    [Parameter(Mandatory = $true)] [string] $OutputDirectory,
    [string] $TtsModelDirectory,
    [string] $TtsModelName,
    [string] $NativeRuntimeRoot,
    [ValidateSet('cuda', 'cpu')] [string] $Backend = 'cuda',
    [switch] $IncludeCpu,
    [int] $MaxChunks = 8,
    [int] $WarmupRounds = 1,
    [int] $Rounds = 3,
    [int] $Threads = 4,
    [int] $TimeoutMs = 120000
)

$ErrorActionPreference = 'Stop'

function Resolve-RequiredFile([string] $PathValue, [string] $Label) {
    $resolved = Resolve-Path -LiteralPath $PathValue -ErrorAction Stop
    if (-not (Test-Path -LiteralPath $resolved.Path -PathType Leaf)) {
        throw "$Label is not a file: $($resolved.Path)"
    }
    return $resolved.Path
}

function Resolve-RequiredDirectory([string] $PathValue, [string] $Label) {
    $resolved = Resolve-Path -LiteralPath $PathValue -ErrorAction Stop
    if (-not (Test-Path -LiteralPath $resolved.Path -PathType Container)) {
        throw "$Label is not a directory: $($resolved.Path)"
    }
    return $resolved.Path
}

function Quote-AgentArgument([string] $Value) {
    return '"' + $Value.Replace('"', '\"') + '"'
}

$manifest = Resolve-RequiredFile $ManifestPath 'Manifest'
$asrModel = Resolve-RequiredFile $AsrModelPath 'ASR model'
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $output | Out-Null
$nativeRoot = $null
if ($NativeRuntimeRoot) {
    $nativeRoot = Resolve-RequiredDirectory $NativeRuntimeRoot 'Native runtime root'
}

$profiles = [System.Collections.Generic.List[string]]::new()
$profiles.Add('serial-cuda-1')
$profiles.Add('parallel-cuda-2')
$profiles.Add('parallel-cuda-3')
$profiles.Add('reuse-cuda-2')
if ($IncludeCpu) {
    $profiles.Add('serial-cpu-1')
    $profiles.Add('parallel-cpu-2')
}
if ($TtsModelDirectory) {
    $ttsDirectory = Resolve-RequiredDirectory $TtsModelDirectory 'TTS model directory'
    $profiles.Add('tts-resident-asr-2')
    $profiles.Add('tts-active-asr-1')
} else {
    $ttsDirectory = $null
}

$results = [System.Collections.Generic.List[object]]::new()
foreach ($profile in $profiles) {
    $profileDirectory = Join-Path $output $profile
    New-Item -ItemType Directory -Force -Path $profileDirectory | Out-Null

    $runnerArgs = @(
        '--agent-asr-concurrency',
        '--profile', $profile,
        '--manifest', (Quote-AgentArgument $manifest),
        '--asr-model', (Quote-AgentArgument $asrModel),
        '--backend', $Backend,
        '--output-dir', (Quote-AgentArgument $profileDirectory),
        '--max-chunks', $MaxChunks,
        '--warmup-rounds', $WarmupRounds,
        '--rounds', $Rounds,
        '--threads', $Threads,
        '--timeout-ms', $TimeoutMs
    )
    if ($ttsDirectory) {
        $runnerArgs += @('--tts-model-dir', (Quote-AgentArgument $ttsDirectory))
        if ($TtsModelName) {
            $runnerArgs += @('--tts-model-name', (Quote-AgentArgument $TtsModelName))
        }
    }
    $runnerArgumentString = ($runnerArgs -join ' ')
    $logPath = Join-Path $profileDirectory 'launcher.log'
    $gradleArguments = @(':composeApp:run', '--no-daemon', "--args=$runnerArgumentString")
    if ($nativeRoot) {
        $gradleArguments = @("-PnativeRuntimeRoot=$nativeRoot") + $gradleArguments
    }

    Write-Host "=== ASR concurrency profile: $profile ==="
    & .\gradlew.bat @gradleArguments 2>&1 | Tee-Object -FilePath $logPath
    $exitCode = $LASTEXITCODE
    $reportPath = Join-Path $profileDirectory 'asr-concurrency-report.json'
    $report = $null
    if (Test-Path -LiteralPath $reportPath -PathType Leaf) {
        $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
    }

    $results.Add([pscustomobject]@{
        profile = $profile
        exitCode = $exitCode
        status = if ($report) { $report.status } else { 'process-failed' }
        profilePassed = if ($report -and $report.decision) { [bool]$report.decision.profilePassed } else { $false }
        eligibleForConcurrentAsr = if ($report -and $report.decision) { [bool]$report.decision.eligibleForConcurrentAsr } else { $false }
        minimumFreeBytes = if ($report -and $report.decision) { $report.decision.minimumFreeBytes } else { $null }
        speedupRatio = if ($report -and $report.decision) { $report.decision.speedupRatio } else { $null }
        reportPath = $reportPath
        error = if ($report) { $report.error } else { "Process exited with code $exitCode. See $logPath" }
    })
}

$twoWay = $results | Where-Object { $_.profile -eq 'parallel-cuda-2' } | Select-Object -First 1
$eligible = [bool]($twoWay -and $twoWay.eligibleForConcurrentAsr)
$summary = [pscustomobject]@{
    schemaVersion = 1
    startedAt = $null
    finishedAt = (Get-Date).ToUniversalTime().ToString('o')
    manifestPath = $manifest
    asrModelPath = $asrModel
    backend = $Backend
    recommendedMaxConcurrentAsr = if ($eligible) { 2 } else { 1 }
    concurrentAsrGatePassed = $eligible
    profiles = @($results)
}
$summaryPath = Join-Path $output 'matrix-summary.json'
$summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $summaryPath -Encoding UTF8

Write-Host "ASR_CONCURRENCY_MATRIX=$summaryPath"
Write-Host "ASR_CONCURRENCY_RECOMMENDED_MAX=$($summary.recommendedMaxConcurrentAsr)"
if (-not $eligible) {
    Write-Host 'ASR_CONCURRENCY_GATE=not-eligible'
    exit 1
}
Write-Host 'ASR_CONCURRENCY_GATE=passed'
exit 0
