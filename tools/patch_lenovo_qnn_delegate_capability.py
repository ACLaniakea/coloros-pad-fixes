#!/usr/bin/env python3
"""Enable V75 HTP capability on Lenovo's QNN 2.21 TFLite delegate.

The TB710FU delegate rejects the transplanted product identity as an unknown
platform before it talks to the otherwise matching Lenovo CDSP stack.  Keep
the complete Lenovo delegate/backend path and bypass only that product gate.
"""

from hashlib import sha256
from pathlib import Path
import argparse


EXPECTED_SHA256 = "787e126283200f915599d77e99d735877e927cb0585a9e3c8ecf4a12096215f6"
CAPABILITY_OFFSET = 0x81030
EXPECTED_PROLOGUE = bytes.fromhex("fd7bbea9f44f01a9")
RETURN_TRUE = bytes.fromhex("20008052c0035fd6")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    image = bytearray(args.source.read_bytes())
    digest = sha256(image).hexdigest()
    if digest != EXPECTED_SHA256:
        raise SystemExit(f"unexpected Lenovo delegate sha256: {digest}")
    found = bytes(image[CAPABILITY_OFFSET:CAPABILITY_OFFSET + len(EXPECTED_PROLOGUE)])
    if found != EXPECTED_PROLOGUE:
        raise SystemExit(f"unexpected capability prologue: {found.hex()}")
    image[CAPABILITY_OFFSET:CAPABILITY_OFFSET + len(RETURN_TRUE)] = RETURN_TRUE
    args.output.write_bytes(image)
    print(f"patched sha256={sha256(image).hexdigest()}")


if __name__ == "__main__":
    main()
