param(
    [Parameter(Mandatory = $true)]
    [string] $Manifest,
    [string] $Audio = '',
    [string] $OutputDirectory = '',
    [string] $OutputName = 'audiobook-sweep.mp4',
    [double] $DurationLimit = 0,
    [int] $CharsPerLine = 20,
    [int] $LinesOnScreen = 7,
    [int] $Width = 1280,
    [int] $Height = 720,
    [int] $Fps = 24,
    [string] $FontName = 'Microsoft YaHei',
    [int] $FontSize = 36,
    [double] $SegmentDuration = 600,
    [int] $SegmentWorkers = 2,
    [string] $AudioBitrate = '96k',
    [switch] $Overwrite,
    [switch] $KeepSegments,
    [switch] $Streamable,
    [switch] $AllowUnvalidated,
    [string] $Python = ''
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

$manifestPath = Resolve-RequiredFile $Manifest 'Manifest'
$runner = Join-Path $PSScriptRoot 'render-audiobook-video.py'

if ([string]::IsNullOrWhiteSpace($Python)) {
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($null -eq $pythonCommand) {
        $pythonCommand = Get-Command py -ErrorAction SilentlyContinue
    }
    if ($null -eq $pythonCommand) {
        throw 'Python was not found. Pass -Python with the path to a Python 3.10+ executable.'
    }
    $Python = $pythonCommand.Source
}

$arguments = @(
    $runner,
    '--manifest', $manifestPath,
    '--output-name', $OutputName,
    '--chars-per-line', $CharsPerLine,
    '--lines-on-screen', $LinesOnScreen,
    '--width', $Width,
    '--height', $Height,
    '--fps', $Fps,
    '--font-name', $FontName,
    '--font-size', $FontSize,
    '--segment-duration', $SegmentDuration,
    '--segment-workers', $SegmentWorkers,
    '--audio-bitrate', $AudioBitrate
)
if (-not [string]::IsNullOrWhiteSpace($Audio)) {
    $arguments += @('--audio', (Resolve-RequiredFile $Audio 'Audio'))
}
if (-not [string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $arguments += @('--output-dir', [IO.Path]::GetFullPath($OutputDirectory))
}
if ($DurationLimit -gt 0) {
    $arguments += @('--duration-limit', $DurationLimit)
}
if ($Overwrite) {
    $arguments += '--overwrite'
}
if ($KeepSegments) {
    $arguments += '--keep-segments'
}
if ($Streamable) {
    $arguments += '--streamable'
}
if ($AllowUnvalidated) {
    $arguments += '--allow-unvalidated'
}

Write-Host "Rendering manifest-backed audiobook sweep from $manifestPath"
& $Python @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Audiobook video renderer failed with exit code $LASTEXITCODE."
}
