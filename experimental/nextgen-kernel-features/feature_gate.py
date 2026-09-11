#!/usr/bin/env python3
"""Read-only gate for SM8650Q next-generation kernel feature experiments."""

from __future__ import annotations

import argparse
from pathlib import Path


BASELINE_REQUIRED = {
    "CONFIG_LRU_GEN": "already provided by the 6.1 baseline; measure before tuning",
    "CONFIG_DAMON": "required for the pressure-driven DAMON experiment",
    "CONFIG_ZSMALLOC": "required by the existing compressed-memory path",
}

TARGET_CONFIG = {
    "CONFIG_DAMON_PADDR": "will be enabled only in the isolated DAMON build",
    "CONFIG_DAMON_RECLAIM": "will be enabled only in the isolated DAMON build",
    "CONFIG_DAMON_SYSFS": "required to configure the experiment without an extra daemon",
}

FORBIDDEN = {
    "CONFIG_SCHED_CLASS_EXT": "sched_ext remains a separate boot-risk experiment",
    "CONFIG_OPLUS_HYBRIDSWAP": "hybridswap replaces zram and cannot coexist with it",
}


def parse_config(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(errors="replace").splitlines():
        if line.startswith("# CONFIG_") and line.endswith(" is not set"):
            values[line[2:-11]] = "n"
        elif line.startswith("CONFIG_") and "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path, help="decompressed /proc/config.gz")
    args = parser.parse_args()
    config = parse_config(args.config)

    print(f"config: {args.config}")
    print("\nBaseline prerequisites:")
    eligible = True
    for key, meaning in BASELINE_REQUIRED.items():
        value = config.get(key, "missing")
        ok = value in {"y", "m"}
        print(f"  {'PASS' if ok else 'BLOCK'} {key}={value} — {meaning}")
        eligible &= ok

    print("\nCandidate configuration (disabled on the production baseline is expected):")
    for key, meaning in TARGET_CONFIG.items():
        value = config.get(key, "missing")
        print(f"  INFO {key}={value} — {meaning}")

    print("\nHard exclusions:")
    for key, meaning in FORBIDDEN.items():
        value = config.get(key, "missing")
        active = value in {"y", "m"}
        print(f"  {'BLOCK' if active else 'PASS'} {key}={value} — {meaning}")
        eligible &= not active

    print("\nResult:")
    if eligible:
        print("PASS: DAMON_RECLAIM may proceed to a separate compile-and-ABI experiment.")
        return 0
    print("BLOCK: collect/adjust the baseline config before attempting any kernel change.")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
