#!/usr/bin/env python3
"""Build the early-boot LSPosed path synchronizer as a dex jar."""

from pathlib import Path
import os
import subprocess
import tempfile
import zipfile


TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
SDK = Path(os.environ.get("ANDROID_SDK", "/run/media/ACLaniakea/IXUNICS/pad/tools/sdk"))
ANDROID_JAR = SDK / "platforms/android-35/android.jar"
R8_JAR = Path(os.environ.get("ACL_R8_JAR", "/run/media/ACLaniakea/IXUNICS/pad/tools/dex/r8.jar"))
SOURCE = TOOLS / "LsposedPathSync.java"
OUTPUT = ROOT / "bin/lsposed-path-sync.jar"


def main() -> None:
    if not ANDROID_JAR.is_file() or not R8_JAR.is_file():
        raise SystemExit("missing Android build toolchain")
    with tempfile.TemporaryDirectory(prefix="lsp-path-sync-") as raw:
        tmp = Path(raw)
        classes = tmp / "classes"
        dex = tmp / "dex"
        classes.mkdir()
        dex.mkdir()
        subprocess.run([
            "javac", "--release", "17", "-classpath", str(ANDROID_JAR),
            "-d", str(classes), str(SOURCE),
        ], check=True)
        subprocess.run([
            "java", "-cp", str(R8_JAR), "com.android.tools.r8.D8",
            "--lib", str(ANDROID_JAR), "--min-api", "31", "--output", str(dex),
            *[str(path) for path in sorted(classes.rglob("*.class"))],
        ], check=True)
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(OUTPUT, "w", compression=zipfile.ZIP_STORED) as dst:
            info = zipfile.ZipInfo("classes.dex", date_time=(2026, 8, 17, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o644 << 16
            info.compress_type = zipfile.ZIP_STORED
            dst.writestr(info, (dex / "classes.dex").read_bytes())
    print(OUTPUT)


if __name__ == "__main__":
    main()
