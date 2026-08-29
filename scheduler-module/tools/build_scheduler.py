#!/usr/bin/env python3
"""Build the independent SM8650Q Scene/UPerf-compatible scheduler module."""

from __future__ import annotations

import json
import stat
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1] / "module"
EXCLUDE = {"scheduler.log"}


def _module_version(module_dir: Path) -> tuple[str, str]:
    """Read version=/versionCode= from module.prop so nothing can drift.

    It used to be a literal in this file, which meant every version bump had to
    be made in three places (module.prop, powercfg.json, here) and a missed one
    silently produced a zip named after the previous release.
    """
    ver = code = ""
    for line in (module_dir / "module.prop").read_text(encoding="utf-8").splitlines():
        if line.startswith("version="):
            ver = line.split("=", 1)[1].strip()
        elif line.startswith("versionCode="):
            code = line.split("=", 1)[1].strip()
    if not ver:
        raise SystemExit("module.prop has no version=")
    return ver, code


def _sync_powercfg(module_dir: Path, ver: str, code: str) -> None:
    """Force payload/powercfg.json to agree with module.prop.

    Scene reads its version from powercfg.json, NOT from module.prop, so the
    two drifting apart makes the app keep reporting a stale version even after
    a successful install -- which is exactly what happened at 1.1.0: module.prop
    said 1.1.0, powercfg.json still said 1.0.9, and Scene showed 1.0.9.
    module.prop is the single source of truth; this rewrites the JSON to match
    and says so out loud, so a missed bump can never be silent again.
    """
    cfg = module_dir / "payload" / "powercfg.json"
    if not cfg.exists():
        return
    data = json.loads(cfg.read_text(encoding="utf-8"))
    if data.get("version") == ver and str(data.get("versionCode")) == code:
        return
    print(f"  powercfg.json: {data.get('version')}/{data.get('versionCode')}"
          f" -> {ver}/{code}（Scene 显示的是这个）")
    data["version"] = ver
    if code:
        data["versionCode"] = int(code)
    cfg.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")


VERSION, VERSION_CODE = _module_version(ROOT)
_sync_powercfg(ROOT, VERSION, VERSION_CODE)

OUT = (ROOT.parents[1] / "releases"
       / f"SM8650Q-Scene-Scheduler-v{VERSION}.zip")


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
