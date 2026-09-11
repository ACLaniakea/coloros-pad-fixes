#!/usr/bin/env bash
# Build the staged OPlus-UX -> Qualcomm-WALT task boost bridge.
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
MODULE_DIR="${PROJECT_ROOT}/kernel-compat/oplus_walt_bridge"
PROVIDER_KO="${PROJECT_ROOT}/oplus-bsp-module/ko/oplus_cpu_sched_sched_assist.ko"
PROVIDER_NAME=oplus_cpu_sched_sched_assist
WALT_PROVIDER_NAME=sched_walt
EXTRA_SYMVERS="${MODULE_DIR}/Module.symvers.provider"

download_if_missing() {
    local url=$1 destination=$2
    [ -s "${destination}" ] || curl -L --fail --retry 3 --continue-at - \
        -o "${destination}" "${url}"
}

mkdir -p "${CACHE_ROOT}" "${SOURCE_DIR}" "${OUTPUT_DIR}"
download_if_missing \
    "https://android.googlesource.com/kernel/common/+archive/${GKI_COMMIT}.tar.gz" \
    "${SOURCE_ARCHIVE}"
download_if_missing "${CI_BASE}/modules_prepare_outdir.tar.gz" "${PREPARE_ARCHIVE}"
download_if_missing "${CI_BASE}/kernel_aarch64_Module.symvers" "${SYMVERS_FILE}"

[ -f "${SOURCE_DIR}/Kconfig" ] || tar -xzf "${SOURCE_ARCHIVE}" -C "${SOURCE_DIR}"
[ -f "${OUTPUT_DIR}/.config" ] || tar -xzf "${PREPARE_ARCHIVE}" -C "${OUTPUT_DIR}"
cp "${SYMVERS_FILE}" "${OUTPUT_DIR}/Module.symvers"

command -v ld.lld >/dev/null 2>&1 || {
    echo "需要 ld.lld，请安装 lld 或把它的 bin 目录加进 PATH" >&2; exit 2; }
[ -f "${PROVIDER_KO}" ] || { echo "找不到提供者：${PROVIDER_KO}" >&2; exit 2; }

python3 - "${PROVIDER_KO}" "${PROVIDER_NAME}" "${EXTRA_SYMVERS}" <<'PY'
import pathlib
import struct
import subprocess
import sys
import tempfile

ko, provider, out = sys.argv[1:]
wanted = {"test_task_ux"}
symbols = subprocess.check_output(["llvm-objdump", "-t", ko], text=True)
offsets = {}
for line in symbols.splitlines():
    parts = line.split()
    if len(parts) >= 2 and "__kcrctab" in line and parts[-1].startswith("__crc_"):
        section = next((p for p in parts if p in {"__kcrctab", "__kcrctab_gpl"}), None)
        if section:
            offsets[parts[-1][len("__crc_"):]] = (int(parts[0], 16), section)

with tempfile.TemporaryDirectory() as temp_dir:
    section_data = {}
    for section in {"__kcrctab", "__kcrctab_gpl"}:
        blob = pathlib.Path(temp_dir) / f"{section}.bin"
        subprocess.check_call([
            "llvm-objcopy", "-O", "binary", f"--only-section={section}", ko, str(blob)
        ])
        section_data[section] = blob.read_bytes()

lines = []
for symbol in sorted(wanted):
    if symbol not in offsets:
        sys.exit(f"提供者中没有导出 {symbol}")
    offset, section = offsets[symbol]
    crc = struct.unpack_from("<I", section_data[section], offset)[0]
    export = "EXPORT_SYMBOL_GPL" if section == "__kcrctab_gpl" else "EXPORT_SYMBOL"
    lines.append(f"0x{crc:08x}\t{symbol}\t{provider}\t{export}\t")
    print(f"  {symbol} CRC = 0x{crc:08x}")
pathlib.Path(out).write_text("\n".join(lines) + "\n")
PY

# Target vendor_boot's sched-walt.ko exports set_task_boost as GPL-only.
# CRC was read from __kcrctab_gpl at __crc_set_task_boost and is also checked
# against the live module by verify_module_abi after deployment.
printf '0xa7807513\tset_task_boost\tsched_walt\tEXPORT_SYMBOL_GPL\t\n' \
    >> "${EXTRA_SYMVERS}"

make -C "${MODULE_DIR}" KERNEL_SRC="${SOURCE_DIR}" KERNEL_OUT="${OUTPUT_DIR}" \
    KBUILD_EXTRA_SYMBOLS="${EXTRA_SYMVERS}" LLVM=1 ARCH=arm64 W=1

COMBINED="${CACHE_ROOT}/symvers-with-walt-providers-${BUILD_ID}.txt"
cat "${SYMVERS_FILE}" "${EXTRA_SYMVERS}" > "${COMBINED}"
python3 "${SCRIPT_DIR}/verify_module_abi.py" "${COMBINED}" \
    --allow-depends "${PROVIDER_NAME},${WALT_PROVIDER_NAME}" \
    "${MODULE_DIR}/oplus_walt_bridge.ko"
install -m 0644 "${MODULE_DIR}/oplus_walt_bridge.ko" \
    "${PROJECT_ROOT}/oplus-bsp-module/ko/oplus_walt_bridge.ko"
echo "已安装到 oplus-bsp-module/ko/oplus_walt_bridge.ko"
