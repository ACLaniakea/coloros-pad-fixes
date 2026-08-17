#!/usr/bin/env python3
"""Package the ColorOS port-tuning KernelSU module (ACLaniakea 1.1.0)."""

from __future__ import annotations

import stat
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1] / "module"
OUT = ROOT.parents[1] / "releases" / "PortTuning-Module-v1.1.2.zip"
EXCLUDE = {"tuning.log", "daemon.pid"}


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUT, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
        for path in sorted(p for p in ROOT.rglob("*") if p.is_file()):
            rel = path.relative_to(ROOT).as_posix()
            if rel in EXCLUDE:
                continue
            info = zipfile.ZipInfo(rel, date_time=(2026, 8, 7, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (stat.S_IMODE(path.stat().st_mode) & 0xFFFF) << 16
            dst.writestr(info, path.read_bytes())
    print(OUT)


if __name__ == "__main__":
    main()
