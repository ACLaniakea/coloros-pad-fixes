#!/usr/bin/env python3
"""Patch the resident OVoice model path in the prebuilt merged dex.

The project intentionally keeps the LSPosed APK separate from the KernelSU
module.  There is no Android dex compiler in this workspace, so this narrow
patch preserves the existing verified dex and redirects the resident model
path to the app-readable native UIM staged by the KernelSU module. The
one-shot Qualcomm probe remains conditional on its explicit arm file.
"""

from __future__ import annotations

import argparse
import hashlib
import struct
import zlib
from pathlib import Path


TARGET_PREFIX = "Lcom/aclaniakea/colorosvoicewakeupbridge/ColorOSVoiceWakeupBridge$"
MODEL_PATH = "/data/user/0/com.oplus.ovoicemanager.wakeup/files/codex-qcom-oppo21001.bin"
VENDOR_MODEL_SOURCE_PATH = "/vendor/etc/models/vui/sm8_gr3UsMFCN230612eAIv34ENPUv4Float.uim"
VENDOR_MODEL_TARGET_PATH = "/data/user/0/com.oplus.ovoicemanager.wakeup/files/lenovo.uim"
QCOM_PROBE_METHOD = "isQcomProbeArmed"


def _uleb(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
        shift += 7


def _string(data: bytes, offset: int) -> str:
    _, start = _uleb(data, offset)
    return data[start : data.index(b"\0", start)].decode("utf-8", "replace")


def _replace_string(data: bytearray, old: str, new: str) -> None:
    """Replace one existing DEX string without changing string-pool offsets."""
    old_bytes = old.encode("utf-8")
    new_bytes = new.encode("utf-8")
    if len(new_bytes) > len(old_bytes):
        raise ValueError(f"replacement string is longer than source: {old}")

    string_count = struct.unpack_from("<I", data, 56)[0]
    string_offsets = struct.unpack_from("<I", data, 60)[0]
    matches = 0
    for index in range(string_count):
        string_data_off = struct.unpack_from("<I", data, string_offsets + index * 4)[0]
        encoded_length, start = _uleb(data, string_data_off)
        end = data.index(0, start)
        if encoded_length != len(old_bytes) or bytes(data[start:end]) != old_bytes:
            continue
        if encoded_length >= 0x80:
            raise ValueError("source string length encoding is not one byte")
        data[string_data_off] = len(new_bytes)
        data[start:end] = new_bytes + b"\0" * (len(old_bytes) - len(new_bytes))
        matches += 1

    if matches != 1:
        raise ValueError(f"expected one vendor model path string, found {matches}")


def _signed16(value: int) -> int:
    return value - 0x10000 if value & 0x8000 else value


def _instruction_width(opcode: int) -> int:
    # The model hook uses only ordinary Dalvik instructions.  Keeping the
    # complete width table here makes method scanning independent of offsets.
    widths = {
        0x00: 1,
        0x01: 1,
        0x02: 2,
        0x03: 3,
        0x04: 1,
        0x05: 2,
        0x06: 3,
        0x07: 1,
        0x08: 2,
        0x09: 3,
        0x0A: 1,
        0x0B: 1,
        0x0C: 1,
        0x0D: 1,
        0x0E: 1,
        0x0F: 1,
        0x10: 1,
        0x11: 1,
        0x12: 1,
        0x13: 2,
        0x14: 3,
        0x15: 2,
        0x16: 2,
        0x17: 3,
        0x18: 5,
        0x19: 2,
        0x1A: 2,
        0x1B: 3,
        0x1C: 2,
        0x1D: 2,
        0x1E: 1,
        0x1F: 2,
        0x20: 2,
        0x21: 1,
        0x22: 2,
        0x23: 2,
        0x24: 3,
        0x25: 3,
        0x26: 3,
        0x27: 1,
        0x28: 1,
        0x29: 2,
        0x2A: 3,
        0x2B: 3,
        0x2C: 3,
        0x2D: 2,
        0x2E: 2,
        0x2F: 2,
        0x30: 2,
        0x31: 2,
        0x32: 2,
        0x33: 2,
        0x34: 2,
        0x35: 2,
        0x36: 2,
        0x37: 2,
        0x38: 2,
        0x39: 2,
        0x3A: 2,
        0x3B: 2,
        0x3C: 2,
        0x3D: 2,
        0x3E: 1,
        0x3F: 1,
        0x40: 1,
        0x41: 1,
        0x42: 1,
        0x43: 1,
        0x44: 2,
        0x45: 2,
        0x46: 2,
        0x47: 2,
        0x48: 2,
        0x49: 2,
        0x4A: 2,
        0x4B: 2,
        0x4C: 2,
        0x4D: 2,
        0x4E: 2,
        0x4F: 2,
        0x50: 2,
        0x51: 2,
        0x52: 2,
        0x53: 2,
        0x54: 2,
        0x55: 2,
        0x56: 2,
        0x57: 2,
        0x58: 2,
        0x59: 2,
        0x5A: 2,
        0x5B: 2,
        0x5C: 2,
        0x5D: 2,
        0x5E: 2,
        0x5F: 2,
        0x60: 2,
        0x61: 2,
        0x62: 2,
        0x63: 2,
        0x64: 2,
        0x65: 2,
        0x66: 2,
        0x67: 2,
        0x68: 2,
        0x69: 2,
        0x6A: 2,
        0x6B: 2,
        0x6C: 2,
        0x6D: 2,
        0x6E: 3,
        0x6F: 3,
        0x70: 3,
        0x71: 3,
        0x72: 3,
        0x74: 3,
        0x75: 3,
        0x76: 3,
        0x77: 3,
        0x78: 3,
    }
    try:
        return widths[opcode]
    except KeyError as exc:
        raise ValueError(f"unsupported opcode while scanning dex: 0x{opcode:02x}") from exc


def _dex_tables(data: bytes):
    if data[:4] != b"dex\n" or data[32:36] != struct.pack("<I", len(data)):
        raise ValueError("not a valid, self-sized dex file")
    u32 = lambda offset: struct.unpack_from("<I", data, offset)[0]
    strings_size, strings_off = u32(56), u32(60)
    strings = [_string(data, u32(strings_off + index * 4)) for index in range(strings_size)]
    types_size, types_off = u32(64), u32(68)
    types = [strings[u32(types_off + index * 4)] for index in range(types_size)]
    methods_size, methods_off = u32(88), u32(92)
    methods = []
    for index in range(methods_size):
        class_idx, proto_idx, name_idx = struct.unpack_from(
            "<HHI", data, methods_off + index * 8
        )
        methods.append((types[class_idx], strings[name_idx], proto_idx))
    return u32, strings, types, methods


def _method_code_items(data: bytes, u32, types, methods):
    class_defs_size, class_defs_off = u32(96), u32(100)
    for class_index in range(class_defs_size):
        class_idx = u32(class_defs_off + class_index * 32)
        class_name = types[class_idx]
        class_data_off = u32(class_defs_off + class_index * 32 + 24)
        if not class_data_off:
            continue
        cursor = class_data_off
        static_count, cursor = _uleb(data, cursor)
        instance_count, cursor = _uleb(data, cursor)
        direct_count, cursor = _uleb(data, cursor)
        virtual_count, cursor = _uleb(data, cursor)
        for _ in range(static_count + instance_count):
            _, cursor = _uleb(data, cursor)
            _, cursor = _uleb(data, cursor)
        for count, kind in ((direct_count, "direct"), (virtual_count, "virtual")):
            method_index = 0
            for _ in range(count):
                delta, cursor = _uleb(data, cursor)
                method_index += delta
                _, cursor = _uleb(data, cursor)
                code_off, cursor = _uleb(data, cursor)
                yield class_name, methods[method_index], code_off, kind


def _patch_code(data: bytearray, code_off: int, strings, methods) -> bool:
    registers, ins, outs, tries, debug_info, instruction_count = struct.unpack_from(
        "<HHHHII", data, code_off
    )
    base = code_off + 16
    index = 0
    has_model_path = False
    instructions = []
    while index < instruction_count:
        word = struct.unpack_from("<H", data, base + index * 2)[0]
        opcode = word & 0xFF
        width = _instruction_width(opcode)
        if index + width > instruction_count:
            raise ValueError("dex instruction exceeds code item")
        instructions.append((index, opcode, width))
        if opcode == 0x1A:
            string_index = struct.unpack_from("<H", data, base + (index + 1) * 2)[0]
            if strings[string_index] == MODEL_PATH:
                has_model_path = True
        elif opcode == 0x1B:
            string_index = struct.unpack_from("<I", data, base + (index + 1) * 2)[0]
            if strings[string_index] == MODEL_PATH:
                has_model_path = True
        index += width
    if not has_model_path:
        return False

    for position, opcode, width in instructions:
        if opcode != 0x71 or position + 3 >= instruction_count:
            continue
        method_index = struct.unpack_from("<H", data, base + (position + 1) * 2)[0]
        if methods[method_index][1] != QCOM_PROBE_METHOD:
            continue
        move_result = struct.unpack_from("<H", data, base + (position + 3) * 2)[0]
        branch_position = position + 4
        branch = struct.unpack_from("<H", data, base + branch_position * 2)[0]
        if move_result & 0xFF != 0x0A or branch & 0xFF not in (0x29, 0x38, 0x39):
            continue
        target = branch_position + _signed16(
            struct.unpack_from("<H", data, base + (branch_position + 1) * 2)[0]
        )
        target_word = struct.unpack_from("<H", data, base + target * 2)[0]
        target_next = struct.unpack_from("<H", data, base + (target + 2) * 2)[0]
        target_string = None
        if target_next & 0xFF == 0x1A:
            target_string = strings[struct.unpack_from("<H", data, base + (target + 3) * 2)[0]]
        if target_word & 0xFF != 0x22 or target_string != MODEL_PATH:
            raise ValueError("QCOM probe branch does not target the packaged model block")
        # Normalize old builds that changed this gate to goto/16 (0x29), or
        # inverted it to if-eqz (0x38).  The QCOM block must be reached only
        # when isQcomProbeArmed() is true; otherwise execution falls through
        # to the app-readable native Lenovo UIM path.
        if branch & 0xFF in (0x29, 0x38):
            struct.pack_into("<H", data, base + branch_position * 2, 0x0039)
        return True
    raise ValueError("QCOM model branch was not found")


def patch_dex(data: bytes) -> bytes:
    patched = bytearray(data)
    _replace_string(patched, VENDOR_MODEL_SOURCE_PATH, VENDOR_MODEL_TARGET_PATH)
    u32, strings, types, methods = _dex_tables(patched)
    matches = 0
    for class_name, method, code_off, _ in _method_code_items(data, u32, types, methods):
        if not (class_name.startswith(TARGET_PREFIX) and method[1] == "afterHookedMethod"):
            continue
        if _patch_code(patched, code_off, strings, methods):
            matches += 1
    if matches != 1:
        raise ValueError(f"expected one resident QCOM model branch, found {matches}")
    patched[12:32] = hashlib.sha1(patched[32:]).digest()
    struct.pack_into("<I", patched, 8, zlib.adler32(patched[12:]) & 0xFFFFFFFF)
    return bytes(patched)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path, nargs="?")
    args = parser.parse_args()
    output = args.output or args.input
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(patch_dex(args.input.read_bytes()))
    print(output)


if __name__ == "__main__":
    main()
