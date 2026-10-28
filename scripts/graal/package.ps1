# package.ps1 — Package the JMine JRE for distribution
#
# Usage:
#   pwsh -File package.ps1 [-JrePath <path>] [-Output <path>] [-Version <semver>]
#
# Creates a compressed archive (zip on Windows, tar.gz on Linux/macOS) and SHA256 checksum.

param(
    [string]$JrePath = "../../output/jmine-jre",
    [string]$Output = "../../output",
    [string]$Version = "0.1.0"
)

$ErrorActionPreference = "Stop"

Write-Host ("=" * 60)
Write-Host "Packaging JMine JRE"
Write-Host ("=" * 60)

# ── Resolve paths ────────────────────────────────────────────────
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$jrePath = if ($JrePath.StartsWith('.')) { (Resolve-Path (Join-Path $scriptDir $JrePath)).Path } else { $JrePath }
$outputPath = if ($Output.StartsWith('.')) { (Resolve-Path (Join-Path $scriptDir $Output)).Path } else { $Output }

if (-not (Test-Path $jrePath)) {
    Write-Host "[ERROR] JRE not found at $jrePath. Run build-jre.ps1 first."
    exit 1
}

# ── Detect platform ──────────────────────────────────────────────
$platform = if ($IsWindows) { "windows" } elseif ($IsMacOS) { "macos" } else { "linux" }
$arch = if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64" -or $env:PROCESSOR_ARCHITEW6432 -eq "ARM64") { "arm64" } else { "x64" }

$archiveName = "jmine-jre-$Version-$platform-$arch"

# ── Compress ─────────────────────────────────────────────────────
if ($IsWindows) {
    $archivePath = Join-Path $outputPath "$archiveName.zip"
    Write-Host "[ZIP] Creating $archiveName.zip..."
    Compress-Archive -Path $jrePath -DestinationPath $archivePath -CompressionLevel Optimal
} else {
    $archivePath = Join-Path $outputPath "$archiveName.tar.gz"
    Write-Host "[TAR] Creating $archiveName.tar.gz..."
    $jreParent = Split-Path $jrePath -Parent
    $jreName = Split-Path $jrePath -Leaf
    tar -czf $archivePath -C $jreParent $jreName
}

if (-not (Test-Path $archivePath)) {
    Write-Host "[ERROR] Archive creation failed"
    exit 1
}

# ── SHA256 checksum ──────────────────────────────────────────────
Write-Host "[SHA256] Computing checksum..."
$sha256 = (Get-FileHash -Path $archivePath -Algorithm SHA256).Hash
$checksumFile = Join-Path $outputPath "$archiveName.sha256"
"$sha256  $archiveName.zip" | Out-File -FilePath $checksumFile -Encoding ASCII

$size = (Get-Item $archivePath).Length / 1MB
Write-Host "[OK] Package: $archivePath ($size.ToString('F1') MB)"
Write-Host "[OK] Checksum: $checksumFile"
Write-Host "[SHA256] $sha256"
