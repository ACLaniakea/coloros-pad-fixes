#!/usr/bin/env python3
"""Build the standalone LSPosed hook for the Lenovo ZUI camera port."""
from pathlib import Path
import os, shutil, subprocess, tempfile, zipfile

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
SDK = Path(os.environ.get("ANDROID_SDK", REPO / "workspace/min-sdk"))
BT = SDK / "build-tools/android-14"
ANDROID_JAR = SDK / "platforms/android-35/android.jar"
STUBS = Path(os.environ.get("XPOSED_STUBS", "/tmp/acdb/stubs"))
STUB_PATCHES = REPO / "base-fix/hook/stub-patches"
SOURCE = REPO / "experimental/lenovo-zui-camera-port/ZuiCameraCompatBridge.java"
R8 = Path(os.environ.get("ACL_R8_JAR", "/run/media/ACLaniakea/IXUNICS/pad/tools/dex/r8.jar"))
KEYSTORE = Path(os.environ.get("ACL_KS", "/run/media/ACLaniakea/IXUNICS/pad/keys/aclaniakea.jks"))
OUT = ROOT / "releases/ZUI-Camera-Compat-v3.2.0.apk"

def run(*args):
    subprocess.run([str(x) for x in args], check=True)

with tempfile.TemporaryDirectory(prefix="zui-hook-") as tmp_name:
    tmp = Path(tmp_name)
    base, stubs, classes, dex = (tmp / "base.apk", tmp / "stubs", tmp / "classes", tmp / "dex")
    res = tmp / "res.zip"
    run(BT / "aapt2", "compile", "--dir", ROOT / "resources/res", "-o", res)
    run(BT / "aapt2", "link", "-o", base, "-I", ANDROID_JAR,
        "--manifest", ROOT / "resources/AndroidManifest.xml", "-R", res,
        "--auto-add-overlay", "--min-sdk-version", "31")
    stubs.mkdir(); classes.mkdir(); dex.mkdir()
    run("javac", "--release", "17", "-classpath", STUBS, "-d", stubs,
        *sorted(STUB_PATCHES.rglob("*.java")))
    run("javac", "--release", "17", "-classpath", f"{ANDROID_JAR}:{stubs}:{STUBS}",
        "-d", classes, SOURCE)
    bridge_classes = [p for p in classes.rglob("*.class")
                      if not str(p.relative_to(classes)).startswith("de/robv/android/xposed/")]
    d8 = ("java", "-cp", R8, "com.android.tools.r8.D8") if R8.is_file() else (BT / "d8",)
    run(*d8, "--lib", ANDROID_JAR, "--min-api", "31", "--output", dex, *bridge_classes)
    unsigned = tmp / "unsigned.apk"
    with zipfile.ZipFile(base) as src, zipfile.ZipFile(unsigned, "w") as dst:
        for item in src.infolist(): dst.writestr(item, src.read(item))
        dst.write(dex / "classes.dex", "classes.dex", compress_type=zipfile.ZIP_STORED)
        dst.write(ROOT / "resources/assets/xposed_init", "assets/xposed_init")
        dst.write(ROOT / "resources/META-INF/xposed/scope.list", "META-INF/xposed/scope.list")
        dst.write(ROOT / "resources/META-INF/xposed/scope.list", "assets/scope.list")
    aligned = tmp / "aligned.apk"
    run(BT / "zipalign", "-f", "-p", "4", unsigned, aligned)
    OUT.parent.mkdir(exist_ok=True)
    run(BT / "apksigner", "sign", "--ks", KEYSTORE, "--ks-key-alias", "aclaniakea",
        "--ks-pass", "pass:changeit", "--key-pass", "pass:changeit", "--out", OUT, aligned)
print(OUT)
