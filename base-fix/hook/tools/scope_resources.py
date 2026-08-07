"""Patch the compiled Xposed scope array in an Android resources.arsc file.

The LSPosed manager reads the ``xposedscope`` manifest metadata from the
compiled resource table.  Updating only ``META-INF/xposed/scope.list`` does
not change that UI recommendation, so the integrated APK builder uses this
small resource-table patcher as well.
"""

from __future__ import annotations

import struct
from typing import Iterable, List, Tuple


RES_TABLE = 0x0002
RES_STRING_POOL = 0x0001
RES_TABLE_PACKAGE = 0x0200
RES_TABLE_TYPE = 0x0201
TYPE_STRING = 0x03
NO_ENTRY = 0xFFFFFFFF
UTF8_FLAG = 0x00000100


def _read_length(data: bytes, offset: int) -> Tuple[int, int]:
    first = data[offset]
    if first & 0x80:
        return ((first & 0x7F) << 7) | (data[offset + 1] & 0x7F), 2
    return first, 1


def _write_length(value: int) -> bytes:
    if value < 0x80:
        return bytes((value,))
    if value >= 0x4000:
        raise ValueError(f"string-pool length is too large: {value}")
    return bytes(((value >> 7) | 0x80, value & 0x7F))


def _decode_string_pool(data: bytes, chunk_offset: int) -> Tuple[List[str], int, int]:
    chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", data, chunk_offset)
    if chunk_type != RES_STRING_POOL or header_size != 28:
        raise ValueError("resources.arsc does not start with a standard string pool")
    count, style_count, flags, strings_start, styles_start = struct.unpack_from(
        "<IIIII", data, chunk_offset + 8
    )
    if style_count or styles_start:
        raise ValueError("styled global string pools are not supported by this patcher")
    offsets = [
        struct.unpack_from("<I", data, chunk_offset + 28 + index * 4)[0]
        for index in range(count)
    ]
    strings: List[str] = []
    base = chunk_offset + strings_start
    for relative in offsets:
        offset = base + relative
        if flags & UTF8_FLAG:
            _, first_size = _read_length(data, offset)
            byte_length, second_size = _read_length(data, offset + first_size)
            start = offset + first_size + second_size
            strings.append(data[start : start + byte_length].decode("utf-8"))
        else:
            char_length = struct.unpack_from("<H", data, offset)[0]
            start = offset + 2
            strings.append(data[start : start + char_length * 2].decode("utf-16le"))
    return strings, flags, chunk_size


def _encode_utf8_string(value: str) -> bytes:
    raw = value.encode("utf-8")
    utf16_length = len(value.encode("utf-16le")) // 2
    return _write_length(utf16_length) + _write_length(len(raw)) + raw + b"\0"


def _build_string_pool(strings: Iterable[str], flags: int) -> bytes:
    values = list(strings)
    if not (flags & UTF8_FLAG):
        raise ValueError("only UTF-8 string pools are supported by this patcher")
    string_data = b"".join(_encode_utf8_string(value) for value in values)
    strings_start = 28 + len(values) * 4
    offsets: List[int] = []
    cursor = 0
    for value in values:
        offsets.append(cursor)
        cursor += len(_encode_utf8_string(value))
    body = struct.pack("<IIIII", len(values), 0, flags, strings_start, 0)
    body += b"".join(struct.pack("<I", offset) for offset in offsets)
    body += string_data
    body += b"\0" * ((-len(body)) & 3)
    return struct.pack("<HHI", RES_STRING_POOL, 28, len(body) + 8) + body


def _patch_scope_type_chunk(chunk: bytes, scope_indexes: List[int]) -> bytes:
    chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", chunk, 0)
    if chunk_type != RES_TABLE_TYPE or header_size < 20:
        raise ValueError("scope resource type chunk is malformed")
    type_id, _, _, entry_count, entries_start = struct.unpack_from("<BBHII", chunk, 8)
    if type_id != 2 or entry_count < 1:
        raise ValueError("xposed_scope is not the first array resource")
    entry_offset = struct.unpack_from("<I", chunk, header_size)[0]
    if entry_offset == NO_ENTRY:
        raise ValueError("xposed_scope has no compiled entry")
    entry_start = entries_start + entry_offset
    entry_size, entry_flags = struct.unpack_from("<HH", chunk, entry_start)
    if not (entry_flags & 0x0001) or entry_size < 16:
        raise ValueError("xposed_scope is not a complex array resource")
    old_count = struct.unpack_from("<I", chunk, entry_start + 12)[0]
    old_end = entry_start + 16 + old_count * 12
    if old_end > len(chunk):
        raise ValueError("xposed_scope map extends beyond its type chunk")

    entry = bytearray(chunk[entry_start : entry_start + 16])
    struct.pack_into("<I", entry, 12, len(scope_indexes))
    maps = bytearray()
    for index, string_index in enumerate(scope_indexes):
        maps += struct.pack("<I", 0x01000000 + index)
        maps += struct.pack("<HBBI", 8, 0, TYPE_STRING, string_index)

    patched = bytearray(chunk[:entry_start] + entry + maps + chunk[old_end:])
    struct.pack_into("<I", patched, 4, len(patched))
    return bytes(patched)


def patch_scope_resources(resources: bytes, scopes: Iterable[str]) -> bytes:
    """Return ``resources.arsc`` with ``@array/xposed_scope`` replaced."""

    table_type, table_header_size, table_size = struct.unpack_from("<HHI", resources, 0)
    if table_type != RES_TABLE or table_header_size != 12 or table_size > len(resources):
        raise ValueError("invalid resources.arsc table header")
    pool_offset = table_header_size
    strings, flags, pool_size = _decode_string_pool(resources, pool_offset)
    desired = [scope.strip().lstrip("\ufeff") for scope in scopes if scope.strip()]
    if not desired:
        raise ValueError("scope list is empty")
    for scope in desired:
        if scope not in strings:
            strings.append(scope)
    scope_indexes = [strings.index(scope) for scope in desired]
    new_pool = _build_string_pool(strings, flags)

    package_offset = pool_offset + pool_size
    package_type, package_header_size, package_size = struct.unpack_from("<HHI", resources, package_offset)
    if package_type != RES_TABLE_PACKAGE or package_size != table_size - package_offset:
        raise ValueError("unexpected resources.arsc package layout")
    package = bytearray(resources[package_offset : package_offset + package_size])

    cursor = package_header_size
    patched_type = False
    while cursor + 8 <= len(package):
        child_type, child_header_size, child_size = struct.unpack_from("<HHI", package, cursor)
        if child_size < child_header_size or cursor + child_size > len(package):
            raise ValueError("malformed package child chunk")
        if child_type == RES_TABLE_TYPE:
            type_id = package[cursor + 8]
            if type_id == 2:
                old_chunk = bytes(package[cursor : cursor + child_size])
                new_chunk = _patch_scope_type_chunk(old_chunk, scope_indexes)
                package = package[:cursor] + new_chunk + package[cursor + child_size :]
                patched_type = True
                break
        cursor += child_size
    if not patched_type:
        raise ValueError("xposed_scope array type chunk was not found")
    struct.pack_into("<I", package, 4, len(package))

    patched = bytearray(resources[:pool_offset] + new_pool + package)
    struct.pack_into("<I", patched, 4, len(patched))
    return bytes(patched)

