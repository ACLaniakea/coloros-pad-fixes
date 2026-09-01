#!/usr/bin/env python3
"""Patch only the fixed-width AIDL instance name in the shared HAL binary."""

import argparse
from pathlib import Path

OLD = b"vendor.oplus.hardware.cryptoeng.ICryptoeng/default"
NEW = b"vendor.oplus.hardware.cryptoeng.ICryptoeng/backing"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()

    data = args.source.read_bytes()
    count = data.count(OLD)
    if count != 1:
        raise SystemExit(f"expected exactly one service-name occurrence, found {count}")
    if len(OLD) != len(NEW):
        raise SystemExit("replacement must preserve binary layout")
    args.destination.write_bytes(data.replace(OLD, NEW, 1))
    args.destination.chmod(0o755)


if __name__ == "__main__":
    main()
