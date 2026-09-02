#!/usr/bin/env python3
"""Assemble the complete, standalone TB710FU ZUI Camera KernelSU module."""
from pathlib import Path
import shutil, subprocess, zipfile

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
STAGE = ROOT / "out/stage"
OUT = ROOT / "releases/LenovoPadProGT-ZUI-Camera-Port-v3.2.0.zip"
SOURCE = REPO / "experimental/lenovo-zui-camera-port"

if STAGE.exists(): shutil.rmtree(STAGE)
shutil.copytree(ROOT, STAGE, ignore=shutil.ignore_patterns("out", "releases", "__pycache__"))

def copy(src, dst):
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)

camera = SOURCE / "lenovo-zui-camera-module"
for name in ("ZuiCamera", "ZuiCameraAssistant", "ZuiCameraQR"):
    shutil.copytree(camera / "system/priv-app" / name, STAGE / "system/priv-app" / name,
                    dirs_exist_ok=True)
for xml in (camera / "system/etc/permissions").glob("*.xml"):
    copy(xml, STAGE / "system/etc/permissions" / xml.name)
shutil.copytree(SOURCE / "lcaf-config/system/etc/camera", STAGE / "system/etc/camera",
                dirs_exist_ok=True)
copy(REPO / "zui-camera-compat-hook/releases/ZUI-Camera-Compat-v3.2.0.apk",
     STAGE / "hook/ZUI-Camera-Compat.apk")
copy(REPO / "experimental/lenovo-zui-camera-port/zui-native-identity/out/stage/zygisk/arm64-v8a.so",
     STAGE / "zygisk/arm64-v8a.so")
copy(REPO / "fix-module/module/bin/lsposed-path-sync.jar", STAGE / "bin/lsposed-path-sync.jar")
for script in ("post-fs-data.sh",):
    (STAGE / script).chmod(0o755)

OUT.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as archive:
    for item in sorted(STAGE.rglob("*")):
        if item.is_file(): archive.write(item, item.relative_to(STAGE))
print(OUT)
