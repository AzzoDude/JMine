# build-bridge.ps1 — Compile the Java KTX Bridge JAR
#
# Usage:
#   pwsh -File build-bridge.ps1 [-Source <path>] [-Output <path>] [-JavaHome <path>]
#
# Downloads ASM library, compiles all Java sources in jmine-ktx-bridge/ with ASM
# on the classpath, and packages everything into a single fat JAR with manifest.
# ASM is embedded in the JAR so the bytecode transformers work at runtime.

param(
    [string]$Source = "../../jmine-ktx-bridge",
    [string]$Output = "../../output",
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"

Write-Host ("=" * 60)
Write-Host "Building Java KTX Bridge (with ASM)"
Write-Host ("=" * 60)

# ── Resolve paths ────────────────────────────────────────────────
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourcePath = if ($Source.StartsWith('.')) { (Resolve-Path (Join-Path $scriptDir $Source)).Path } else { $Source }
$outputPath = if ($Output.StartsWith('.')) { (Resolve-Path (Join-Path $scriptDir $Output)).Path } else { $Output }

if (-not (Test-Path $sourcePath)) {
    Write-Host "[ERROR] jmine-ktx-bridge not found at $sourcePath"
    exit 1
}

# ── Download ASM library ─────────────────────────────────────────
$asmVersion = "9.7.1"
$asmJarName = "asm-$asmVersion.jar"
$asmJarPath = Join-Path $outputPath $asmJarName

if (-not (Test-Path $asmJarPath)) {
    Write-Host "[DOWNLOAD] Fetching ASM $asmVersion from Maven Central..."
    $asmUrl = "https://repo1.maven.org/maven2/org/ow2/asm/asm/$asmVersion/asm-$asmVersion.jar"
    try {
        Invoke-WebRequest -Uri $asmUrl -OutFile $asmJarPath -ErrorAction Stop
        $asmSize = (Get-Item $asmJarPath).Length / 1KB
        Write-Host "[OK] ASM downloaded: $asmJarPath ($asmSize.ToString('F1') KB)"
    } catch {
        Write-Host "[ERROR] Failed to download ASM: $_"
        exit 1
    }
} else {
    Write-Host "[CACHE] Using cached ASM: $asmJarPath"
}

# ── Extract ASM classes for embedding ────────────────────────────
$asmExtractDir = Join-Path $outputPath "asm-classes"
if (Test-Path $asmExtractDir) { Remove-Item $asmExtractDir -Recurse -Force }
New-Item -ItemType Directory -Path $asmExtractDir -Force | Out-Null

Write-Host "[EXTRACT] Extracting ASM classes for embedding..."
& jar xf $asmJarPath -C $asmExtractDir org 2>$null
if ($LASTEXITCODE -ne 0) {
    # Fallback: use PowerShell to extract
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($asmJarPath, $asmExtractDir)
}
Write-Host "[OK] ASM classes extracted to $asmExtractDir"

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

Write-Host "[COMPILE] Compiling $($javaFiles.Count) Java files with ASM on classpath..."
$classpath = $asmJarPath
& $javac --release 25 -cp $classpath -d $buildDir ($javaFiles | ForEach-Object { $_.FullName })

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Compilation failed"
    exit 1
}

Write-Host "[OK] Compilation successful"

# ── Create fat JAR with ASM embedded ─────────────────────────────
$manifest = Join-Path $sourcePath "META-INF/MANIFEST.MF"
$jarPath = Join-Path $outputPath "jmine-ktx-bridge.jar"

# Remove old JAR if exists
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }

Write-Host "[JAR] Creating fat JAR with ASM embedded..."

# Create a temporary directory for JAR assembly
$jarStaging = Join-Path $outputPath "jar-staging"
if (Test-Path $jarStaging) { Remove-Item $jarStaging -Recurse -Force }
New-Item -ItemType Directory -Path $jarStaging -Force | Out-Null

# Copy compiled classes
if (Test-Path (Join-Path $buildDir "com")) {
    Copy-Item -Recurse (Join-Path $buildDir "com") (Join-Path $jarStaging "com")
}

# Copy ASM classes (embedded in the JAR)
if (Test-Path (Join-Path $asmExtractDir "org")) {
    Copy-Item -Recurse (Join-Path $asmExtractDir "org") (Join-Path $jarStaging "org")
    Write-Host "[JAR] ASM classes embedded in JAR"
}

# Copy manifest
if (Test-Path $manifest) {
    $metaInfDir = Join-Path $jarStaging "META-INF"
    New-Item -ItemType Directory -Path $metaInfDir -Force | Out-Null
    Copy-Item $manifest (Join-Path $metaInfDir "MANIFEST.MF")
}

# Build the JAR from staging directory
Push-Location $jarStaging
if (Test-Path (Join-Path $jarStaging "META-INF" "MANIFEST.MF")) {
    & $jar cfm $jarPath META-INF/MANIFEST.MF com org
} else {
    & $jar cf $jarPath com org
}
Pop-Location

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] JAR creation failed"
    exit 1
}

# Clean up staging and extracted ASM
if (Test-Path $jarStaging) { Remove-Item $jarStaging -Recurse -Force }
if (Test-Path $asmExtractDir) { Remove-Item $asmExtractDir -Recurse -Force }

$size = (Get-Item $jarPath).Length / 1KB
Write-Host "[OK] KTX bridge built: $jarPath ($size.ToString('F1') KB) — includes ASM $asmVersion"
