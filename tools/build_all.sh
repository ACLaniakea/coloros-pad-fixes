#!/usr/bin/env bash
# ACLaniakea ColorOS Pad Port Fixes - 3.2.1 全量构建
# 输出统一到 releases/。
#
# 工具链默认指向随盘保存的那一份（PAD_KIT），不再依赖 /tmp——/tmp 会被清空，
# 之前就因此整条 APK 构建线挂掉过。要临时换别处的工具链，仍可用环境变量覆盖。
#   $PAD_KIT/tools/sdk   aapt2 / d8 / zipalign / apksigner / android-35
#   $PAD_KIT/tools/dex   smali / baksmali / r8 / xposed-api-82
#   $PAD_KIT/keys        签名密钥（不入库）
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$PWD
PAD_KIT=${PAD_KIT:-/run/media/ACLaniakea/IXUNICS/pad}
# The checked-in minimal SDK is the reproducible fallback.  A full external
# SDK remains preferred when present, but releases must also build on a clean
# workstation that only has workspace/min-sdk (currently build-tools 34).
if [ -z "${ANDROID_SDK:-}" ] && [ -d "$PAD_KIT/tools/sdk" ]; then
    export ANDROID_SDK="$PAD_KIT/tools/sdk"
else
    export ANDROID_SDK=${ANDROID_SDK:-$ROOT/workspace/min-sdk}
fi
export XPOSED_STUBS=${XPOSED_STUBS:-$PAD_KIT/tools/dex/xposed-api-82.jar}
export PEN_SMALI_TOOLS_DIR=${PEN_SMALI_TOOLS_DIR:-$PAD_KIT/tools/dex}
export SMALI_JAR=${SMALI_JAR:-$PAD_KIT/tools/dex/smali-fat.jar}
export ACL_R8_JAR=${ACL_R8_JAR:-$PAD_KIT/tools/dex/r8.jar}
export ACL_KS=${ACL_KS:-$PAD_KIT/keys/aclaniakea.jks}

if [ -x "$ANDROID_SDK/build-tools/android-15/aapt2" ]; then
    export ACL_BUILD_TOOLS=android-15
elif [ -x "$ANDROID_SDK/build-tools/android-14/aapt2" ]; then
    export ACL_BUILD_TOOLS=android-14
else
    echo "工具链缺件: $ANDROID_SDK/build-tools/{android-15,android-14}/aapt2" >&2
    exit 1
fi

for _need in "$ANDROID_SDK/build-tools/$ACL_BUILD_TOOLS/aapt2" \
             "$ANDROID_SDK/platforms/android-35/android.jar" \
             "$XPOSED_STUBS" "$SMALI_JAR" "$ACL_R8_JAR" "$ACL_KS"; do
    [ -e "$_need" ] || { echo "工具链缺件: $_need" >&2; exit 1; }
done

echo "== 1/8 base-fix hook APK =="
python3 base-fix/hook/tools/build_integrated_hook.py

echo "== 2/8 pen-bridge hook APK (from Java source) =="
python3 pen-bridge/hook/tools/build_hook_source.py

echo "== 3/8 PenHidCtl APK =="
python3 pen-bridge/penhidctl/tools/build_penhid.py

echo "== 4/8 refresh PenHidCtl inside pen-bridge module =="
cp releases/PenHidCtl-v3.2.1.apk pen-bridge/module/system/priv-app/aclpenhid/PenHidCtl.apk

echo "== 5/8 root module zips =="
python3 fix-module/tools/build_fix.py
python3 pen-bridge/module/tools/build_root.py pen-bridge/module releases/PenBridge-Module-v3.2.1.zip

echo "== 6/8 OPlus BSP kernel modules =="
python3 oplus-bsp-module/tools/build_oplus_bsp.py

echo "== 7/8 SM8650Q Scene scheduler =="
python3 scheduler-module/tools/build_scheduler.py

echo "== 8/9 ZUI Camera Hook and module =="
python3 zui-camera-compat-hook/build.py
python3 zui-camera-module/tools/build_module.py

echo "== 9/9 optional tuning module =="
python3 port-tuning/tools/build_tuning.py
echo "== releases =="
ls -la releases/
