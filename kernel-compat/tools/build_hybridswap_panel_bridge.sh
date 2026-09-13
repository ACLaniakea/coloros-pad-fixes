#!/usr/bin/env bash
set -euo pipefail
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cache=${KERNEL_COMPAT_CACHE:-/tmp/coloros-pad-kernel-compat}
make -C "$cache/common-5c2cea985a84" O="$cache/out-13606743" \
  M="$root/kernel-compat/oplus_hybridswap_panel_bridge" LLVM=1 ARCH=arm64 W=1 modules
cp "$root/kernel-compat/oplus_hybridswap_panel_bridge/oplus_hybridswap_panel_bridge.ko" \
  "$root/oplus-bsp-module/ko/"
