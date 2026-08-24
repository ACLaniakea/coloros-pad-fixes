#!/usr/bin/env python3
"""Fail if an external module does not match the pinned tablet GKI ABI."""

import argparse
import pathlib
import subprocess
import sys


EXPECTED_VERMAGIC = (
    "6.1.128-android14-11-g5c2cea985a84-ab13606743 "
    "SMP preempt mod_unload modversions aarch64"
)


def output(*args: str) -> str:
    return subprocess.check_output(args, text=True).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("symvers", type=pathlib.Path)
    parser.add_argument("modules", nargs="+", type=pathlib.Path)
    args = parser.parse_args()

    expected_crc = {}
    for line in args.symvers.read_text().splitlines():
        fields = line.split()
        if len(fields) >= 2:
            expected_crc[fields[1]] = fields[0].lower()

    failed = False
    for module in args.modules:
        module_failed = False
        vermagic = output("modinfo", "-F", "vermagic", str(module))
        if vermagic != EXPECTED_VERMAGIC:
            print(f"FAIL {module}: vermagic={vermagic!r}", file=sys.stderr)
            failed = True
            module_failed = True

        imports = output("modprobe", "--show-modversions", str(module))
        mismatches = []
        restricted_hooks = []
        lines = imports.splitlines() if imports else []
        for line in lines:
            crc, symbol = line.split()[:2]
            if expected_crc.get(symbol) != crc.lower():
                mismatches.append((symbol, crc, expected_crc.get(symbol)))
            if "android_rvh_" in symbol:
                restricted_hooks.append(symbol)
        if mismatches:
            for symbol, actual, expected in mismatches:
                print(
                    f"FAIL {module}: {symbol} crc={actual} expected={expected}",
                    file=sys.stderr,
                )
            failed = True
            module_failed = True
        if restricted_hooks:
            print(
                f"FAIL {module}: restricted hooks imported: "
                + ", ".join(restricted_hooks),
                file=sys.stderr,
            )
            failed = True
            module_failed = True
        dependencies = output("modinfo", "-F", "depends", str(module))
        if dependencies:
            print(
                f"FAIL {module}: unexpected module dependencies={dependencies!r}",
                file=sys.stderr,
            )
            failed = True
            module_failed = True
        if not module_failed:
            print(f"PASS {module}: vermagic exact, {len(lines)} symbol CRCs exact")

    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
