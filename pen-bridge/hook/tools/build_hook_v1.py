#!/usr/bin/env python3
"""Build the pen-bridge LSPosed Hook APK (ACLaniakea 1.0.1).

The current behavior is the verified r50 dex (all accumulated smali patches).
This script takes that APK/dex, renames the package com.codex -> com.aclaniakea
at smali level, reassembles, rebuilds resources under the new package name and
signs the result.  Behavior is preserved byte-for-byte apart from the rename.

Usage: python3 build_hook_v1.py INPUT_APK [OUTPUT_APK]
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
RES = ROOT / "source" / "resources" / "res"
MANIFEST = ROOT / "source" / "resources" / "AndroidManifest.xml"
XPOSED_INIT = ROOT / "source" / "resources" / "assets" / "xposed_init"
SCOPE_LIST = ROOT / "source" / "resources" / "META-INF" / "xposed" / "scope.list"
PEN_SO = ROOT / "source" / "resources" / "lib" / "arm64-v8a" / "libpeninput.so"

SDK = Path(os.environ.get("ANDROID_SDK", "/tmp/android-sdk"))
BT = SDK / "build-tools" / "android-15"
AAPT2 = BT / "aapt2"
ZIPALIGN = BT / "zipalign"
APKSIGNER = BT / "apksigner"
def _android_jar(sdk: Path) -> Path:
    for cand in (sdk / "platforms" / "android-35" / "android.jar",
                 sdk / "platforms" / "android-35" / "android-35" / "android.jar"):
        if cand.is_file():
            return cand
    return sdk / "platforms" / "android-35" / "android.jar"


ANDROID_JAR = _android_jar(SDK)
DEX_TOOLS = Path(os.environ.get("PEN_SMALI_TOOLS_DIR", "/tmp/codex-dex-tools"))
KEYSTORE = Path(os.environ.get("ACL_KS", "/tmp/aclaniakea.jks"))
KS_PASS = os.environ.get("ACL_KS_PASS", "changeit")
ALIAS = "aclaniakea"

OUT_DIR = ROOT.parents[1] / "releases"
OUT_APK = OUT_DIR / "PenBridge-Hook-v1.0.1.apk"


def run(cmd: list[str]) -> None:
    print("+", " ".join(str(c) for c in cmd))
    subprocess.run([str(c) for c in cmd], check=True)


def extract_dex(apk: Path, dst: Path) -> None:
    with zipfile.ZipFile(apk) as z:
        try:
            data = z.read("classes.dex")
        except KeyError:
            raise SystemExit(f"{apk}: no classes.dex")
        dst.write_bytes(data)


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
    global OUT_APK
    if len(sys.argv) < 2:
        raise SystemExit("usage: build_hook_v1.py INPUT_APK [OUTPUT_APK]")
    in_apk = Path(sys.argv[1])
    if len(sys.argv) >= 3:
        OUT_APK = Path(sys.argv[2])
    OUT_APK.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="penhook-") as td:
        tmp = Path(td)
        dex = tmp / "classes.dex"
        extract_dex(in_apk, dex)
        smali_out = tmp / "smali"
        run(["java", "-cp", f"{DEX_TOOLS}/*", "org.jf.baksmali.Main", "d", dex, "-o", smali_out])
        # rename package
        old_pkg = smali_out / "com" / "codex"
        new_pkg = smali_out / "com" / "aclaniakea"
        if not old_pkg.is_dir():
            raise SystemExit("input dex does not contain com/codex package")
        shutil.move(str(old_pkg), str(new_pkg))
        for f in smali_out.rglob("*.smali"):
            text = f.read_text(encoding="utf-8")
            text = text.replace("Lcom/codex/", "Lcom/aclaniakea/")
            text = text.replace("com.codex.", "com.aclaniakea.")
            f.write_text(text, encoding="utf-8")
        # reassemble
        run(["java", "-cp", f"{DEX_TOOLS}/*", "org.jf.smali.Main", "a", smali_out, "-o", tmp / "new.dex"])
        # resources
        run([AAPT2, "compile", "--dir", RES, "-o", tmp / "res.zip"])
        run([AAPT2, "link", "-o", tmp / "base.apk", "-I", ANDROID_JAR,
             "--auto-add-overlay", "--manifest", MANIFEST, "-R", tmp / "res.zip",
             "--java", tmp / "gen", "--min-sdk-version", "31",
             "--target-sdk-version", "35",
             "--version-code", "1001", "--version-name", "1.0.1"])
        # assemble
        unsigned = tmp / "unsigned.apk"
        with zipfile.ZipFile(tmp / "base.apk", "r") as src, \
             zipfile.ZipFile(unsigned, "w") as dst:
            for info in src.infolist():
                dst.writestr(info, src.read(info))
            write_aligned_stored(dst, "classes.dex", (tmp / "new.dex").read_bytes())
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
