#!/usr/bin/env bash
# Build aclswap.ko: the stock GKI zram driver rebuilt out-of-tree with writeback
# enabled, renamed so it coexists with the loaded GKI zram.ko. See
# kernel-compat/aclswap/README.md for why this exists and what the patch changes.
set -euo pipefail

BUILD_ID=13606743
GKI_COMMIT=5c2cea985a841939e6d074cbed2019dec0245fcd
GKI_SHORT=5c2cea985a84
CI_BASE="https://ci.android.com/builds/submitted/${BUILD_ID}/kernel_aarch64/latest/raw"
CACHE_ROOT=${KERNEL_COMPAT_CACHE:-${HOME}/coloros-kernel-build}
SOURCE_ARCHIVE="${CACHE_ROOT}/common-src.tar.gz"
PREPARE_ARCHIVE="${CACHE_ROOT}/modules_prepare.tar.gz"
SYMVERS_FILE="${CACHE_ROOT}/Module.symvers"
SOURCE_DIR="${CACHE_ROOT}/src"
OUTPUT_DIR="${CACHE_ROOT}/out"
BUILD_DIR="${CACHE_ROOT}/aclswap-build"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)
MODULE_DIR="${PROJECT_ROOT}/kernel-compat/aclswap"

download_if_missing() {
    [ -s "$2" ] && return 0
    curl -L --fail --retry 3 --continue-at - -o "$2" "$1"
}

mkdir -p "${CACHE_ROOT}" "${SOURCE_DIR}" "${OUTPUT_DIR}"
download_if_missing \
    "https://android.googlesource.com/kernel/common/+archive/${GKI_COMMIT}.tar.gz" \
    "${SOURCE_ARCHIVE}"
download_if_missing "${CI_BASE}/modules_prepare_outdir.tar.gz" "${PREPARE_ARCHIVE}"
download_if_missing "${CI_BASE}/kernel_aarch64_Module.symvers" "${SYMVERS_FILE}"

[ -f "${SOURCE_DIR}/Makefile" ] || tar -xzf "${SOURCE_ARCHIVE}" -C "${SOURCE_DIR}"
[ -f "${OUTPUT_DIR}/.config" ] || tar -xzf "${PREPARE_ARCHIVE}" -C "${OUTPUT_DIR}"
cp -f "${SYMVERS_FILE}" "${OUTPUT_DIR}/Module.symvers"

command -v ld.lld >/dev/null 2>&1 || {
    echo "ld.lld is required; install lld or prepend its bin directory to PATH" >&2
    exit 2
}

# Always rebuild from pristine sources plus the patch, so the delta stays honest:
# a stale hand-edit in the build directory can never masquerade as the shipped patch.
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
cp "${SOURCE_DIR}/drivers/block/zram/zcomp.c" \
   "${SOURCE_DIR}/drivers/block/zram/zcomp.h" \
   "${SOURCE_DIR}/drivers/block/zram/zram_drv.c" \
   "${SOURCE_DIR}/drivers/block/zram/zram_drv.h" \
   "${BUILD_DIR}/"
cp "${MODULE_DIR}/Makefile" "${BUILD_DIR}/Makefile"
(cd "${BUILD_DIR}" && patch -p1 --batch < "${MODULE_DIR}/aclswap.patch")

make -C "${BUILD_DIR}" KERNEL_SRC="${SOURCE_DIR}" KERNEL_OUT="${OUTPUT_DIR}" \
     LLVM=1 ARCH=arm64

echo "Built ${BUILD_DIR}/aclswap.ko"
modinfo "${BUILD_DIR}/aclswap.ko" | grep -E "vermagic|name|license"

if [ -f "${SCRIPT_DIR}/verify_module_abi.py" ]; then
    # zsmalloc is the compressed-page allocator zram has always used; the
    # dependency is inherent to the driver, not scope creep.
    python3 "${SCRIPT_DIR}/verify_module_abi.py" --allow-depends zsmalloc \
        "${SYMVERS_FILE}" "${BUILD_DIR}/aclswap.ko"
fi

install -m 0644 "${BUILD_DIR}/aclswap.ko" \
    "${PROJECT_ROOT}/fix-module/module/bin/aclswap.ko"
echo "Installed into fix-module/module/bin/aclswap.ko"

# oplus_sched_assist shares the same toolchain and pinned GKI; build it here
# rather than duplicating the download and verification.
SCHED_SRC="${PROJECT_ROOT}/kernel-compat/oplus_sched_assist"
SCHED_BUILD="${CACHE_ROOT}/oplus_sched_assist-build"
rm -rf "${SCHED_BUILD}"
mkdir -p "${SCHED_BUILD}"
cp "${SCHED_SRC}/oplus_sched_assist.c" "${SCHED_SRC}/Makefile" "${SCHED_BUILD}/"
make -C "${SCHED_BUILD}" KERNEL_SRC="${SOURCE_DIR}" KERNEL_OUT="${OUTPUT_DIR}" \
     LLVM=1 ARCH=arm64
if [ -f "${SCRIPT_DIR}/verify_module_abi.py" ]; then
    python3 "${SCRIPT_DIR}/verify_module_abi.py" "${SYMVERS_FILE}" \
        "${SCHED_BUILD}/oplus_sched_assist.ko"
fi
install -m 0644 "${SCHED_BUILD}/oplus_sched_assist.ko" \
    "${PROJECT_ROOT}/fix-module/module/bin/oplus_sched_assist.ko"
echo "Installed into fix-module/module/bin/oplus_sched_assist.ko"
