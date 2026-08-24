#!/usr/bin/env python3
"""Build the independent SM8650Q Scene/UPerf-compatible scheduler module."""

from __future__ import annotations

import stat
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1] / "module"
EXCLUDE = {"scheduler.log"}


def _module_version(module_dir: Path) -> str:
    """Read version= from module.prop so the artifact name cannot drift.

    It used to be a literal in this file, which meant every version bump had to
    be made in three places (module.prop, powercfg.json, here) and a missed one
    silently produced a zip named after the previous release.
    """
    for line in (module_dir / "module.prop").read_text(encoding="utf-8").splitlines():
        if line.startswith("version="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("module.prop has no version=")


OUT = (ROOT.parents[1] / "releases"
       / f"SM8650Q-Scene-Scheduler-v{_module_version(ROOT)}.zip")


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUT, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
        for path in sorted(p for p in ROOT.rglob("*") if p.is_file()):
            rel = path.relative_to(ROOT).as_posix()
            if rel in EXCLUDE or rel.startswith("state/"):
                continue
            info = zipfile.ZipInfo(rel, date_time=(2026, 8, 24, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (stat.S_IMODE(path.stat().st_mode) & 0xFFFF) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            dst.writestr(info, path.read_bytes())
    print(OUT)


if __name__ == "__main__":
    main()
