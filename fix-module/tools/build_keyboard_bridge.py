#!/usr/bin/env python3
"""Build the tiny aarch64 Lenovo private-key uinput bridge."""

from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "native" / "lenovo_keyboard_bridge.c"
OUTPUT = ROOT / "module" / "bin" / "lenovo-keyboard-bridge"
DEFAULT_NDK = ROOT.parents[0] / "workspace" / "toolchains" / "android-ndk-r26d"
NDK = Path(os.environ.get("ANDROID_NDK", DEFAULT_NDK))
PREBUILT = NDK / "toolchains/llvm/prebuilt/linux-x86_64"
CLANG = PREBUILT / "bin/clang-17"
SYSROOT = PREBUILT / "sysroot"
STRIP = PREBUILT / "bin/llvm-strip"

if not CLANG.is_file():
    raise SystemExit(f"missing Android NDK compiler: {CLANG}")
OUTPUT.parent.mkdir(parents=True, exist_ok=True)
subprocess.run([str(CLANG), "--target=aarch64-linux-android31",
                f"--sysroot={SYSROOT}", "-O2", "-fPIE", "-pie", "-Wall", "-Wextra",
                "-Werror", "-o", str(OUTPUT), str(SOURCE)], check=True)
strip = str(STRIP if STRIP.is_file() else "llvm-strip")
subprocess.run([strip, "--strip-unneeded", str(OUTPUT)], check=True)
OUTPUT.chmod(0o755)
print(OUTPUT)
