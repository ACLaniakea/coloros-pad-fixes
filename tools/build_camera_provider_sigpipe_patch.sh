#!/usr/bin/env bash
# Build a narrowly-scoped SIGPIPE guard for the OPD2513 camera provider.
#
# The loader invokes the first init-array constructor through the existing
# R_AARCH64_RELATIVE relocation at 0x8018.  We repoint that one relocation to
# a BTI-compatible constructor in the unused executable gap at 0x7528.  It
# installs SIG_IGN for SIGPIPE, then tail-calls the original 0x5654 ctor.
set -euo pipefail

readonly stock_sha=751fa24b9d44a790a2594828c5aaa2a9e98f1a2a7734cf7cd103023893031f6a
readonly injection_offset=$((0x7528))
readonly relocation_addend_offset=$((0x23c0))
readonly patch_size=$((0x64))

repo_dir=$(cd "$(dirname "$0")/.." && pwd)
input=${1:?usage: $0 STOCK_PROVIDER [OUTPUT_PROVIDER]}
output=${2:-"$repo_dir/fix-module/module/payload/camera/vendor.qti.camera.provider-service_64"}
assembly="$repo_dir/tools/camera-provider-sigpipe-constructor.S"
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT

test "$(sha256sum "$input" | awk '{print $1}')" = "$stock_sha" || {
    echo "refusing unexpected provider input: $input" >&2
    exit 1
}

llvm-mc -triple=aarch64-linux-android -filetype=obj "$assembly" -o "$work_dir/guard.o"
llvm-objcopy -O binary --only-section=.text "$work_dir/guard.o" "$work_dir/guard.bin"
test "$(stat -c%s "$work_dir/guard.bin")" -ge $((injection_offset + patch_size))

mkdir -p "$(dirname "$output")"
cp "$input" "$output"

# R_AARCH64_RELATIVE at file offset 0x23b0 is the original first .init_array
# constructor. Its addend lives at 0x23c0 and changes from 0x5654 to 0x7528.
printf '\x28\x75\x00\x00\x00\x00\x00\x00' |
    dd of="$output" bs=1 seek="$relocation_addend_offset" conv=notrunc status=none
dd if="$work_dir/guard.bin" of="$output" bs=1 skip="$injection_offset" \
    seek="$injection_offset" count="$patch_size" conv=notrunc status=none

test "$(sha256sum "$input" | awk '{print $1}')" = "$stock_sha"
dd if="$work_dir/guard.bin" bs=1 skip="$injection_offset" count="$patch_size" status=none |
    cmp -s - <(dd if="$output" bs=1 skip="$injection_offset" count="$patch_size" status=none)
readelf -rW "$output" |
    grep -q '0000000000008018.*R_AARCH64_RELATIVE.*7528'

echo "built $output"
sha256sum "$output"
