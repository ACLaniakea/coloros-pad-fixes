#!/usr/bin/env bash
# ACLaniakea ColorOS Pad Port Fixes - 1.0.0 全量构建
# 输出统一到 releases/。需要本机工具链：
#   /tmp/android-sdk (aapt2/d8/zipalign/apksigner/android-35)
#   /tmp/acdb/stubs (Xposed stubs)
#   /tmp/codex-dex-tools (smali/baksmali)
#   /tmp/aclaniakea.jks (签名密钥，不入库)
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$PWD
export ANDROID_SDK=${ANDROID_SDK:-/tmp/android-sdk}
export XPOSED_STUBS=${XPOSED_STUBS:-/tmp/acdb/stubs}
export PEN_SMALI_TOOLS_DIR=${PEN_SMALI_TOOLS_DIR:-/tmp/codex-dex-tools}
export SMALI_JAR=${SMALI_JAR:-/tmp/codex-dex-tools/smali.jar}
export ACL_KS=${ACL_KS:-/tmp/aclaniakea.jks}

PEN_BASE_APK=${PEN_BASE_APK:-/run/media/ACLaniakea/IXUNICS/pad/workspace/lenovo-pen-bridge-hook/release/LenovoPenBridge-Hook-v1.0.68-r50-haptic-signed.apk}

echo "== 1/6 base-fix hook APK =="
python3 base-fix/hook/tools/build_integrated_hook.py

echo "== 2/6 pen-bridge hook APK (from r50 dex) =="
python3 pen-bridge/hook/tools/build_hook_v1.py "$PEN_BASE_APK"

echo "== 3/6 PenHidCtl APK =="
python3 pen-bridge/penhidctl/tools/build_penhid.py

echo "== 4/6 refresh PenHidCtl inside pen-bridge module =="
cp releases/PenHidCtl-v1.0.0.apk pen-bridge/module/system/priv-app/penhidctl/PenHidCtl.apk

echo "== 5/6 module zips =="
python3 base-fix/module/tools/build_release.py
python3 pen-bridge/module/tools/build_root.py pen-bridge/module releases/PenBridge-Module-v1.0.0.zip
python3 port-tuning/tools/build_tuning.py

echo "== 6/6 releases =="
ls -la releases/
