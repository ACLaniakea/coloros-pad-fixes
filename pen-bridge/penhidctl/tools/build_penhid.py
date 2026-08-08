#!/usr/bin/env python3
"""Build PenHidCtl priv-app APK (com.aclaniakea.penhidctl, 1.0.1) from source."""

from __future__ import annotations

import os
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "src"
RES = ROOT / "res"
MANIFEST = ROOT / "AndroidManifest.xml"

SDK = Path(os.environ.get("ANDROID_SDK", "/tmp/android-sdk"))
BT = SDK / "build-tools" / "android-15"
AAPT2 = BT / "aapt2"
D8 = BT / "d8"
ZIPALIGN = BT / "zipalign"
APKSIGNER = BT / "apksigner"
def _android_jar(sdk: Path) -> Path:
    for cand in (sdk / "platforms" / "android-35" / "android.jar",
                 sdk / "platforms" / "android-35" / "android-35" / "android.jar"):
        if cand.is_file():
            return cand
    return sdk / "platforms" / "android-35" / "android.jar"


ANDROID_JAR = _android_jar(SDK)
KEYSTORE = Path(os.environ.get("ACL_KS", "/tmp/aclaniakea.jks"))
KS_PASS = os.environ.get("ACL_KS_PASS", "changeit")
ALIAS = "aclaniakea"

OUT_DIR = ROOT.parents[1] / "releases"
OUT_APK = OUT_DIR / "PenHidCtl-v1.0.1.apk"


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
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="penhid-") as td:
        tmp = Path(td)
        run([AAPT2, "compile", "--dir", RES, "-o", tmp / "res.zip"])
        run([AAPT2, "link", "-o", tmp / "base.apk", "-I", ANDROID_JAR,
             "--auto-add-overlay", "--manifest", MANIFEST, "-R", tmp / "res.zip",
             "--java", tmp / "gen", "--min-sdk-version", "31",
             "--target-sdk-version", "35",
             "--version-code", "1001", "--version-name", "1.0.1"])
        (tmp / "classes").mkdir(parents=True, exist_ok=True)
        (tmp / "dex").mkdir(parents=True, exist_ok=True)
        run(["javac", "-source", "8", "-target", "8",
             "-classpath", f"{ANDROID_JAR}:{tmp / 'gen'}",
             "-d", tmp / "classes"] +
            [str(p) for p in sorted(SOURCES.rglob("*.java"))])
        run([D8, "--lib", ANDROID_JAR, "--min-api", "31", "--output", tmp / "dex"] +
            [str(p) for p in sorted((tmp / "classes").rglob("*.class"))])
        unsigned = tmp / "unsigned.apk"
        with zipfile.ZipFile(tmp / "base.apk", "r") as src, \
             zipfile.ZipFile(unsigned, "w") as dst:
            for info in src.infolist():
                dst.writestr(info, src.read(info))
            write_aligned_stored(dst, "classes.dex", (tmp / "dex" / "classes.dex").read_bytes())
        aligned = tmp / "aligned.apk"
        run([ZIPALIGN, "-f", "-p", "4", unsigned, aligned])
        run([APKSIGNER, "sign", "--ks", KEYSTORE, "--ks-key-alias", ALIAS,
             "--ks-pass", f"pass:{KS_PASS}", "--key-pass", f"pass:{KS_PASS}",
             "--out", OUT_APK, aligned])
    print(OUT_APK)


if __name__ == "__main__":
    main()
