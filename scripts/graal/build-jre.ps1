# build-jre.ps1 — Assemble the JMine JRE using GraalVM jlink
#
# Usage:
#   pwsh -File build-jre.ps1 [-Output <path>] [-JavaHome <path>]
#
# Creates a custom JRE with jlink, then embeds native libraries (mimalloc, KTX),
# the KTX bridge JAR, documentation, and scripts.

param(
    [string]$Output = "../../output/jmine-jre",
    [string]$JavaHome = "",
    [string]$ArtifactsDir = "../../output"
)

$ErrorActionPreference = "Stop"

Write-Host ("=" * 60)
Write-Host "Assembling JMine JRE"
Write-Host ("=" * 60)

# ── Resolve paths ────────────────────────────────────────────────
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$outputPath = if ($Output.StartsWith('.')) { (Resolve-Path (Join-Path $scriptDir $Output)).Path } else { $Output }
$artifactsDir = if ($ArtifactsDir.StartsWith('.')) { (Resolve-Path (Join-Path $scriptDir $ArtifactsDir)).Path } else { $ArtifactsDir }

# ── Detect platform ──────────────────────────────────────────────
$platform = if ($IsWindows) { "windows" } elseif ($IsMacOS) { "macos" } else { "linux" }
$arch = if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64" -or $env:PROCESSOR_ARCHITEW6432 -eq "ARM64") { "arm64" } else { "x64" }

# ── Find Java ────────────────────────────────────────────────────
if (-not $JavaHome) {
    $JavaHome = $env:JAVA_HOME
}
if (-not $JavaHome) {
    $JavaHome = $env:JMINE_JAVA_HOME
}

if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome "bin/jlink.exe"))) {
    $javaCandidates = @(
        (Join-Path $scriptDir "../../graalvm-build"),
        (Join-Path $scriptDir "../../graalvm-cache/graalvm-jdk"),
        "C:\Program Files\Java\jdk-25"
    )
    foreach ($candidate in $javaCandidates) {
        $jlinkPath = Join-Path $candidate "bin/jlink.exe"
        if (-not $IsWindows) { $jlinkPath = Join-Path $candidate "bin/jlink" }
        if (Test-Path $jlinkPath) {
            $JavaHome = $candidate
            break
        }
    }
}

if (-not $JavaHome -or -not (Test-Path $JavaHome)) {
    Write-Host "[ERROR] Java 25+ JDK with jlink not found. Set JAVA_HOME or JMINE_JAVA_HOME."
    exit 1
}

Write-Host "[JAVA]  Found: $JavaHome"

$exe = if ($IsWindows) { ".exe" } else { "" }
$jlink = Join-Path $JavaHome "bin/jlink$exe"

if (-not (Test-Path $jlink)) {
    Write-Host "[ERROR] jlink not found at $jlink. Need full JDK, not JRE."
    exit 1
}

# ── Create JRE with jlink ────────────────────────────────────────
Write-Host "[JLINK] Creating custom JRE..."
& $jlink `
    --add-modules ALL-MODULE-PATH `
    --output $outputPath `
    --no-manifest `
    --strip-debug `
    --compress=2

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] jlink failed"
    exit 1
}

Write-Host "[OK] Base JRE created at $outputPath"

# ── Copy native libraries ────────────────────────────────────────
$jreBin = Join-Path $outputPath "bin"

# mimalloc
$mimallocDir = Join-Path $artifactsDir "mimalloc/$platform-$arch"
if (Test-Path $mimallocDir) {
    $ext = switch ($platform) {
        "windows" { @(".dll") }
        "linux"   { @(".so") }
        "macos"   { @(".dylib") }
    }
    Get-ChildItem -Path $mimallocDir -File -Include $ext -Recurse | ForEach-Object {
        Copy-Item $_.FullName (Join-Path $jreBin $_.Name)
        Write-Host "[COPY] $($_.Name) -> jre/bin/"
    }
} else {
    Write-Host "[WARN] mimalloc artifacts not found at $mimallocDir — skipping"
}

# KTX
$ktxDir = Join-Path $artifactsDir "ktx/$platform-$arch"
if (Test-Path $ktxDir) {
    $ext = switch ($platform) {
        "windows" { @(".dll") }
        "linux"   { @(".so") }
        "macos"   { @(".dylib") }
    }
    Get-ChildItem -Path $ktxDir -File -Include $ext -Recurse | ForEach-Object {
        Copy-Item $_.FullName (Join-Path $jreBin $_.Name)
        Write-Host "[COPY] $($_.Name) -> jre/bin/"
    }
} else {
    Write-Host "[WARN] KTX artifacts not found at $ktxDir — skipping"
}

# ── Copy KTX bridge JAR ──────────────────────────────────────────
$bridgeJar = Join-Path $artifactsDir "jmine-ktx-bridge.jar"
if (Test-Path $bridgeJar) {
    $jreLib = Join-Path $outputPath "lib"
    New-Item -ItemType Directory -Path $jreLib -Force | Out-Null
    Copy-Item $bridgeJar (Join-Path $jreLib "jmine-ktx-bridge.jar")
    Write-Host "[COPY] jmine-ktx-bridge.jar -> jre/lib/"
} else {
    Write-Host "[WARN] jmine-ktx-bridge.jar not found — skipping"
}

# ── Copy documentation ───────────────────────────────────────────
$docsDir = Join-Path $outputPath "docs"
New-Item -ItemType Directory -Path $docsDir -Force | Out-Null

$projectRoot = Join-Path $scriptDir ".."
foreach ($doc in @("jmine_config.md", "jmine_advanced_optimizations.md", "jmine_mod_compatibility.md")) {
    $src = Join-Path $projectRoot $doc
    if (Test-Path $src) {
        Copy-Item $src (Join-Path $docsDir $doc)
        Write-Host "[COPY] $doc -> jre/docs/"
    }
}

# ── Copy script.py ───────────────────────────────────────────────
$scriptPy = Join-Path $projectRoot "script.py"
if (Test-Path $scriptPy) {
    Copy-Item $scriptPy (Join-Path $outputPath "script.py")
    Write-Host "[COPY] script.py -> jre/"
}

# ── Summary ──────────────────────────────────────────────────────
$jreSize = (Get-ChildItem -Path $outputPath -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host "[OK] JMine JRE assembled at $outputPath ($jreSize.ToString('F1') MB)"
