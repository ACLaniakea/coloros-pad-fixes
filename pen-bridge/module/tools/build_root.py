#!/usr/bin/env python3
"""Package the KernelSU Root module without runtime files from prior installs."""

from __future__ import annotations

import stat
import subprocess
import sys
import zipfile
from pathlib import Path


INCLUDE = (
    "README.md",
    "action.sh",
    "bin/pen-cps-gpio",
    "customize.sh",
    "module.prop",
    "payload/refresh_rate_config.tb710fu.xml",
    "post-fs-data.sh",
    "service.sh",
    "system/etc/permissions/privapp-permissions-com.aclaniakea.penhidctl.xml",
    "system/priv-app/aclpenhid/PenHidCtl.apk",
    "uninstall.sh",
)

EXTERNAL = {
    "bin/lsposed-path-sync.jar": "fix-module/module/bin/lsposed-path-sync.jar",
    "hook/PenBridge-Hook.apk": "releases/PenBridge-Hook-v1.1.25.apk",
}


def build(module_dir: Path, output: Path) -> None:
    repo = module_dir.parents[1]
    subprocess.run([
        sys.executable,
        str(repo / "fix-module/module/tools/build_lsposed_sync.py"),
    ], check=True)
    missing = [name for name in INCLUDE if not (module_dir / name).is_file()]
    missing += [name for name, source in EXTERNAL.items()
                if not (repo / source).is_file()]
    if missing:
        raise FileNotFoundError("missing module files: " + ", ".join(missing))
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in INCLUDE:
            path = module_dir / name
            info = zipfile.ZipInfo(name)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (stat.S_IMODE(path.stat().st_mode) | stat.S_IFREG) << 16
            archive.writestr(info, path.read_bytes())
        for name, source in EXTERNAL.items():
            path = repo / source
            info = zipfile.ZipInfo(name)
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = (0o644 | stat.S_IFREG) << 16
            archive.writestr(info, path.read_bytes())
    print(output)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(f"usage: {sys.argv[0]} MODULE_DIR OUTPUT_ZIP")
    build(Path(sys.argv[1]), Path(sys.argv[2]))
