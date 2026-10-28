#!/usr/bin/env python3
"""
JMine Build Orchestrator

Thin wrapper that delegates build tasks to PowerShell scripts:
  - scripts/mimalloc/build.ps1   → mimalloc native library
  - scripts/ktx/build.ps1        → KTX library with JNI
  - scripts/graal/build-bridge.ps1 → Java KTX Bridge JAR
  - scripts/graal/build-jre.ps1  → GraalVM jlink JRE assembly
  - scripts/graal/package.ps1    → Archive + checksum

Usage:
    python jmine.py build              # Build all components
    python jmine.py build mimalloc     # Build only mimalloc
    python jmine.py build ktx          # Build only KTX
    python jmine.py build bridge       # Build only KTX bridge
    python jmine.py build jre          # Assemble JRE from built components
    python jmine.py clean              # Clean all build artifacts
    python jmine.py package            # Package JRE for distribution
    python jmine.py --help             # Show help
"""

import argparse
import platform
import shutil
import subprocess
import sys
from pathlib import Path

# ── Paths ──────────────────────────────────────────────────────────────
SCRIPT_DIR = Path(__file__).parent
PWSH = "pwsh"  # PowerShell 7.x


def detect_platform():
    system = platform.system().lower()
    if system == "windows":
        return "windows"
    elif system == "linux":
        return "linux"
    elif system == "darwin":
        return "macos"
    return system


def detect_arch():
    machine = platform.machine().lower()
    if machine in ("amd64", "x86_64"):
        return "x64"
    elif machine in ("arm64", "aarch64"):
        return "arm64"
    return machine


def run_pwsh(script: Path, args: list[str] | None = None) -> int:
    """Run a PowerShell script and return its exit code."""
    cmd = [PWSH, "-File", str(script)]
    if args:
        cmd.extend(args)
    print(f"[RUN] {' '.join(cmd)}")
    result = subprocess.run(cmd)
    return result.returncode


# ── Build commands ─────────────────────────────────────────────────────

def build_mimalloc() -> bool:
    ps1 = SCRIPT_DIR / "mimalloc" / "build.ps1"
    if not ps1.exists():
        print(f"[ERROR] {ps1} not found")
        return False
    rc = run_pwsh(ps1)
    return rc == 0


def build_ktx() -> bool:
    ps1 = SCRIPT_DIR / "ktx" / "build.ps1"
    if not ps1.exists():
        print(f"[ERROR] {ps1} not found")
        return False
    rc = run_pwsh(ps1)
    return rc == 0


def build_bridge() -> bool:
    ps1 = SCRIPT_DIR / "graal" / "build-bridge.ps1"
    if not ps1.exists():
        print(f"[ERROR] {ps1} not found")
        return False
    rc = run_pwsh(ps1)
    return rc == 0


def build_jre() -> bool:
    ps1 = SCRIPT_DIR / "graal" / "build-jre.ps1"
    if not ps1.exists():
        print(f"[ERROR] {ps1} not found")
        return False
    rc = run_pwsh(ps1)
    return rc == 0


def package_jre() -> bool:
    ps1 = SCRIPT_DIR / "graal" / "package.ps1"
    if not ps1.exists():
        print(f"[ERROR] {ps1} not found")
        return False
    rc = run_pwsh(ps1)
    return rc == 0


# ── Clean ──────────────────────────────────────────────────────────────

def clean() -> None:
    print("=" * 60)
    print("Cleaning build artifacts")
    print("=" * 60)

    dirs_to_clean = [
        SCRIPT_DIR.parent / "output",
        SCRIPT_DIR.parent / "mimalloc" / "build_local",
        SCRIPT_DIR.parent / "ktx-software" / "build_local",
        SCRIPT_DIR.parent / "jmine-ktx-bridge" / "build",
    ]

    for d in dirs_to_clean:
        if d.exists():
            shutil.rmtree(d)
            print(f"[CLEAN] {d}")

    print("[OK] Clean complete")


# ── Main ───────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="JMine Build Orchestrator — delegates to PowerShell scripts",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
PowerShell scripts:
  scripts/mimalloc/build.ps1       Build mimalloc native library
  scripts/ktx/build.ps1            Build KTX library with JNI
  scripts/graal/build-bridge.ps1   Compile Java KTX Bridge JAR
  scripts/graal/build-jre.ps1      Assemble JMine JRE with jlink
  scripts/graal/package.ps1        Package JRE archive + checksum

Examples:
  python jmine.py build              Build all components
  python jmine.py build mimalloc     Build only mimalloc
  python jmine.py build ktx          Build only KTX
  python jmine.py build bridge       Build only KTX bridge
  python jmine.py build jre          Assemble JRE from built components
  python jmine.py clean              Clean all build artifacts
  python jmine.py package            Package JRE for distribution
        """,
    )

    parser.add_argument(
        "command",
        choices=["build", "clean", "package"],
        help="Build command: build, clean, or package",
    )
    parser.add_argument(
        "component",
        nargs="?",
        choices=["all", "mimalloc", "ktx", "bridge", "jre"],
        default="all",
        help="Component to build (default: all)",
    )

    args = parser.parse_args()

    if args.command == "clean":
        clean()
        return

    if args.command == "package":
        success = package_jre()
        sys.exit(0 if success else 1)

    # ── Build ──────────────────────────────────────────────────────
    plat = detect_platform()
    arch = detect_arch()

    print(f"\nPlatform: {plat}-{arch}")
    print(f"Delegating to PowerShell 7.x scripts...\n")

    components = []
    if args.component in ("all", "mimalloc"):
        components.append(("mimalloc", build_mimalloc))
    if args.component in ("all", "ktx"):
        components.append(("ktx", build_ktx))
    if args.component in ("all", "bridge"):
        components.append(("bridge", build_bridge))
    if args.component == "jre":
        success = build_jre()
        sys.exit(0 if success else 1)

    results = {}
    for name, func in components:
        try:
            results[name] = func()
        except Exception as e:
            print(f"[ERROR] {name} build failed: {e}")
            results[name] = False

    # ── Summary ────────────────────────────────────────────────────
    print("\n" + "=" * 60)
    print("Build Summary")
    print("=" * 60)
    for name, success in results.items():
        status = "OK" if success else "FAILED"
        print(f"  {name}: {status}")

    all_success = all(results.values())
    if all_success:
        print("\n[OK] All components built successfully!")
    else:
        print("\n[WARN] Some components failed. Check output above.")

    sys.exit(0 if all_success else 1)


if __name__ == "__main__":
    main()
