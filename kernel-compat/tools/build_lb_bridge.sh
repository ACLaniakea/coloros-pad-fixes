#!/usr/bin/env bash
# 构建 oplus_lb_bridge.ko —— 把 sched_assist 的 tick 负载均衡入口接回调度器。
# 背景与风险见 kernel-compat/oplus_lb_bridge/oplus_lb_bridge.c 顶部注释。
#
# 与 build_gki_compat.sh 的唯一区别：本模块要链接 oplus_cpu_sched_sched_assist
# 导出的 __oplus_tick_balance。那是个 OPlus 预编译 .ko，没有配套的
# Module.symvers，所以这里直接从它的 __kcrctab 段把 CRC 抠出来，现场合成一份
# 喂给 modpost。CRC 取自提供者而不是我们的原型，因此 struct rq 在本模块里
# 保持不完整类型也不影响加载校验。
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
MODULE_DIR="${PROJECT_ROOT}/kernel-compat/oplus_lb_bridge"
PROVIDER_KO="${PROJECT_ROOT}/oplus-bsp-module/ko/oplus_cpu_sched_sched_assist.ko"
PROVIDER_NAME=oplus_cpu_sched_sched_assist
EXTRA_SYMVERS="${MODULE_DIR}/Module.symvers.provider"

download_if_missing() {
    local url=$1 destination=$2
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

[ -f "${SOURCE_DIR}/Kconfig" ] || tar -xzf "${SOURCE_ARCHIVE}" -C "${SOURCE_DIR}"
[ -f "${OUTPUT_DIR}/.config" ] || tar -xzf "${PREPARE_ARCHIVE}" -C "${OUTPUT_DIR}"
cp "${SYMVERS_FILE}" "${OUTPUT_DIR}/Module.symvers"

command -v ld.lld >/dev/null 2>&1 || {
    echo "需要 ld.lld，请安装 lld 或把它的 bin 目录加进 PATH" >&2; exit 2; }
[ -f "${PROVIDER_KO}" ] || { echo "找不到提供者 .ko：${PROVIDER_KO}" >&2; exit 2; }

# ---- 从提供者 .ko 抠出导出符号的 CRC，合成一份 Module.symvers ----------------
python3 - "${PROVIDER_KO}" "${PROVIDER_NAME}" "${EXTRA_SYMVERS}" <<'PY'
import struct, subprocess, sys, tempfile, pathlib

ko, provider, out = sys.argv[1], sys.argv[2], sys.argv[3]
WANTED = {"__oplus_tick_balance"}

# __kcrctab 是 u32 数组；__crc_<sym> 这些局部符号给出各自在段内的偏移。
syms = subprocess.check_output(["llvm-objdump", "-t", ko], text=True)
offsets = {}
for line in syms.splitlines():
    parts = line.split()
    if len(parts) < 2 or "__kcrctab" not in line:
        continue
    name = parts[-1]
    if name.startswith("__crc_"):
        offsets[name[len("__crc_"):]] = int(parts[0], 16)

with tempfile.TemporaryDirectory() as td:
    blob = pathlib.Path(td) / "kcrctab.bin"
    subprocess.check_call(
        ["llvm-objcopy", "-O", "binary", "--only-section=__kcrctab", ko, str(blob)])
    data = blob.read_bytes()

lines = []
for sym in sorted(WANTED):
    if sym not in offsets:
        sys.exit(f"提供者 .ko 里没有导出 {sym}")
    off = offsets[sym]
    if off + 4 > len(data):
        sys.exit(f"{sym} 的 CRC 偏移 {off} 超出 __kcrctab 段长 {len(data)}")
    crc = struct.unpack_from("<I", data, off)[0]
    lines.append(f"0x{crc:08x}\t{sym}\t{provider}\tEXPORT_SYMBOL\t")
    print(f"  {sym} CRC = 0x{crc:08x}  (来自 {provider})")

pathlib.Path(out).write_text("\n".join(lines) + "\n")
PY

make -C "${MODULE_DIR}" \
    KERNEL_SRC="${SOURCE_DIR}" \
    KERNEL_OUT="${OUTPUT_DIR}" \
    KBUILD_EXTRA_SYMBOLS="${EXTRA_SYMVERS}" \
    LLVM=1 ARCH=arm64 W=1

echo "已构建 ${MODULE_DIR}/oplus_lb_bridge.ko"
modinfo "${MODULE_DIR}/oplus_lb_bridge.ko"
modprobe --show-modversions "${MODULE_DIR}/oplus_lb_bridge.ko"

# ABI 门禁：把提供者的那条 CRC 并进 GKI symvers 一起校验，
# 并显式声明对 sched_assist 的依赖（它是本模块存在的全部理由）。
COMBINED="${CACHE_ROOT}/symvers-with-provider-${BUILD_ID}.txt"
cat "${SYMVERS_FILE}" "${EXTRA_SYMVERS}" > "${COMBINED}"
python3 "${SCRIPT_DIR}/verify_module_abi.py" "${COMBINED}" \
    --allow-depends "${PROVIDER_NAME}" \
    "${MODULE_DIR}/oplus_lb_bridge.ko"
