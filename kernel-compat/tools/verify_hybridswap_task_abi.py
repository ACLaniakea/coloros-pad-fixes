#!/usr/bin/env python3
"""Reject HybridSwap objects whose task_struct ABI differs from tablet r2.

The kernel accepts an external module when vermagic and symbol CRCs match, but
those checks do not encode private struct offsets.  A SYSVIPC layout mismatch
therefore passed modpost and later made force_shrink_batch() dereference a bad
current->signal pointer.  Check the actual DWARF layout and generated code
before a rebuilt HybridSwap module is ever copied to a device.
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path


EXPECTED_MEMBERS = {
    "files": 2168,
    "io_uring": 2176,
    "nsproxy": 2184,
    "signal": 2192,
    "pending": 2232,
}
EXPECTED_INSTRUCTIONS = ("#0x8c9", "#0x890", "#0x51")


def run(*args: str) -> str:
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("artifact", type=Path, help="hybridmain.o or final .ko")
    args = parser.parse_args()

    try:
        layout = run("pahole", "-C", "task_struct", str(args.artifact))
        disassembly = run("llvm-objdump", "-d", "--no-show-raw-insn", str(args.artifact))
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"FAIL {args.artifact}: cannot inspect artifact: {error}", file=sys.stderr)
        return 1

    failed = False
    if "sysvsem;" in layout or "sysvshm;" in layout:
        print("FAIL: SYSVIPC was inlined into task_struct", file=sys.stderr)
        failed = True
    for member, expected in EXPECTED_MEMBERS.items():
        match = re.search(rf"\b{re.escape(member)};\s*/\*\s+(\d+)\s+", layout)
        actual = int(match.group(1)) if match else None
        if actual != expected:
            print(f"FAIL: task_struct.{member}={actual}, expected {expected}", file=sys.stderr)
            failed = True

    function = re.search(
        r"<force_shrink_batch>:(.*?)(?=\n[0-9a-f]+ <|\Z)", disassembly, re.S
    )
    body = function.group(1) if function else ""
    for operand in EXPECTED_INSTRUCTIONS:
        if operand not in body:
            print(f"FAIL: force_shrink_batch missing {operand}", file=sys.stderr)
            failed = True

    if failed:
        return 1
    print(f"PASS {args.artifact}: task_struct and force_shrink_batch match tablet r2 ABI")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
