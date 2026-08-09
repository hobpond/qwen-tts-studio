[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BundlePath
)

$ErrorActionPreference = "Stop"
$bundle = (Resolve-Path -LiteralPath $BundlePath).Path
$repositoryRoot = Split-Path (Split-Path $bundle -Parent) -Parent
$errors = [System.Collections.Generic.List[string]]::new()
$concepts = @{}

function Add-Error([string]$Message) {
    $script:errors.Add($Message)
}

function Get-Relative([string]$Path) {
    return $Path.Substring($bundle.Length + 1).Replace('\', '/')
}

function Test-ConceptTarget([string]$SourceRelative, [string]$Target) {
    $clean = $Target.Split('#')[0].Split('?')[0]
    if ([string]::IsNullOrWhiteSpace($clean) -or $clean -match '^(https?|mailto):') { return }

    # Bundle-root absolute links are the preferred OKF form. Repository-source
    # links that intentionally leave the bundle are allowed and remain useful
    # when this bundle is read from its containing repository.
    if ($clean.StartsWith('/')) {
        $candidate = Join-Path $bundle ($clean.TrimStart('/').Replace('/', '\'))
    } else {
        $sourceDir = Split-Path (Join-Path $bundle $SourceRelative) -Parent
        $candidate = Join-Path $sourceDir ($clean.Replace('/', '\'))
        try {
            $resolved = [IO.Path]::GetFullPath($candidate)
            if (-not $resolved.StartsWith($bundle, [StringComparison]::OrdinalIgnoreCase)) { return }
        } catch { return }
    }

    if (-not (Test-Path -LiteralPath $candidate) -and $clean.StartsWith('/')) {
        # This project keeps source-code links in concept bodies. They are
        # repository-relative references, not bundle concepts, so accept them
        # when the containing repository provides the target.
        $repositoryCandidate = Join-Path $repositoryRoot ($clean.TrimStart('/').Replace('/', '\'))
        if (Test-Path -LiteralPath $repositoryCandidate) { return }
    }
    if (-not (Test-Path -LiteralPath $candidate)) {
        Add-Error "${SourceRelative}: unresolved bundle link '${Target}'"
    }
}

Get-ChildItem -LiteralPath $bundle -Recurse -File -Filter '*.md' | ForEach-Object {
    $relative = Get-Relative $_.FullName
    $lines = Get-Content -LiteralPath $_.FullName
    $reserved = $_.Name -in @('index.md', 'log.md')

    if (-not $reserved) {
        if ($lines.Count -lt 3 -or $lines[0] -ne '---') {
            Add-Error "${relative}: missing YAML frontmatter"
        } else {
            $closing = [Array]::IndexOf($lines, '---', 1)
            if ($closing -lt 0) {
                Add-Error "${relative}: unterminated YAML frontmatter"
            } elseif (-not ($lines[1..($closing - 1)] -match '^type:\s*\S')) {
                Add-Error "${relative}: frontmatter requires a non-empty type"
            }
        }
        $concepts[$relative] = $true
    } elseif ($_.Name -eq 'index.md' -and $relative -ne 'index.md' -and $lines.Count -gt 0 -and $lines[0] -eq '---') {
        Add-Error "${relative}: only the bundle-root index.md may carry okf_version frontmatter"
    }

    foreach ($line in $lines) {
        $matches = [regex]::Matches($line, '\[[^\]]+\]\(([^)]+)\)')
        foreach ($match in $matches) {
            Test-ConceptTarget $relative $match.Groups[1].Value
        }
    }
}

$rootIndex = Join-Path $bundle 'index.md'
if (-not (Test-Path -LiteralPath $rootIndex)) { Add-Error 'Bundle is missing root index.md' }
if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output "OKF validation passed: $($concepts.Count) concept files in $bundle"
