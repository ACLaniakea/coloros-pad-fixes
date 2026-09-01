#!/usr/bin/env python3
"""Build the pen-bridge LSPosed Hook APK from Java source (ACLaniakea 1.1.27).

The hook now compiles directly from pen-bridge/hook/source/sources so every
behavior change lives in source:
  javac (android.jar + Xposed stubs) -> d8 -> classes.dex
  aapt2 compile+link (source res + manifest) -> resources.arsc
  assemble (dex + native lib + xposed_init + scope.list) -> zipalign -> apksigner
"""

from __future__ import annotations

import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "source" / "sources"
COMPILE_STUBS = ROOT / "source" / "stubs"
RES = ROOT / "source" / "resources" / "res"
MANIFEST = ROOT / "source" / "resources" / "AndroidManifest.xml"
XPOSED_INIT = ROOT / "source" / "resources" / "assets" / "xposed_init"
SCOPE_LIST = ROOT / "source" / "resources" / "META-INF" / "xposed" / "scope.list"
PEN_SO = ROOT / "source" / "resources" / "lib" / "arm64-v8a" / "libpeninput.so"

SDK = Path(os.environ.get("ANDROID_SDK", "/tmp/android-sdk"))
BT = SDK / "build-tools" / "android-15"
AAPT2 = BT / "aapt2"
D8 = BT / "d8"
R8_JAR = Path(os.environ.get("ACL_R8_JAR", "/run/media/ACLaniakea/IXUNICS/pad/tools/dex/r8.jar"))
ZIPALIGN = BT / "zipalign"
APKSIGNER = BT / "apksigner"


def _android_jar(sdk: Path) -> Path:
    for cand in (sdk / "platforms" / "android-35" / "android.jar",
                 sdk / "platforms" / "android-35" / "android-35" / "android.jar"):
        if cand.is_file():
            return cand
    return sdk / "platforms" / "android-35" / "android.jar"


ANDROID_JAR = _android_jar(SDK)
STUBS = Path(os.environ.get("XPOSED_STUBS", "/tmp/acdb/stubs"))
KEYSTORE = Path(os.environ.get("ACL_KS", "/run/media/ACLaniakea/IXUNICS/pad/keys/aclaniakea.jks"))
KS_PASS = os.environ.get("ACL_KS_PASS", "changeit")
ALIAS = "aclaniakea"

OUT_DIR = ROOT.parents[1] / "releases"
OUT_APK = OUT_DIR / "PenBridge-Hook-v3.1.0.apk"


def run(cmd: list[str]) -> None:
    print("+", " ".join(str(c) for c in cmd))
    subprocess.run([str(c) for c in cmd], check=True)


def write_aligned_stored(dst: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name)
    info.compress_type = zipfile.ZIP_STORED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    header_size = 30 + len(info.filename.encode("utf-8"))
    padding = (-(dst.fp.tell() + header_size)) % 4
    if padding:
        extra_size = padding + 4
        info.extra = struct.pack("<HH", 0xFFFF, extra_size - 4) + b"\0" * (extra_size - 4)
    dst.writestr(info, data)


def main() -> None:
    if not all(p.exists() for p in (AAPT2, D8, ZIPALIGN, APKSIGNER, ANDROID_JAR, KEYSTORE)):
        raise SystemExit(f"missing toolchain; checked {AAPT2}, {ANDROID_JAR}, {KEYSTORE}")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="penhook-src-") as td:
        tmp = Path(td)
        run([AAPT2, "compile", "--dir", RES, "-o", tmp / "res.zip"])
        run([AAPT2, "link", "-o", tmp / "base.apk", "-I", ANDROID_JAR,
             "--auto-add-overlay", "--manifest", MANIFEST, "-R", tmp / "res.zip",
             "--java", tmp / "gen", "--min-sdk-version", "31",
             "--target-sdk-version", "35",
             "--version-code", "301000", "--version-name", "3.1.0"])
        (tmp / "classes").mkdir(parents=True, exist_ok=True)
        (tmp / "dex").mkdir(parents=True, exist_ok=True)
        run(["javac", "--release", "17",
             "-classpath", f"{ANDROID_JAR}:{STUBS}:{tmp / 'gen'}",
             "-d", tmp / "classes"] +
            [str(p) for p in sorted(SOURCES.rglob("*.java"))] +
            [str(p) for p in sorted(COMPILE_STUBS.rglob("*.java"))])
        d8_cmd = ([D8] if not R8_JAR.is_file()
                  else ["java", "-cp", R8_JAR, "com.android.tools.r8.D8"])
        # Xposed API and the UEventObserver stub are provided at runtime by
        # LSPosed / the framework. They must never be baked into classes.dex,
        # otherwise LSPosed refuses to load the module ("The Xposed API
        # classes are compiled into the module's APK").
        _provided_prefixes = ("android/os/UEventObserver", "de/robv/android/xposed/")
        _class_files = []
        for p in sorted((tmp / "classes").rglob("*.class")):
            rel = str(p.relative_to(tmp / "classes"))
            if any(rel.startswith(prefix) for prefix in _provided_prefixes):
                continue
            _class_files.append(str(p))
        run(d8_cmd + ["--lib", ANDROID_JAR, "--min-api", "31", "--output", tmp / "dex"] + _class_files)
        dex = tmp / "dex" / "classes.dex"
        unsigned = tmp / "unsigned.apk"
        with zipfile.ZipFile(tmp / "base.apk", "r") as src, \
             zipfile.ZipFile(unsigned, "w") as dst:
            for info in src.infolist():
                dst.writestr(info, src.read(info))
            write_aligned_stored(dst, "classes.dex", dex.read_bytes())
            write_aligned_stored(dst, "lib/arm64-v8a/libpeninput.so", PEN_SO.read_bytes())
            dst.writestr("assets/xposed_init", XPOSED_INIT.read_bytes(),
                         compress_type=zipfile.ZIP_DEFLATED)
            dst.writestr("META-INF/xposed/scope.list", SCOPE_LIST.read_bytes(),
                         compress_type=zipfile.ZIP_DEFLATED)
        aligned = tmp / "aligned.apk"
        run([ZIPALIGN, "-f", "-p", "4", unsigned, aligned])
        run([APKSIGNER, "sign", "--ks", KEYSTORE, "--ks-key-alias", ALIAS,
             "--ks-pass", f"pass:{KS_PASS}", "--key-pass", f"pass:{KS_PASS}",
             "--out", OUT_APK, aligned])
    print(OUT_APK)


if __name__ == "__main__":
    main()
