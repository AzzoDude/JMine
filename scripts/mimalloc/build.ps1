# build.ps1 — Build mimalloc native library
#
# Usage:
#   pwsh -File build.ps1 [-Source <path>] [-Output <path>] [-Config Release|Debug]
#
# Clones (if needed), configures CMake, builds, and copies artifacts to the output directory.
# Target: mimalloc v1.8.9

param(
    [string]$Source = "../../mimalloc",
    [string]$Output = "../../output/mimalloc",
    [string]$Config = "Release",
    [switch]$Clone
)

$ErrorActionPreference = "Stop"

# ── Detect platform ──────────────────────────────────────────────
$platform = switch ($env:OS) {
    "Windows_NT" { "windows" }
    default {
        if (Test-Path "/etc/os-release") {
            if ((Get-Content "/etc/os-release" | Select-String -Pattern "ID=ubuntu|ID=debian|ID=fedora")) { "linux" }
            elseif (Get-Content "/etc/os-release" | Select-String -Pattern "ID=macos|Darwin") { "macos" }
            else { "linux" }
        }
        else { "windows" }
    }
}

# Override on macOS where $env:OS is Windows_NT in Git Bash
if ($IsMacOS) { $platform = "macos" }
if ($IsLinux) { $platform = "linux" }
if ($IsWindows) { $platform = "windows" }

$arch = if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64" -or $env:PROCESSOR_ARCHITEW6432 -eq "ARM64") { "arm64" } else { "x64" }

Write-Host "=" * 60
Write-Host "Building mimalloc for $platform-$arch"
Write-Host "=" * 60

# ── Resolve paths ────────────────────────────────────────────────
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourcePath = if ($Source.StartsWith('.')) { Resolve-Path (Join-Path $scriptDir $Source) } else { $Source }
$outputPath = if ($Output.StartsWith('.')) { Resolve-Path (Join-Path $scriptDir $Output) } else { $Output }

# ── Clone if needed ──────────────────────────────────────────────
if ($Clone -or -not (Test-Path $sourcePath)) {
    if (-not (Test-Path $sourcePath)) {
        Write-Host "[INFO] mimalloc source not found at $sourcePath"
        Write-Host "[CLONE] Cloning mimalloc v1.8.9..."
        $parent = Split-Path $sourcePath
        git -C $parent clone --depth 1 --branch v1.8.9 https://github.com/microsoft/mimalloc.git $sourcePath 2>&1 | Out-Null
    }
}

if (-not (Test-Path $sourcePath)) {
    Write-Host "[ERROR] mimalloc source not found at $sourcePath"
    Write-Host "Clone manually: git clone --depth 1 --branch v1.8.9 https://github.com/microsoft/mimalloc.git $sourcePath"
    exit 1
}

# ── Find CMake ───────────────────────────────────────────────────
$cmake = Get-Command cmake -ErrorAction SilentlyContinue
if (-not $cmake) {
    # Try VS-bundled CMake on Windows
    $vsPaths = @(
        "C:\Program Files\Microsoft Visual Studio\18\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe",
        "C:\Program Files\Microsoft Visual Studio\17\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
    )
    foreach ($vs in $vsPaths) {
        if (Test-Path $vs) { $cmake = $vs; break }
    }
}

if (-not $cmake) {
    Write-Host "[ERROR] CMake not found. Install CMake 3.18+."
    exit 1
}

Write-Host "[CMAKE] Found: $($cmake.Source)"

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
Push-Location $buildDir
& cmake .. `
    -G $generator `
    "-DCMAKE_BUILD_TYPE=$Config" `
    -DMI_OVERRIDE=ON `
    -DMI_BUILD_SHARED=ON `
    -DMI_BUILD_STATIC=ON `
    -DMI_BUILD_TESTS=OFF `
    -DMI_SHOW_ERRORS=ON `
    -DMI_STATS=ON
Pop-Location

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] CMake configuration failed"
    exit 1
}

# ── Build ────────────────────────────────────────────────────────
Write-Host "[BUILD] Compiling mimalloc..."
Push-Location $buildDir
& cmake --build . --config $Config
Pop-Location

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] mimalloc build failed"
    exit 1
}

# ── Package artifacts ────────────────────────────────────────────
$dest = Join-Path $outputPath "$platform-$arch"
New-Item -ItemType Directory -Path $dest -Force | Out-Null

# Copy shared libraries
$ext = switch ($platform) {
    "windows" { @(".dll", ".lib") }
    "linux"   { @(".so", ".a") }
    "macos"   { @(".dylib", ".a") }
}

Get-ChildItem -Path $buildDir -File -Include $ext -Recurse | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $dest $_.Name)
    Write-Host "[COPY] $($_.Name) -> $dest/"
}

# Copy headers
$includeSrc = Join-Path $sourcePath "include"
$includeDst = Join-Path $dest "include"
if (Test-Path $includeSrc) {
    if (Test-Path $includeDst) { Remove-Item $includeDst -Recurse -Force }
    Copy-Item -Recurse $includeSrc $includeDst
    Write-Host "[COPY] include/ -> $dest/"
}

Write-Host "[OK] mimalloc built for $platform-$arch -> $dest"
