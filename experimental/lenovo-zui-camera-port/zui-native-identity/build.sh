#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "$0")" && pwd)
out_dir="$root_dir/out"
stage_dir="$out_dir/stage"
android_lib_dir=${ZUI_ANDROID_LIB_DIR:?set ZUI_ANDROID_LIB_DIR to arm64 Android system libraries}

rm -rf "$stage_dir"
mkdir -p "$stage_dir/zygisk" "$out_dir"

clang++ --target=aarch64-linux-android36 -std=c++20 -Oz -fPIC -fvisibility=hidden \
  -fno-exceptions -fno-rtti -ffreestanding -nostdlib -shared -fuse-ld=lld \
  -Wl,-soname,zui_native_identity.so -L "$android_lib_dir" -llog -ldl -lc \
  "$root_dir/zui_native_identity.cpp" -o "$stage_dir/zygisk/arm64-v8a.so"

cp "$root_dir/module.prop" "$stage_dir/module.prop"
(cd "$stage_dir" && jar --create --file "$out_dir/zui_native_identity_experimental.zip" .)
sha256sum "$stage_dir/zygisk/arm64-v8a.so" "$out_dir/zui_native_identity_experimental.zip"
