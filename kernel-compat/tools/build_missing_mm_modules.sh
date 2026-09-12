#!/usr/bin/env bash
# 补齐移植包里缺失的一加内存/事件模块。两项都不用 vendor hook，只吃 GKI 已导出的符号：
#   oplus_mm_proactive_compact  -> /proc/oplus_mem/fragmentation_index（对照机有，平板缺）
#   oplus_mm_kevent{,_fb}       -> generic netlink 事件通道，bsp_kevent 靠它才不会启动即退
# 用法与 build_oplus_memory_policy.sh 一致：缓存 GKI 源码+symvers，就地编，过 ABI 门禁。
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

download_if_missing() {
    local url=$1 destination=$2
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
for m in oplus_proactive_compact oplus_mm_kevent; do
    d="${PROJECT_ROOT}/kernel-compat/${m}"
    echo "==== 编译 ${m}"
    make -C "${SOURCE_DIR}" O="${OUTPUT_DIR}" M="${d}" LLVM=1 ARCH=arm64 modules
done

python3 "${SCRIPT_DIR}/verify_module_abi.py" "${SYMVERS_FILE}" \
  "${PROJECT_ROOT}/kernel-compat/oplus_proactive_compact/oplus_mm_proactive_compact.ko" \
  "${PROJECT_ROOT}/kernel-compat/oplus_mm_kevent/oplus_mm_kevent.ko"

# kevent_fb 吃的是 kevent 导出的两个符号，GKI 的 symvers 里当然没有。
# 把同批 kevent 的 symvers 并进来再验，依赖关系写明是「已决定」而不是默认放行。
MERGED_SYMVERS="${CACHE_ROOT}/symvers-with-kevent-${BUILD_ID}.txt"
cat "${SYMVERS_FILE}" "${PROJECT_ROOT}/kernel-compat/oplus_mm_kevent/Module.symvers" > "${MERGED_SYMVERS}"
python3 "${SCRIPT_DIR}/verify_module_abi.py" "${MERGED_SYMVERS}" \
  --allow-depends oplus_mm_kevent \
  "${PROJECT_ROOT}/kernel-compat/oplus_mm_kevent/oplus_mm_kevent_fb.ko"
