#!/usr/bin/env bash
# Regenerate the swappiness one-time patch for the Qualcomm post-boot scripts.
#
# The ColorOS/QTI boot scripts hard-code "echo 100 > /proc/sys/vm/swappiness",
# which overrides the module's early VM baseline after every reboot. This tool
# takes the vendor originals and produces the patched copies shipped inside
# fix-module/module/payload/bin/, where every swappiness write is 10 instead
# of 100. The module bind-mounts these copies over /vendor/bin at
# post-fs-data, so the boot scripts themselves write 5 (root-cause fix, no
# polling guard).
#
# Usage:
#   patch_postboot.sh <vendor-dir> [output-dir]
#   e.g. patch_postboot.sh /path/to/extracted/vendor/bin \
#        fix-module/module/payload/bin
set -euo pipefail

SRC_DIR="${1:?usage: patch_postboot.sh <vendor-bin-dir> [output-dir]}"
OUT_DIR="${2:-$(cd "$(dirname "$0")/.." && pwd)/module/payload/bin}"

mkdir -p "$OUT_DIR"
for name in init.qcom.post_boot.sh init.kernel.post_boot.sh; do
    src="$SRC_DIR/$name"
    dst="$OUT_DIR/$name"
    [ -f "$src" ] || { echo "missing vendor script: $src" >&2; exit 1; }
    sed 's/echo 100 > \/proc\/sys\/vm\/swappiness/echo 10 > \/proc\/sys\/vm\/swappiness/g' \
        "$src" >"$dst"
    chmod 0755 "$dst"
    echo "patched $dst"
done
