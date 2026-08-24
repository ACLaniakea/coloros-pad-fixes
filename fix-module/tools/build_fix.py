#!/usr/bin/env python3
"""Build the merged ColorOS port fix module (base-fix + tuning, ACLaniakea)."""

from __future__ import annotations

import stat
import subprocess
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1] / "module"
def _module_version(module_dir: Path) -> str:
    """Read version= from module.prop so the artifact name cannot drift."""
    for line in (module_dir / "module.prop").read_text(encoding="utf-8").splitlines():
        if line.startswith("version="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("module.prop has no version=")


OUT = ROOT.parents[1] / "releases" / f"FixModule-v{_module_version(ROOT)}.zip"
HOOK_APK = ROOT.parents[1] / "releases" / "BaseFix-Hook-v1.1.12.apk"
EXCLUDE = {"fix-module.log", "tuning.log", "daemon.pid", "magic.pid"}


def main() -> None:
    subprocess.run([sys.executable, str(ROOT / "tools/build_lsposed_sync.py")], check=True)
    if not HOOK_APK.is_file():
        raise SystemExit(f"missing Hook APK: {HOOK_APK}")
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
            mode = 0o644 if path.suffix == ".ko" else stat.S_IMODE(path.stat().st_mode)
            info.external_attr = (mode & 0xFFFF) << 16
            info.compress_type = (
                zipfile.ZIP_STORED
                if path.suffix in {".apk", ".jar", ".so", ".bin", ".uim", ".zip"}
                else zipfile.ZIP_DEFLATED
            )
            dst.writestr(info, path.read_bytes())
        hook_info = zipfile.ZipInfo("hook/BaseFix-Hook.apk", date_time=(2026, 8, 18, 0, 0, 0))
        hook_info.create_system = 3
        hook_info.external_attr = 0o644 << 16
        hook_info.compress_type = zipfile.ZIP_STORED
        dst.writestr(hook_info, HOOK_APK.read_bytes())
    print(OUT)


if __name__ == "__main__":
    main()
