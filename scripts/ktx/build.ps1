# build.ps1 — Build KTX library with JNI bindings
#
# Usage:
#   pwsh -File build.ps1 [-Source <path>] [-Output <path>] [-JavaHome <path>]
#
# Clones (if needed), configures CMake with JNI, builds, installs, and copies artifacts.
# Target: KTX-Software v4.3.2

param(
    [string]$Source = "../../ktx-software",
    [string]$Output = "../../output/ktx",
    [string]$JavaHome = "",
    [switch]$Clone
)

$ErrorActionPreference = "Stop"

# ── Detect platform ──────────────────────────────────────────────
$platform = if ($IsWindows) { "windows" } elseif ($IsMacOS) { "macos" } else { "linux" }
$arch = if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64" -or $env:PROCESSOR_ARCHITEW6432 -eq "ARM64") { "arm64" } else { "x64" }

Write-Host ("=" * 60)
Write-Host "Building KTX library with JNI for $platform-$arch"
Write-Host ("=" * 60)

# ── Resolve paths ────────────────────────────────────────────────
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourcePath = if ($Source.StartsWith('.')) { (Resolve-Path (Join-Path $scriptDir $Source)).Path } else { $Source }
$outputPath = if ($Output.StartsWith('.')) { (Resolve-Path (Join-Path $scriptDir $Output)).Path } else { $Output }

# ── Clone if needed ──────────────────────────────────────────────
if ($Clone -or -not (Test-Path $sourcePath)) {
    if (-not (Test-Path $sourcePath)) {
        Write-Host "[INFO] ktx-software not found at $sourcePath"
        Write-Host "[CLONE] Cloning KTX-Software v4.3.2 (with submodules)..."
        $parent = Split-Path $sourcePath
        git -C $parent clone --recursive --branch v4.3.2 https://github.com/KhronosGroup/KTX-Software.git $sourcePath 2>&1 | Out-Null
    }
}

if (-not (Test-Path $sourcePath)) {
    Write-Host "[ERROR] ktx-software not found at $sourcePath"
    Write-Host "Clone: git clone --recursive --branch v4.3.2 https://github.com/KhronosGroup/KTX-Software.git $sourcePath"
    exit 1
}

# ── Find CMake ───────────────────────────────────────────────────
$cmake = Get-Command cmake -ErrorAction SilentlyContinue
if (-not $cmake) {
    $vsPaths = @(
        "C:\Program Files\Microsoft Visual Studio\18\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe",
        "C:\Program Files\Microsoft Visual Studio\17\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
    )
    foreach ($vs in $vsPaths) {
        if (Test-Path $vs) { $cmake = $vs; break }
    }
}

if (-not $cmake) {
    Write-Host "[ERROR] CMake not found. Install CMake 3.22+."
    exit 1
}

Write-Host "[CMAKE] Found: $($cmake.Source)"

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
    # Try PATH
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

# ── Configure ────────────────────────────────────────────────────
$buildDir = Join-Path $sourcePath "build_local"
New-Item -ItemType Directory -Path $buildDir -Force | Out-Null

$generator = if ($platform -eq "windows") {
    if (Get-Command ninja -ErrorAction SilentlyContinue) { "Ninja" }
    else { "Visual Studio 17 2022" }
} else {
    "Unix Makefiles"
}

Write-Host "[CONFIG] Generator: $generator"

# Set JAVA_HOME for JNI detection
$env:JAVA_HOME = $JavaHome

Push-Location $buildDir
& cmake .. `
    -G $generator `
    -DCMAKE_BUILD_TYPE=Release `
    -DKTX_FEATURE_JNI=ON `
    -DKTX_FEATURE_TESTS=OFF `
    -DKTX_FEATURE_TOOLS=OFF `
    -DKTX_FEATURE_DOC=OFF `
    -DBUILD_SHARED_LIBS=ON `
    -DCMAKE_INSTALL_PREFIX=install
Pop-Location

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] CMake configuration failed"
    exit 1
}

# ── Build ────────────────────────────────────────────────────────
Write-Host "[BUILD] Compiling KTX..."
Push-Location $buildDir
& cmake --build . --config Release
Pop-Location

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] KTX build failed"
    exit 1
}

# ── Install ──────────────────────────────────────────────────────
Write-Host "[INSTALL] Installing KTX..."
Push-Location $buildDir
& cmake --install . --config Release
Pop-Location

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] KTX install failed"
    exit 1
}

# ── Package artifacts ────────────────────────────────────────────
$dest = Join-Path $outputPath "$platform-$arch"
New-Item -ItemType Directory -Path $dest -Force | Out-Null

$installDir = Join-Path $buildDir "install"

# Determine file extension
$ext = switch ($platform) {
    "windows" { ".dll" }
    "linux"   { ".so" }
    "macos"   { ".dylib" }
}

# Copy shared libraries from bin/ and lib/
foreach ($subdir in @("bin", "lib")) {
    $srcSub = Join-Path $installDir $subdir
    if (Test-Path $srcSub) {
        Get-ChildItem -Path $srcSub -Recurse -File -Filter "*$ext" | ForEach-Object {
            Copy-Item $_.FullName (Join-Path $dest $_.Name)
            Write-Host "[COPY] $($_.Name) -> $dest/"
        }
    }
}

# Copy JNI JAR if built
Get-ChildItem -Path $installDir -Recurse -File -Filter "*.jar" -ErrorAction SilentlyContinue | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $dest $_.Name)
    Write-Host "[COPY] $($_.Name) -> $dest/"
}

# Copy headers
$includeSrc = Join-Path $installDir "include"
$includeDst = Join-Path $dest "include"
if (Test-Path $includeSrc) {
    if (Test-Path $includeDst) { Remove-Item $includeDst -Recurse -Force }
    Copy-Item -Recurse $includeSrc $includeDst
    Write-Host "[COPY] include/ -> $dest/"
}

Write-Host "[OK] KTX built for $platform-$arch -> $dest"
