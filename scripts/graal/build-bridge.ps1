# build-bridge.ps1 — Compile the Java KTX Bridge JAR
#
# Usage:
#   pwsh -File build-bridge.ps1 [-Source <path>] [-Output <path>] [-JavaHome <path>]
#
# Compiles all Java sources in jmine-ktx-bridge/ and packages them into a JAR with manifest.

param(
    [string]$Source = "../../jmine-ktx-bridge",
    [string]$Output = "../../output",
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"

Write-Host ("=" * 60)
Write-Host "Building Java KTX Bridge"
Write-Host ("=" * 60)

# ── Resolve paths ────────────────────────────────────────────────
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourcePath = if ($Source.StartsWith('.')) { (Resolve-Path (Join-Path $scriptDir $Source)).Path } else { $Source }
$outputPath = if ($Output.StartsWith('.')) { (Resolve-Path (Join-Path $scriptDir $Output)).Path } else { $Output }

if (-not (Test-Path $sourcePath)) {
    Write-Host "[ERROR] jmine-ktx-bridge not found at $sourcePath"
    exit 1
}

# ── Find Java ────────────────────────────────────────────────────
if (-not $JavaHome) {
    $JavaHome = $env:JAVA_HOME
}
if (-not $JavaHome) {
    $JavaHome = $env:JMINE_JAVA_HOME
}

# Try common GraalVM locations
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome "bin/javac.exe"))) {
    $javaCandidates = @(
        (Join-Path $scriptDir "../../graalvm-build"),
        (Join-Path $scriptDir "../../graalvm-cache/graalvm-jdk"),
        "C:\Program Files\Java\jdk-25"
    )
    foreach ($candidate in $javaCandidates) {
        if (Test-Path (Join-Path $candidate "bin/javac.exe")) {
            $JavaHome = $candidate
            break
        }
    }
}

if (-not $JavaHome) {
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) {
        $JavaHome = Split-Path (Split-Path $javac.Source)
    }
}

if (-not $JavaHome -or -not (Test-Path $JavaHome)) {
    Write-Host "[ERROR] Java 25+ JDK not found. Set JAVA_HOME or JMINE_JAVA_HOME."
    exit 1
}

Write-Host "[JAVA]  Found: $JavaHome"

$exe = if ($IsWindows) { ".exe" } else { "" }
$javac = Join-Path $JavaHome "bin/javac$exe"
$jar = Join-Path $JavaHome "bin/jar$exe"

# ── Compile ──────────────────────────────────────────────────────
$buildDir = Join-Path $sourcePath "build"
if (Test-Path $buildDir) { Remove-Item $buildDir -Recurse -Force }
New-Item -ItemType Directory -Path $buildDir -Force | Out-Null

$srcTexture = Join-Path $sourcePath "src/main/java/com/jmine/texture"
$srcPerf = Join-Path $sourcePath "src/main/java/com/jmine/perf"

$javaFiles = @()
if (Test-Path $srcTexture) {
    $javaFiles += Get-ChildItem -Path $srcTexture -Filter "*.java" -File
}
if (Test-Path $srcPerf) {
    $javaFiles += Get-ChildItem -Path $srcPerf -Filter "*.java" -File
}

if ($javaFiles.Count -eq 0) {
    Write-Host "[ERROR] No Java source files found"
    exit 1
}

Write-Host "[COMPILE] Compiling $($javaFiles.Count) Java files..."
& $javac --release 25 -d $buildDir ($javaFiles | ForEach-Object { $_.FullName })

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Compilation failed"
    exit 1
}

Write-Host "[OK] Compilation successful"

# ── Create JAR ───────────────────────────────────────────────────
$manifest = Join-Path $sourcePath "META-INF/MANIFEST.MF"
$jarPath = Join-Path $outputPath "jmine-ktx-bridge.jar"

if (Test-Path $manifest) {
    Write-Host "[JAR] Creating JAR with manifest..."
    & $jar cfm $jarPath $manifest -C $buildDir com
} else {
    Write-Host "[JAR] Creating JAR without manifest..."
    & $jar cf $jarPath -C $buildDir com
}

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] JAR creation failed"
    exit 1
}

$size = (Get-Item $jarPath).Length / 1KB
Write-Host "[OK] KTX bridge built: $jarPath ($size.ToString('F1') KB)"
