#!/usr/bin/env bash
# Build the read-only QTI IPerf Binder accessibility probe for arm64 Android.
# It supports this workspace's trimmed NDK (host clang + retained sysroot).
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)
NDK_ROOT=${ANDROID_NDK_ROOT:-"${PROJECT_ROOT}/workspace/toolchains/android-ndk-r26d"}
TOOLCHAIN="${NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64"
# This bundled NDK ships API 34 as its highest platform.  It is ABI-compatible with the
# target's API 36 for this stable NDK Binder call.
CLANG_WRAPPER="${TOOLCHAIN}/bin/aarch64-linux-android34-clang"
OUT="${SCRIPT_DIR}/qti_perf_probe"

if test -x "${TOOLCHAIN}/bin/clang"; then
    "${CLANG_WRAPPER}" -Wall -Wextra -Werror -O2 -fPIE -pie \
        "${SCRIPT_DIR}/qti_perf_probe.c" -lbinder_ndk -ldl -o "${OUT}"
else
    # This workspace's NDK was archived without the host clang binary.  Reuse
    # host clang, but retain NDK headers, crt objects and Android compiler-rt.
    command -v clang >/dev/null || { echo "host clang not found" >&2; exit 2; }
    clang --target=aarch64-linux-android34 --sysroot="${TOOLCHAIN}/sysroot" \
        -resource-dir="${TOOLCHAIN}/lib/clang/17" -fuse-ld=lld \
        -Wall -Wextra -Werror -O2 -fPIE -pie \
        "${SCRIPT_DIR}/qti_perf_probe.c" -lbinder_ndk -ldl -o "${OUT}"
fi
echo "built ${OUT}"
