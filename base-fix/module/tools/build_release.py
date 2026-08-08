#!/usr/bin/env python3
"""Build the native/systemless repair module without the LSPosed APK."""

from pathlib import Path
import stat
import zipfile


ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT.parents[1] / "releases" / "BaseFix-Module-v1.0.1.zip"
EXCLUDE: set[str] = set()


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUT, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
        for path in sorted(p for p in ROOT.rglob("*") if p.is_file()):
            rel = path.relative_to(ROOT).as_posix()
            if rel in EXCLUDE or rel.startswith("tools/") or path.suffix.lower() == ".apk":
                continue
            info = zipfile.ZipInfo(rel, date_time=(2026, 8, 6, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (stat.S_IMODE(path.stat().st_mode) & 0xFFFF) << 16
            info.compress_type = zipfile.ZIP_STORED if path.suffix in {".apk", ".jar", ".so", ".bin", ".uim", ".zip"} else zipfile.ZIP_DEFLATED
            dst.writestr(info, path.read_bytes())
    print(OUT)


if __name__ == "__main__":
    main()
