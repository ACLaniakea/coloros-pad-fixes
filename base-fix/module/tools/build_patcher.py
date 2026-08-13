#!/usr/bin/env python3
"""Build the app-suggestion CardProtocolPatcher jar from smali source.

Reproduces base-fix/module/bin/card-protocol-patcher.jar so a fresh clone can
build the module without any precompiled jar:

    smali.jar a tools/smali -> classes.dex
    zip -> bin/card-protocol-patcher.jar

The smali source is the same dex previously shipped as a prebuilt artifact
(com.aclaniakea.oplusappsuggestionfix.CardProtocolPatcher, which patches the
ColorOS card_configs protocol to 2 for the Breeno card widget).

Requires a smali assembler jar (https://bitbucket.org/JesusFreke/smali/downloads/),
pointed to by SMALI_JAR (default /tmp/codex-dex-tools/smali.jar).
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
SMALI_SRC = ROOT / "smali"
OUT = ROOT.parent / "bin" / "card-protocol-patcher.jar"
SMALI_DEFAULT = Path(os.environ.get("SMALI_JAR", "/tmp/codex-dex-tools/smali.jar"))
SMALI_FALLBACK = Path("/tmp/smali.jar")


def find_smali() -> Path:
    for cand in (SMALI_DEFAULT, SMALI_FALLBACK):
        if cand.is_file():
            return cand
    raise SystemExit(
        f"smali.jar not found (checked {SMALI_DEFAULT}, {SMALI_FALLBACK}); "
        "set SMALI_JAR to the smali assembler jar"
    )


def main() -> None:
    smali = find_smali()
    if not SMALI_SRC.is_dir():
        raise SystemExit(f"smali source missing: {SMALI_SRC}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="patcher-") as td:
        dex = Path(td) / "classes.dex"
        subprocess.run(
            ["java", "-jar", str(smali), "a", str(SMALI_SRC), "-o", str(dex)],
            check=True,
        )
        with zipfile.ZipFile(OUT, "w", compression=zipfile.ZIP_DEFLATED) as dst:
            info = zipfile.ZipInfo("classes.dex", date_time=(2026, 8, 6, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            dst.writestr(info, dex.read_bytes())
    print(OUT)


if __name__ == "__main__":
    main()
