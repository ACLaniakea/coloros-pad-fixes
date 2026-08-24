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
MODULE_DIR="${PROJECT_ROOT}/kernel-compat/oplus_compat"

download_if_missing() {
    local url=$1
    local destination=$2
    if [ ! -s "${destination}" ]; then
        curl -L --fail --retry 3 --continue-at - -o "${destination}" "${url}"
    fi
}

mkdir -p "${CACHE_ROOT}" "${SOURCE_DIR}" "${OUTPUT_DIR}"

download_if_missing \
    "https://android.googlesource.com/kernel/common/+archive/${GKI_COMMIT}.tar.gz" \
    "${SOURCE_ARCHIVE}"
download_if_missing "${CI_BASE}/modules_prepare_outdir.tar.gz" "${PREPARE_ARCHIVE}"
download_if_missing "${CI_BASE}/kernel_aarch64_Module.symvers" "${SYMVERS_FILE}"

if [ ! -f "${SOURCE_DIR}/Kconfig" ]; then
    tar -xzf "${SOURCE_ARCHIVE}" -C "${SOURCE_DIR}"
fi
if [ ! -f "${OUTPUT_DIR}/.config" ]; then
    tar -xzf "${PREPARE_ARCHIVE}" -C "${OUTPUT_DIR}"
fi
cp "${SYMVERS_FILE}" "${OUTPUT_DIR}/Module.symvers"

if ! command -v ld.lld >/dev/null 2>&1; then
    echo "ld.lld is required; install the lld package or prepend its bin directory to PATH" >&2
    exit 2
fi

make -C "${MODULE_DIR}" \
    KERNEL_SRC="${SOURCE_DIR}" \
    KERNEL_OUT="${OUTPUT_DIR}" \
    LLVM=1 ARCH=arm64 W=1

for module in oplus_shell_temp_compat oplus_mm_compat; do
    echo "Built ${MODULE_DIR}/${module}.ko"
    modinfo "${MODULE_DIR}/${module}.ko"
    modprobe --show-modversions "${MODULE_DIR}/${module}.ko"
done

python3 "${SCRIPT_DIR}/verify_module_abi.py" "${SYMVERS_FILE}" \
    "${MODULE_DIR}/oplus_shell_temp_compat.ko" \
    "${MODULE_DIR}/oplus_mm_compat.ko"
