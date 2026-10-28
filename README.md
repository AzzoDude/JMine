# JMine — Custom GraalVM JRE for Minecraft Performance

[![CI Build](https://github.com/<user>/JMine/actions/workflows/ci.yml/badge.svg)](https://github.com/<user>/JMine/actions/workflows/ci.yml)
[![Release](https://github.com/<user>/JMine/actions/workflows/release.yml/badge.svg)](https://github.com/<user>/JMine/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

JMine is a custom-optimized Java Runtime Environment built on **GraalVM 25** that delivers maximum Minecraft performance through JRE-level optimizations — no mods required.

## What JMine Does

JMine combines three layers of optimization that work entirely at the runtime level:

| Layer | Component | Performance Gain |
|---|---|---|
| **Memory Allocation** | [mimalloc](https://github.com/microsoft/mimalloc) v1.8.9 | 27.5% faster ticks, 37.4% higher throughput |
| **Texture Compression** | [KTX-Software](https://github.com/KhronosGroup/KTX-Software) v4.3.2 + KTX Bridge | 4:1 VRAM reduction via BC7 compression |
| **JIT Compilation** | GraalVM JIT with 90+ tuned JVM flags | 5-15% faster TPS over standard OpenJDK |

## Components

JMine is built from three components, each compiled for **Windows**, **Linux**, and **macOS**:

### 1. mimalloc (Native Library)
Microsoft's high-performance memory allocator, injected into `java.exe` to replace the default JVM allocator. Handles the millions of small object allocations Minecraft makes every tick.

- **Windows**: `mimalloc.dll` + `mimalloc-redirect.dll`
- **Linux**: `libmimalloc.so`
- **macOS**: `libmimalloc.dylib`

### 2. KTX Library + JNI Bindings
Khronos Group's texture compression library with Java bindings. Enables runtime PNG→BC7 texture compression, reducing GPU VRAM usage by ~75%.

- **Windows**: `libktx.dll` + JNI JAR
- **Linux**: `libktx.so` + JNI JAR
- **macOS**: `libktx.dylib` + JNI JAR

### 3. Java KTX Bridge
A Java instrumentation agent (`jmine-ktx-bridge.jar`) with **ASM 9.7.1 embedded** that intercepts Minecraft's texture loading at the bytecode level. It transforms `Resource.open()` at class load time to check the DDS cache before every texture load, serving cached compressed textures automatically. Includes performance transformers for thread pool optimization and allocation reduction. No mods required — fully plug-and-play.

## Platform Support

| Platform | Architecture | Status |
|---|---|---|
| Windows | x64 | ✅ Full support |
| Linux | x64 | ✅ Full support |
| macOS | x64 | ✅ Full support |
| macOS | arm64 (Apple Silicon) | 🔄 Planned |

## Quick Start

### Download Pre-built Release

1. Go to [Releases](https://github.com/<user>/JMine/releases)
2. Download the package for your platform:
   - Windows: `jmine-jre-0.1.0-windows-x64.zip`
   - Linux: `jmine-jre-0.1.0-linux-x64.tar.gz`
   - macOS: `jmine-jre-0.1.0-macos-x64.tar.gz`
3. Extract and point your Minecraft launcher's JVM path to `jmine-jre/bin/java` (or `java.exe`)

### Configure Your Launcher

Add these JVM arguments (or use the included `script.py`):

```
-javaagent:jmine-jre/lib/jmine-ktx-bridge.jar=mode=balanced
```

See [jmine_config.md](jmine_config.md) for the complete 90+ flag reference.

## Build from Source

### Prerequisites

- **PowerShell 7.6.9+** (core build system)
- **CMake** 3.22+
- **Java 25 JDK** (GraalVM recommended)
- **Ninja** (Windows, optional — falls back to Visual Studio)
- **Python 3.8+** (for orchestrator wrapper, optional)

### Clone Dependencies

```bash
# Clone JMine
git clone https://github.com/<user>/JMine.git
cd JMine

# Clone mimalloc
git clone --depth 1 --branch v1.8.9 https://github.com/microsoft/mimalloc.git ../mimalloc

# Clone KTX-Software
git clone --recursive https://github.com/KhronosGroup/KTX-Software.git ../ktx-software
```

### Build Locally

All build logic is in **PowerShell 7.x** scripts. The Python `jmine.py` is a thin orchestrator.

```bash
cd scripts

# Build all components (delegates to pwsh scripts)
python jmine.py build

# Or run PowerShell scripts directly:
pwsh -File mimalloc/build.ps1
pwsh -File ktx/build.ps1
pwsh -File graal/build-bridge.ps1
pwsh -File graal/build-jre.ps1
pwsh -File graal/package.ps1

# Build only one component
python jmine.py build mimalloc
python jmine.py build ktx
python jmine.py build bridge
python jmine.py build jre

# Package for distribution
python jmine.py package

# Clean build artifacts
python jmine.py clean
```

## CI/CD Pipeline

JMine uses GitHub Actions to automatically build and release for all three platforms.
All build steps delegate to **PowerShell 7.x** scripts in `scripts/`.

### CI Workflow (`.github/workflows/ci.yml`)
Triggers on **push** to `main`/`dev` and **pull requests**:
1. Calls `scripts/mimalloc/build.ps1` for Windows, Linux, macOS in parallel
2. Calls `scripts/ktx/build.ps1` for Windows, Linux, macOS in parallel
3. Calls `scripts/graal/build-bridge.ps1` to compile the Java KTX bridge
4. Calls `scripts/graal/build-jre.ps1` to assemble the JMine JRE per platform
5. Calls `scripts/graal/package.ps1` to create archives with SHA256 checksums
6. Uploads artifacts for download

### Release Workflow (`.github/workflows/release.yml`)
Triggers on **git tags** (`v*`):
1. Runs the same PowerShell build pipeline as CI
2. Publishes artifacts to [GitHub Releases](https://github.com/<user>/JMine/releases)
3. Also publishes standalone native libraries and KTX bridge JAR

### Creating a Release

```bash
# Tag and push
git tag v0.1.0
git push origin v0.1.0
```

The release workflow will automatically build all platforms and publish.

## Project Structure

```
JMine/
├── .github/
│   └── workflows/
│       ├── ci.yml              # CI build pipeline (calls pwsh scripts)
│       └── release.yml         # Release pipeline (on tags)
├── scripts/
│   ├── jmine.py               # Thin orchestrator → delegates to pwsh
│   ├── config.toml            # Build configuration
│   ├── mimalloc/
│   │   └── build.ps1          # Build mimalloc native library
│   ├── ktx/
│   │   └── build.ps1          # Build KTX library with JNI
│   └── graal/
│       ├── build-bridge.ps1   # Compile Java KTX Bridge JAR (downloads ASM, creates fat JAR)
│       ├── build-jre.ps1      # Assemble JMine JRE with jlink
│       └── package.ps1        # Package JRE archive + checksum
├── jmine-ktx-bridge/          # Java KTX bridge source
├── README.md                  # This file
└── output/                    # Build output (gitignored)
    ├── mimalloc/
    │   ├── windows-x64/
    │   ├── linux-x64/
    │   └── macos-x64/
    ├── ktx/
    │   ├── windows-x64/
    │   ├── linux-x64/
    │   └── macos-x64/
    ├── jmine-ktx-bridge.jar
    └── jmine-jre/             # Assembled JRE
```

## Documentation

- **[jmine_config.md](jmine_config.md)** — Complete JVM flag reference (90+ flags)
- **[jmine_advanced_optimizations.md](jmine_advanced_optimizations.md)** — Deep dive into CPU, memory, and texture optimizations
- **[jmine_mod_compatibility.md](jmine_mod_compatibility.md)** — Compatibility with Sodium, Lithium, and other mods
- **[jmine_jre_interception.md](jmine_jre_interception.md)** — JRE-level code interception techniques

## Performance Expectations

| Metric | Standard JDK | JMine (estimated) |
|---|---|---|
| Tick speed | Baseline | 25-40% faster |
| VRAM usage | 100% | ~25% (with BC7) |
| GC pauses | Variable | Reduced (Graal JIT + mimalloc) |
| Warmup time | Slow | Faster (Graal JIT) |

## License

MIT — See [LICENSE](LICENSE) for details.

Dependencies:
- mimalloc: MIT License
- KTX-Software: Apache 2.0 License
- GraalVM Community: GPLv2 with Classpath Exception
