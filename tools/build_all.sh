#!/usr/bin/env bash
# ACLaniakea ColorOS Pad Port Fixes - 2.0.0 全量构建
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
export ACL_KS=${ACL_KS:-/run/media/ACLaniakea/IXUNICS/pad/keys/aclaniakea.jks}

echo "== 1/7 base-fix hook APK =="
python3 base-fix/hook/tools/build_integrated_hook.py

echo "== 2/7 pen-bridge hook APK (from Java source) =="
python3 pen-bridge/hook/tools/build_hook_source.py

echo "== 3/7 PenHidCtl APK =="
python3 pen-bridge/penhidctl/tools/build_penhid.py

echo "== 4/7 refresh PenHidCtl inside pen-bridge module =="
cp releases/PenHidCtl-v1.1.0.apk pen-bridge/module/system/priv-app/aclpenhid/PenHidCtl.apk

echo "== 5/7 root module zips =="
python3 fix-module/tools/build_fix.py
python3 pen-bridge/module/tools/build_root.py pen-bridge/module releases/PenBridge-Module-v1.1.19.zip

echo "== 6/7 SM8650Q Scene scheduler =="
python3 scheduler-module/tools/build_scheduler.py

echo "== 7/7 releases =="
ls -la releases/
