param(
    [string]$PortableExe = "",
    [string]$ModelDirectory = "",
    [string]$OutputDirectory = "",
    [switch]$Launch
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($PortableExe)) {
    $PortableExe = Join-Path $repoRoot "composeApp\build\compose\binaries\main\app\qwen-tts-studio\qwen-tts-studio.exe"
}
if ([string]::IsNullOrWhiteSpace($ModelDirectory)) {
    $ModelDirectory = Join-Path $env:USERPROFILE ".qwen-tts-studio\models"
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputDirectory = Join-Path $env:TEMP "qwen-tts-batch-smoke-$stamp"
}

if (-not (Test-Path -LiteralPath $PortableExe -PathType Leaf)) {
    throw "Portable executable not found: $PortableExe"
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$fixture = Join-Path $OutputDirectory "batch-input.txt"
[IO.File]::WriteAllLines(
    $fixture,
    @("Portable batch smoke test one.", "Portable batch smoke test two."),
    [Text.UTF8Encoding]::new($false)
)

$models = if (Test-Path -LiteralPath $ModelDirectory -PathType Container) {
    @(Get-ChildItem -LiteralPath $ModelDirectory -Filter *.gguf -File -ErrorAction SilentlyContinue)
} else {
    @()
}

Write-Output "Portable executable: $PortableExe"
Write-Output "Model directory: $ModelDirectory"
Write-Output "Text fixture: $fixture"
Write-Output "Output directory: $OutputDirectory"
Write-Output "Expected generated files: manifest.json, chunk-000000.wav, chunk-000001.wav"

if ($models.Count -eq 0) {
    Write-Warning "No GGUF model found. Select a model directory containing GGUF files in Setup before starting the batch."
    exit 2
}

if ($Launch) {
    Start-Process -FilePath $PortableExe -WorkingDirectory (Split-Path $PortableExe)
    Write-Output "Portable UI launched. Select the text fixture and output directory above, then start Batch generation."
}
