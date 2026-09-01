#!/usr/bin/env python3
"""Package cryptoeng-hal-module into releases/CryptoengHAL-Module-v<version>.zip."""
from __future__ import annotations

import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MOD = ROOT / "cryptoeng-hal-module"

def _version() -> str:
    for line in (MOD / "module.prop").read_text(encoding="utf-8").splitlines():
        if line.startswith("version="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("module.prop has no version=")

OUT = ROOT / "releases" / f"CryptoengHAL-Module-v{_version()}.zip"
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for f in sorted(MOD.rglob("*")):
        if f.is_file():
            z.write(f, f.relative_to(MOD))
print(OUT)
