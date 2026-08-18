param(
    [string] $SourceText = 'D:\work\qwen-tts-studio\build\chinese-vivian-20260816\source.txt',
    [string] $OutputDirectory = 'D:\work\qwen-tts-studio\build\chinese-vivian-20260816\full-vivian-zh',
    [string] $ModelDirectory = 'D:\qwen-tts-studio\models',
    [string] $ModelName = 'qwen-talker-1.7b-customvoice-Q8_0.gguf',
    [string] $NativeRuntimeRoot = 'D:\work\qwen-tts-studio\artifacts\native-cuda-runtime-asr',
    [ValidateSet('cuda', 'cpu')] [string] $Backend = 'cuda',
    [string] $Language = 'Chinese',
    [string] $Speaker = 'Vivian',
    [string] $Instruction = 'Warm,clear,Mandarin,audiobook,narration,measured,pacing,crisp,diction,natural,pauses,gentle,emotion,restrained,character,distinction.',
    # 0 selects the profile-aware default below. Explicit values remain
    # available for controlled comparison runs.
    [int] $ChunkCharacters = 0,
    [int] $MaxChunks = 0,
    [switch] $Resume,
    [switch] $RechunkFailed,
    [int] $RechunkCharacters = 40
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

$source = Resolve-RequiredFile $SourceText 'Source text'
$modelDirectoryResolved = Resolve-RequiredDirectory $ModelDirectory 'Model directory'
$model = Resolve-RequiredFile (Join-Path $modelDirectoryResolved $ModelName) 'TTS model'
$nativeRoot = Resolve-RequiredDirectory $NativeRuntimeRoot 'Native runtime root'

if ($RechunkFailed -and -not $Resume) {
    throw 'RechunkFailed requires Resume so the existing manifest remains the control plane.'
}
if ($RechunkCharacters -le 0) {
    throw 'RechunkCharacters must be greater than zero.'
}
if ($Resume -and $MaxChunks -gt 0) {
    throw 'MaxChunks cannot be used with Resume; the manifest is the control plane.'
}

if (-not $Resume -and $ChunkCharacters -le 0) {
    if ($PSBoundParameters.ContainsKey('ChunkCharacters')) {
        throw 'ChunkCharacters must be greater than zero when supplied explicitly.'
    }

    # The current Chinese/Vivian Qwen custom-voice profile uses 80 source
    # characters as its normal target. A repeated stratified probe showed
    # that 80 is materially more efficient than 40 while remaining within
    # the observed reliability envelope; 40 remains the recovery fallback
    # for chunks that fail cap or ASR validation.
    $isChineseVivian = $Language -match '^(?i:Chinese|Mandarin)$' -and
        $Speaker -match '^(?i:Vivian)$'
    $ChunkCharacters = if ($isChineseVivian) { 80 } else { 1200 }
}
if ($MaxChunks -lt 0) {
    throw 'MaxChunks cannot be negative.'
}

$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $output | Out-Null

$runnerArgs = @(
    '--headless-batch-verify',
    '--mode', 'native',
    '--output-dir', $output,
    '--flat-output'
)

if (-not $Resume) {
    $runnerArgs += @(
        '--model-dir', $modelDirectoryResolved,
        '--model-name', $ModelName,
        '--backend', $Backend,
        '--text-file', $source,
        '--language', $Language,
        '--speaker', $Speaker,
        '--instruction', $Instruction,
        '--chunk-characters', $ChunkCharacters
    )
} else {
    # The durable manifest supplies model, voice, prompt, language, and chunk
    # boundaries. The source file is retained only as the full-file validation
    # reference; it is never used to rebuild the replay plan.
    $runnerArgs += @('--text-file', $source)
}

if ($MaxChunks -gt 0) {
    $runnerArgs += @('--max-chunks', $MaxChunks)
}

if ($Resume) {
    $manifest = Join-Path $output 'manifest.json'
    if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) {
        throw "Resume requested but manifest was not found: $manifest"
    }
    $runnerArgs += @('--manifest', $manifest, '--resume')
    if ($RechunkFailed) {
        $runnerArgs += @('--rechunk-failed', '--rechunk-characters', $RechunkCharacters)
    }
}

$runnerArgumentString = ($runnerArgs | ForEach-Object { Quote-AgentArgument ([string] $_) }) -join ' '
$gradleArguments = @(
    "-PnativeRuntimeRoot=$nativeRoot",
    ':composeApp:run',
    '--no-daemon',
    "--args=$runnerArgumentString"
)

Write-Host "Starting manifest-driven native $Backend Vivian batch."
Write-Host "Source: $source"
Write-Host "Output: $output"
Write-Host "Chunk characters: $(if ($RechunkFailed) { $RechunkCharacters } else { $ChunkCharacters })"
Write-Host "Chunking profile: $(if ($RechunkFailed) { 'failed-chunk-recovery' } elseif ($PSBoundParameters.ContainsKey('ChunkCharacters')) { 'explicit' } else { 'auto' })"
Write-Host "Language: $Language"
Write-Host "Speaker: $Speaker"
Write-Host "Instruction: $Instruction"

& .\gradlew.bat @gradleArguments
if ($LASTEXITCODE -ne 0) {
    throw "Gradle batch runner failed with exit code $LASTEXITCODE."
}

Write-Host "Batch runner completed: $output"
