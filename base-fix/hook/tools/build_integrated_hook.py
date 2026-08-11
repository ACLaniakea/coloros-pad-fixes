#!/usr/bin/env python3
"""Build the base-fix LSPosed Hook APK from source (ACLaniakea 1.0.0).

Produces a signed APK for com.aclaniakea.colorosostatsguard:
  javac (android.jar + Xposed stubs) -> d8 -> classes.dex
  aapt2 compile+link (source res + manifest) -> resources.arsc
  assemble -> zipalign -> apksigner
"""

from __future__ import annotations

import os
import shutil
import subprocess
import struct
import sys
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "sources"
RES = ROOT / "resources" / "res"
MANIFEST = ROOT / "resources" / "AndroidManifest.xml"
XPOSED_INIT = ROOT / "resources" / "assets" / "xposed_init"
SCOPE_LIST = ROOT / "resources" / "META-INF" / "xposed" / "scope.list"

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
KEYSTORE = Path(os.environ.get("ACL_KS", "/tmp/aclaniakea.jks"))
KS_PASS = os.environ.get("ACL_KS_PASS", "changeit")
ALIAS = "aclaniakea"

OUT_DIR = ROOT.parents[1] / "releases"
OUT_APK = OUT_DIR / "BaseFix-Hook-v1.0.1.apk"


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
    with tempfile.TemporaryDirectory(prefix="basefix-") as td:
        tmp = Path(td)
        # 1. resources
        run([AAPT2, "compile", "--dir", RES, "-o", tmp / "res.zip"])
        run([AAPT2, "link", "-o", tmp / "base.apk", "-I", ANDROID_JAR,
             "--auto-add-overlay", "--manifest", MANIFEST, "-R", tmp / "res.zip",
             "--java", tmp / "gen", "--min-sdk-version", "31",
             "--target-sdk-version", "35",
             "--version-code", "1001", "--version-name", "1.0.1"])
        # 2. compile java
        (tmp / "classes").mkdir(parents=True, exist_ok=True)
        (tmp / "dex").mkdir(parents=True, exist_ok=True)
        run(["javac", "--release", "17",
             "-classpath", f"{ANDROID_JAR}:{STUBS}:{tmp / 'gen'}",
             "-d", tmp / "classes"] +
            [str(p) for p in sorted(SOURCES.rglob("*.java"))])
        # 3. dex
        d8_cmd = ([D8] if not R8_JAR.is_file()
                  else ["java", "-cp", R8_JAR, "com.android.tools.r8.D8"])
        run(d8_cmd + ["--lib", ANDROID_JAR, "--min-api", "31", "--output", tmp / "dex"] +
            [str(p) for p in sorted((tmp / "classes").rglob("*.class"))])
        dex = tmp / "dex" / "classes.dex"
        # 4. assemble
        unsigned = tmp / "unsigned.apk"
        with zipfile.ZipFile(tmp / "base.apk", "r") as src, \
             zipfile.ZipFile(unsigned, "w") as dst:
            for info in src.infolist():
                dst.writestr(info, src.read(info))
            write_aligned_stored(dst, "classes.dex", dex.read_bytes())
            dst.writestr("assets/xposed_init", XPOSED_INIT.read_bytes(),
                         compress_type=zipfile.ZIP_DEFLATED)
            dst.writestr("META-INF/xposed/scope.list", SCOPE_LIST.read_bytes(),
                         compress_type=zipfile.ZIP_DEFLATED)
        # 5. align + sign
        aligned = tmp / "aligned.apk"
        run([ZIPALIGN, "-f", "-p", "4", unsigned, aligned])
        run([APKSIGNER, "sign", "--ks", KEYSTORE, "--ks-key-alias", ALIAS,
             "--ks-pass", f"pass:{KS_PASS}", "--key-pass", f"pass:{KS_PASS}",
             "--out", OUT_APK, aligned])
    print(OUT_APK)


if __name__ == "__main__":
    main()
