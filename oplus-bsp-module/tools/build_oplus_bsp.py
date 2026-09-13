#!/usr/bin/env python3
"""Package the OPlus BSP kernel-module Magisk/KernelSU zip.

The module ships the .ko files themselves plus the dependency-ordered loader in
post-fs-data.sh; there is nothing to compile here, so this is a pure packaging
step (same shape as the other module builders: deterministic order, explicit
required-file list, mode bits preserved).
"""

from __future__ import annotations

import stat
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
REQUIRED = ("module.prop", "post-fs-data.sh", "service.sh")
# HybridSwap 已内建 Lenovo 面板 kprobe；这个早期独立桥探测同一事件，且只为旧
# HybridSwap 二进制构建。源码试验件留在 kernel-compat/，不得随正式包出货。
EXCLUDED_KOS = {"oplus_hybridswap_panel_bridge.ko"}


def version() -> str:
    for line in (ROOT / "module.prop").read_text(encoding="utf-8").splitlines():
        if line.startswith("version="):
            return line.split("=", 1)[1].strip().lstrip("v")
    raise SystemExit("module.prop has no version=")


def main() -> None:
    missing = [n for n in REQUIRED if not (ROOT / n).is_file()]
    if missing:
        raise SystemExit("missing module files: " + ", ".join(missing))
    kos = sorted(ko for ko in (ROOT / "ko").glob("*.ko")
                 if ko.name not in EXCLUDED_KOS)
    if not kos:
        raise SystemExit("no .ko under oplus-bsp-module/ko/")
    out = REPO / "releases" / f"OplusBSP-Modules-v{version()}.zip"
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as z:
        for name in REQUIRED:
            path = ROOT / name
            info = zipfile.ZipInfo(name)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (stat.S_IMODE(path.stat().st_mode) | stat.S_IFREG) << 16
            z.writestr(info, path.read_bytes())
        for ko in kos:
            info = zipfile.ZipInfo(f"ko/{ko.name}")
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o644 | stat.S_IFREG) << 16
            z.writestr(info, ko.read_bytes())
    print(out, f"({len(kos)} modules)")


if __name__ == "__main__":
    main()
