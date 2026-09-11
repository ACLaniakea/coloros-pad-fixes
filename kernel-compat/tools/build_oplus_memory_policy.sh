#!/usr/bin/env bash
set -euo pipefail

BUILD_ID=13606743
GKI_COMMIT=5c2cea985a841939e6d074cbed2019dec0245fcd
GKI_SHORT=5c2cea985a84
CI_BASE="https://ci.android.com/builds/submitted/${BUILD_ID}/kernel_aarch64/latest/raw"
CACHE_ROOT=${KERNEL_COMPAT_CACHE:-/tmp/coloros-pad-kernel-compat}
SOURCE_ARCHIVE="${CACHE_ROOT}/common-${GKI_SHORT}.tar.gz"
PREPARE_ARCHIVE="${CACHE_ROOT}/modules_prepare_outdir-${BUILD_ID}.tar.gz"
SYMVERS_FILE="${CACHE_ROOT}/kernel_aarch64_Module-${BUILD_ID}.symvers"
SOURCE_DIR="${CACHE_ROOT}/common-${GKI_SHORT}"
OUTPUT_DIR="${CACHE_ROOT}/out-${BUILD_ID}"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)
MODULE_DIR="${PROJECT_ROOT}/kernel-compat/oplus_memory_policy"

download_if_missing() {
    local url=$1 destination=$2
    # Do not use curl resume against CI redirectors: some of them acknowledge a
    # range request but return a fresh, truncated body.  A partially downloaded
    # kernel archive must never be reused for a module build.
    if [ ! -s "${destination}" ]; then
        rm -f "${destination}.part"
        curl -L --fail --retry 3 --retry-all-errors -o "${destination}.part" "${url}"
        mv -f "${destination}.part" "${destination}"
    fi
}

mkdir -p "${CACHE_ROOT}" "${SOURCE_DIR}" "${OUTPUT_DIR}"
download_if_missing "https://android.googlesource.com/kernel/common/+archive/${GKI_COMMIT}.tar.gz" "${SOURCE_ARCHIVE}"
download_if_missing "${CI_BASE}/modules_prepare_outdir.tar.gz" "${PREPARE_ARCHIVE}"
download_if_missing "${CI_BASE}/kernel_aarch64_Module.symvers" "${SYMVERS_FILE}"
[ -f "${SOURCE_DIR}/Kconfig" ] || tar -xzf "${SOURCE_ARCHIVE}" -C "${SOURCE_DIR}"
[ -f "${OUTPUT_DIR}/.config" ] || tar -xzf "${PREPARE_ARCHIVE}" -C "${OUTPUT_DIR}"
cp "${SYMVERS_FILE}" "${OUTPUT_DIR}/Module.symvers"

command -v ld.lld >/dev/null
make -C "${SOURCE_DIR}" O="${OUTPUT_DIR}" M="${MODULE_DIR}" LLVM=1 ARCH=arm64 W=1 modules

python3 "${SCRIPT_DIR}/verify_module_abi.py" "${SYMVERS_FILE}" \
  --allow-restricted android_rvh_probe_register,__tracepoint_android_rvh_set_balance_anon_file_reclaim \
  "${MODULE_DIR}/oplus_bsp_zram_opt.ko" \
  "${MODULE_DIR}/oplus_bsp_kswapd_opt.ko"
