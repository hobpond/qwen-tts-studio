param(
    [Parameter(Mandatory = $true)] [string] $ManifestPath,
    [string] $SourceTextPath
)

$ErrorActionPreference = 'Stop'
$manifestFile = (Resolve-Path -LiteralPath $ManifestPath).Path
$directory = Split-Path -Parent $manifestFile
$manifest = Get-Content -LiteralPath $manifestFile -Raw -Encoding UTF8 | ConvertFrom-Json
$findings = [System.Collections.Generic.List[string]]::new()

$chunks = @($manifest.chunks | Sort-Object index)
$expectedIndexes = 0..([int]$manifest.expectedChunkCount - 1)
$actualIndexes = @($chunks | ForEach-Object { [int]$_.index })
if (($actualIndexes -join ',') -ne ($expectedIndexes -join ',')) {
    $findings.Add('ERROR INDEX_GAP manifest indexes are not contiguous.')
}

$joined = [Text.StringBuilder]::new()
foreach ($chunk in $chunks) {
    $index = '{0:D6}' -f [int]$chunk.index
    $textPath = Join-Path $directory "chunk-$index.txt"
    $wavPath = Join-Path $directory "chunk-$index.wav"
    if (-not (Test-Path -LiteralPath $textPath)) {
        $findings.Add("ERROR TEXT_SIDECAR_MISSING chunk=$($chunk.index)")
    } else {
        $text = [IO.File]::ReadAllText($textPath, [Text.Encoding]::UTF8)
        [void]$joined.Append($text)
        if ($text -cne [string]$chunk.text) { $findings.Add("ERROR TEXT_MANIFEST_MISMATCH chunk=$($chunk.index)") }
    }
    if ([string]$chunk.status -eq 'COMPLETE') {
        if (-not (Test-Path -LiteralPath $wavPath)) {
            $findings.Add("ERROR WAV_MISSING chunk=$($chunk.index)")
            continue
        }
        $bytes = [IO.File]::ReadAllBytes($wavPath)
        if ($bytes.Length -lt 44 -or [Text.Encoding]::ASCII.GetString($bytes, 0, 4) -ne 'RIFF' -or [Text.Encoding]::ASCII.GetString($bytes, 8, 4) -ne 'WAVE') {
            $findings.Add("ERROR WAV_INVALID chunk=$($chunk.index)")
        }
        $hash = (Get-FileHash -LiteralPath $wavPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -ne [string]$chunk.sha256) { $findings.Add("ERROR WAV_CHECKSUM_MISMATCH chunk=$($chunk.index)") }
        if ([int64]$chunk.frameCount -ge 4096) { $findings.Add("WARNING AUDIO_CAP_REACHED chunk=$($chunk.index)") }
    } else {
        $findings.Add("WARNING CHUNK_NOT_COMPLETE chunk=$($chunk.index) status=$($chunk.status)")
    }
}

if ($SourceTextPath) {
    $source = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $SourceTextPath), [Text.Encoding]::UTF8).Replace("`r`n", "`n").Replace("`r", "`n")
    if ($joined.ToString() -cne $source) { $findings.Add('ERROR SOURCE_ROUND_TRIP_FAILED concatenated sidecars differ from normalized source.') }
}

if ($findings.Count -eq 0) { $findings.Add('INFO VALID deterministic validation passed.') }
$findings | ForEach-Object { Write-Output $_ }
if ($findings | Where-Object { $_ -like 'ERROR *' }) { exit 1 }
