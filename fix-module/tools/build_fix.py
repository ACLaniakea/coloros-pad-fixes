#!/usr/bin/env python3
"""Build the merged ColorOS port fix module (base-fix + tuning, ACLaniakea)."""

from __future__ import annotations

import stat
import subprocess
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1] / "module"
OUT = ROOT.parents[1] / "releases" / "FixModule-v1.2.4.zip"
EXCLUDE = {"fix-module.log", "tuning.log", "daemon.pid", "magic.pid"}


def main() -> None:
    patcher = ROOT / "tools" / "build_patcher.py"
    jar = ROOT / "bin" / "card-protocol-patcher.jar"
    if patcher.is_file():
        try:
            subprocess.run([sys.executable, str(patcher)], check=True)
        except (subprocess.CalledProcessError, SystemExit):
            if not jar.is_file():
                raise
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUT, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
        for path in sorted(p for p in ROOT.rglob("*") if p.is_file()):
            rel = path.relative_to(ROOT).as_posix()
            if rel in EXCLUDE or rel.startswith("tools/"):
                continue
            if path.suffix.lower() == ".apk":
                continue
            info = zipfile.ZipInfo(rel, date_time=(2026, 8, 17, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (stat.S_IMODE(path.stat().st_mode) & 0xFFFF) << 16
            info.compress_type = (
                zipfile.ZIP_STORED
                if path.suffix in {".apk", ".jar", ".so", ".bin", ".uim", ".zip"}
                else zipfile.ZIP_DEFLATED
            )
            dst.writestr(info, path.read_bytes())
    print(OUT)


if __name__ == "__main__":
    main()
