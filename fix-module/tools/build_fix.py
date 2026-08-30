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
def _basefix_version() -> str:
    """Read the BaseFix Hook version from its manifest instead of hardcoding it.

    The literal had already drifted once; a stale value here silently embeds an
    older Hook into the module while the standalone APK ships the new one.
    """
    import re
    manifest = ROOT.parents[1] / "base-fix" / "hook" / "resources" / "AndroidManifest.xml"
    m = re.search(r'android:versionName="([^"]+)"', manifest.read_text(encoding="utf-8"))
    if not m:
        raise SystemExit("base-fix AndroidManifest has no android:versionName")
    return m.group(1)


HOOK_APK = ROOT.parents[1] / "releases" / f"BaseFix-Hook-v{_basefix_version()}.apk"
EXCLUDE = {"fix-module.log", "fix-module.log.1", "tuning.log", "daemon.pid",
           "magic.pid", "ram-expand.boot-toggle", ".aclswap.err"}

# 只在仓库里留作参考、不该跟着装到机器上的目录。
#
# 之前这些是随包发的：装完 /data/adb/modules/coloros_port_fix 下会多出
# .presync-2.1.0/（合并前的旧脚本快照，10 万字符）和 .retired-dead-files/
# （已退役的 aclswap.ko / oplus_mm_compat.ko 等，约 1.5 MB），一个字节都用不上。
# 它们对读源码的人有价值，对装模块的人只是垃圾，所以留在仓库、剔出安装包。
EXCLUDE_PREFIXES = (
    "tools/",
    ".presync-",
    ".retired-dead-files/",
    "payload/osense/.retired-handed-back-to-stock/",
    "payload/retired/",
)


def verify_hook_payload(apk: Path) -> None:
    """Refuse to ship an LSPosed entry point absent from classes.dex.

    The module pins LSPosed to its embedded APK before zygote starts, so a
    stale embedded payload can override a newer PackageManager APK silently.
    """
    with zipfile.ZipFile(apk) as src:
        try:
            entries = src.read("assets/xposed_init").decode("utf-8-sig").splitlines()
            dex = src.read("classes.dex")
        except KeyError as exc:
            raise SystemExit(f"invalid Hook APK {apk}: missing {exc}") from exc
    missing = []
    for entry in entries:
        entry = entry.strip()
        if not entry or entry.startswith("#"):
            continue
        descriptor = ("L" + entry.replace(".", "/") + ";").encode()
        if descriptor not in dex:
            missing.append(entry)
    if missing:
        raise SystemExit("refusing stale Hook APK; xposed_init entries missing from classes.dex: "
                         + ", ".join(missing))


def main() -> None:
    # Same policy as the patcher jar below: rebuild from source when the
    # toolchain is present, but fall back to the checked-in build product
    # rather than refusing to package. Both jars are byte-reproducible, so a
    # machine without the SDK still assembles an identical module.
    sync_jar = ROOT / "bin" / "lsposed-path-sync.jar"
    try:
        subprocess.run([sys.executable, str(ROOT / "tools/build_lsposed_sync.py")],
                       check=True)
    except (subprocess.CalledProcessError, SystemExit):
        if not sync_jar.is_file():
            raise
        print(f"toolchain unavailable; keeping {sync_jar}")
    if not HOOK_APK.is_file():
        raise SystemExit(f"missing Hook APK: {HOOK_APK}")
    verify_hook_payload(HOOK_APK)
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
            if rel in EXCLUDE or rel.startswith(EXCLUDE_PREFIXES):
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
