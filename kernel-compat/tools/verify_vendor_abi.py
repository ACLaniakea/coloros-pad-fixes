#!/usr/bin/env python3
"""Check that a candidate kernel can still load this device's vendor modules.

The tablet boots a GKI Image plus 289 Lenovo/QTI vendor modules -- touch, pen,
CPS wireless charging, keyboard, wifi, display. Every one of them carries
MODVERSIONS CRCs for the symbols it imports, so replacing the kernel breaks all
of them unless the new build exports byte-identical CRCs.

That contract is checkable offline, before anything is flashed:

    verify_vendor_abi.py --modules-dir <pulled /vendor/lib/modules> \\
                         --symvers <candidate Module.symvers>

Symbols the candidate kernel does not export at all are reported separately and
are not failures by themselves: vendor modules export to each other (mhi_*,
icnss_*, qcom_glink_*, lenovo_kb_analyze and friends), and those come from the
vendor tree rather than the kernel. The failure condition is a symbol the
candidate *does* export with a different CRC -- that module will refuse to load.

Measured against the stock Lenovo GKI (ab13606743): 3615 required pairs,
1293 inter-module, 0 CRC mismatches. Any replacement must reach 0 as well.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

VERSION_LINE = re.compile(r"^(?:0x)?([0-9a-fA-F]+)\s+(\S+)$")


def normalize(crc: str) -> str:
    """CRCs print with and without 0x and with leading zeroes; compare bare."""
    return crc.strip().lower().removeprefix("0x").lstrip("0") or "0"


def module_requirements(path: Path) -> list[tuple[str, str]]:
    try:
        out = subprocess.run(
            ["modprobe", "--dump-modversions", str(path)],
            capture_output=True, text=True, check=False,
        ).stdout
    except FileNotFoundError:
        sys.exit("modprobe is required to read module CRCs; install kmod")
    pairs = []
    for line in out.splitlines():
        matched = VERSION_LINE.match(line.strip())
        if matched:
            pairs.append((matched.group(2), normalize(matched.group(1))))
    return pairs


def exported_symbols(symvers: Path) -> dict[str, str]:
    exported = {}
    for line in symvers.read_text(errors="replace").splitlines():
        fields = line.split("\t")
        if len(fields) >= 2:
            exported[fields[1].strip()] = normalize(fields[0])
    return exported


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--modules-dir", required=True, type=Path,
                        help="directory of .ko files pulled from /vendor/lib/modules")
    parser.add_argument("--symvers", required=True, type=Path,
                        help="Module.symvers of the candidate kernel")
    parser.add_argument("--show", type=int, default=15,
                        help="how many examples to print per category")
    args = parser.parse_args()

    modules = sorted(args.modules_dir.rglob("*.ko"))
    if not modules:
        sys.exit(f"no .ko files under {args.modules_dir}")
    exported = exported_symbols(args.symvers)
    if not exported:
        sys.exit(f"no exported symbols parsed from {args.symvers}")

    required: dict[tuple[str, str], list[str]] = {}
    for module in modules:
        for symbol, crc in module_requirements(module):
            required.setdefault((symbol, crc), []).append(module.name)

    mismatched = []
    inter_module = set()
    for (symbol, crc), users in sorted(required.items()):
        if symbol not in exported:
            inter_module.add(symbol)
        elif exported[symbol] != crc:
            mismatched.append((symbol, crc, exported[symbol], users))

    print(f"modules            : {len(modules)}")
    print(f"required (sym,crc) : {len(required)}")
    print(f"kernel-provided    : {len(required) - len(inter_module) - len(mismatched)}")
    print(f"inter-module       : {len(inter_module)}  (expected; supplied by the vendor tree)")
    print(f"CRC mismatches     : {len(mismatched)}")

    for symbol in sorted(inter_module)[:args.show]:
        print(f"  not exported : {symbol}")
    for symbol, want, got, users in mismatched[:args.show]:
        print(f"  MISMATCH     : {symbol} module wants {want}, kernel exports {got}"
              f"  [{', '.join(sorted(set(users))[:3])}]")

    if mismatched:
        print("\nFAIL: these vendor modules would refuse to load on this kernel.")
        return 1
    print("\nPASS: every kernel-provided symbol matches; vendor modules would still load.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
