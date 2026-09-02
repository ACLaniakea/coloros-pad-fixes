#!/usr/bin/env python3
"""Produce a guarded AON delegate variant without an empty ADSP path entry.

The recovered ColorOS delegate calls ``setenv("ADSP_LIBRARY_PATH", value, 1)``
with a value beginning with a separator.  On the TB710FU port that creates an
empty first loader search entry immediately before QNN delegate creation.  This
tool changes only that leading byte to ``/`` (yielding an equivalent absolute
``//my_product/...`` path), and refuses every unexpected input binary.

It is intentionally an experiment artifact: it does not install anything and
the caller must use a bind mount / copied staging file to test it.
"""

from __future__ import annotations

import argparse
from hashlib import sha256
from pathlib import Path


EXPECTED_SHA256 = "acfb66cb834f4edb8ad9106bb5d6c47ca4518e1d4630aa3283af2e0e733e7c5d"
PATH_OFFSET = 0xF3138
EXPECTED_PATH = (
    b";/my_product/app/AONService/lib/arm64/cdsp/unsigned;"
    b"/vendor/lib64/rfs/dsp;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/dsp\0"
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    image = bytearray(args.source.read_bytes())
    digest = sha256(image).hexdigest()
    if digest != EXPECTED_SHA256:
        raise SystemExit(f"unexpected delegate sha256: {digest}")
    actual = bytes(image[PATH_OFFSET : PATH_OFFSET + len(EXPECTED_PATH)])
    if actual != EXPECTED_PATH:
        raise SystemExit(f"unexpected ADSP path bytes: {actual!r}")
    image[PATH_OFFSET] = ord("/")
    args.output.write_bytes(image)
    print(f"patched sha256={sha256(image).hexdigest()}")


if __name__ == "__main__":
    main()
