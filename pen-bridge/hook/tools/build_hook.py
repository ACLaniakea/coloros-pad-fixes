#!/usr/bin/env python3
"""Build the standalone Lenovo pen LSPosed APK from the current APK.

The full Java source remains in ../source.  This small release builder keeps
the original Android resources/native library and applies the two safety
patches that are already represented by the source changes:

* make the two writeKernelFlag() results false so user space never writes
  vendor DSI panel proc nodes;
* remove the immediate SCREEN_ON applyPenHall() call.  The independent Root
  module replays the real Hall state after the panel settles.

The input APK is intentionally kept as an explicit argument because future
releases can replace it with a newly compiled APK without changing the Root
module.

The release input is the last known-good APK, so the DEX patch also replaces
the mistaken s0.c(Integer, String) callback lookup with the inherited
BleManager.b(String) BluetoothGatt connect entry point.  It adds the matching
real disconnect call to the CoreService action hook: HookUtils.call() invokes
the OEM BleManager.a() method, which calls BluetoothGatt.disconnect(), while
the original CoreService action continues to close the BluetoothDevice/HID
side.  The stock s0.c callback guard is left untouched: it is a UI/profile
callback, and injecting a second connect call there causes duplicate GATT
sessions and breaks the stock magnetic/charging/settings event flow.  The My
Devices detail route is kept tolerant of a delayed state mirror without
manufacturing a connected state.

The Bluetooth path is deliberately independent of Hall/CPS state. The Root
service resolves the current bonded Lenovo pen address and the Hook accepts
the real ACL/GATT events for that address; Hall remains limited to charging
and the magnetic capsule. Legacy system_server HID/OAF/ACL recovery methods
are reduced to no-ops because Root is the only bounded boot connection owner.
"""

from __future__ import annotations

import hashlib
import os
import struct
import sys
import zipfile
import zlib


BASE_APK_SHA256 = "d4c3bbae4616baea18a3663d7b8616889c5509ad085b66b63b736843fc1e8267"
KERNEL_WAKE_CODE_ITEM = 0x15488
SCREEN_ON_APPLY_CALL = 0x15180
ORIGINAL_GATT_CONNECT_METHOD = 0xB2BE
ORIGINAL_GATT_CONNECT_STRING = 0xB2C0
ORIGINAL_GATT_LOG_STRING = b"IPe original s0.c connect requested mode="
FIXED_GATT_LOG_STRING = b"IPe original s0.b connect requested mode="
MYDEVICES_DETAIL_CONNECTED_BRANCH = 0xD7A6


def _read_uleb128(data: bytes | bytearray, offset: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while True:
        if offset >= len(data):
            raise ValueError("truncated ULEB128 value")
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
        shift += 7
        if shift > 35:
            raise ValueError("invalid ULEB128 value")


def _encode_uleb128(value: int) -> bytes:
    encoded = bytearray()
    while value >= 0x80:
        encoded.append((value & 0x7F) | 0x80)
        value >>= 7
    encoded.append(value)
    return bytes(encoded)


def _dex_string_tables(dex: bytes | bytearray) -> tuple[list[str], list[str]]:
    string_count, string_off = struct.unpack_from("<II", dex, 56)
    string_offsets = [
        struct.unpack_from("<I", dex, string_off + index * 4)[0]
        for index in range(string_count)
    ]
    strings = []
    for offset in string_offsets:
        _, cursor = _read_uleb128(dex, offset)
        end = dex.index(0, cursor)
        strings.append(bytes(dex[cursor:end]).decode("utf-8"))

    type_count, type_off = struct.unpack_from("<II", dex, 64)
    types = [
        strings[struct.unpack_from("<I", dex, type_off + index * 4)[0]]
        for index in range(type_count)
    ]
    return strings, types


def _find_method_code_reference(
    dex: bytes | bytearray, class_descriptor: str, method_name: str
) -> tuple[int, int, list[int]]:
    """Return (code_off, encoded-code-off offset, all code offsets)."""
    strings, types = _dex_string_tables(dex)
    method_count, method_off = struct.unpack_from("<II", dex, 88)
    methods = []
    for index in range(method_count):
        class_index, _, name_index = struct.unpack_from(
            "<HHI", dex, method_off + index * 8
        )
        methods.append((types[class_index], strings[name_index]))

    class_count, class_off = struct.unpack_from("<II", dex, 96)
    target = None
    all_code_offsets = []
    for index in range(class_count):
        class_index = struct.unpack_from("<I", dex, class_off + index * 32)[0]
        is_target_class = types[class_index] == class_descriptor
        class_data_off = struct.unpack_from("<I", dex, class_off + index * 32 + 24)[0]
        if class_data_off == 0:
            continue
        cursor = class_data_off
        static_count, cursor = _read_uleb128(dex, cursor)
        instance_count, cursor = _read_uleb128(dex, cursor)
        direct_count, cursor = _read_uleb128(dex, cursor)
        virtual_count, cursor = _read_uleb128(dex, cursor)
        for _ in range(static_count + instance_count):
            _, cursor = _read_uleb128(dex, cursor)
            _, cursor = _read_uleb128(dex, cursor)
        for count in (direct_count, virtual_count):
            method_index = 0
            for _ in range(count):
                delta, cursor = _read_uleb128(dex, cursor)
                method_index += delta
                _, cursor = _read_uleb128(dex, cursor)
                code_ref_offset = cursor
                code_off, cursor = _read_uleb128(dex, cursor)
                if code_off:
                    all_code_offsets.append(code_off)
                if is_target_class and methods[method_index] == (class_descriptor, method_name):
                    if target is not None:
                        raise ValueError("duplicate target method code reference")
                    target = (code_off, code_ref_offset)
    if target is None:
        raise ValueError(f"DEX method not found: {class_descriptor}->{method_name}")
    return target[0], target[1], sorted(set(all_code_offsets))


def patch_void_method_to_return(
    dex: bytearray, class_descriptor: str, method_name: str
) -> None:
    """Disable a legacy void retry method without changing DEX layout."""
    code_off, _, _ = _find_method_code_reference(dex, class_descriptor, method_name)
    registers, ins, outs, tries, debug_info, insns_size = struct.unpack_from(
        "<HHHHII", dex, code_off
    )
    if insns_size < 1:
        raise ValueError(f"empty DEX method: {class_descriptor}->{method_name}")
    insns_off = code_off + 16
    # return-void followed by NOPs keeps all existing code-item offsets,
    # try tables and debug data stable while making every caller harmless.
    dex[insns_off : insns_off + insns_size * 2] = b"\x00" * (insns_size * 2)
    dex[insns_off : insns_off + 2] = bytes.fromhex("0e00")


def patch_real_gatt_disconnect(dex: bytearray) -> None:
    """Make DISCONNECT_PENCIL close the OEM BluetoothGatt session."""
    class_descriptor = "Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$3;"
    old_code_off, code_ref_offset, all_code_offsets = _find_method_code_reference(
        dex, class_descriptor, "beforeHookedMethod"
    )
    next_code_offsets = [offset for offset in all_code_offsets if offset > old_code_off]
    if not next_code_offsets:
        raise ValueError("target DEX code item has no following code item")
    next_code_off = min(next_code_offsets)

    registers, ins, outs, tries, debug_info, old_insns_size = struct.unpack_from(
        "<HHHHII", dex, old_code_off
    )
    if (registers, ins, outs, tries, debug_info, old_insns_size) != (
        5,
        2,
        3,
        2,
        0x18066,
        135,
    ):
        raise ValueError("CoreService disconnect hook code item does not match expected APK")

    strings, types = _dex_string_tables(dex)
    field_count, field_off = struct.unpack_from("<II", dex, 80)
    core_field_index = None
    for index in range(field_count):
        class_index, _, name_index = struct.unpack_from("<HHI", dex, field_off + index * 8)
        if types[class_index] == "Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;" and strings[name_index] == "coreBleManager":
            core_field_index = index
            break
    if core_field_index is None:
        raise ValueError("IpeManagerHooks.coreBleManager field not found")

    object_array_type = next(
        (index for index, descriptor in enumerate(types) if descriptor == "[Ljava/lang/Object;"),
        None,
    )
    string_a = next((index for index, value in enumerate(strings) if value == "a"), None)
    if object_array_type is None or string_a is None:
        raise ValueError("reflection helper DEX constants are missing")

    method_count, method_off = struct.unpack_from("<II", dex, 88)
    hook_utils_call = None
    haptic_disconnect = None
    for index in range(method_count):
        class_index, _, name_index = struct.unpack_from("<HHI", dex, method_off + index * 8)
        descriptor = types[class_index]
        name = strings[name_index]
        if descriptor == "Lcom/aclaniakea/colorosporttuning/HookUtils;" and name == "call":
            hook_utils_call = index
        if descriptor == "Lcom/aclaniakea/colorosporttuning/PenHapticGatt;" and name == "disconnect":
            haptic_disconnect = index
    if hook_utils_call is None or haptic_disconnect is None:
        raise ValueError("disconnect helper method references are missing")

    old_insns_off = old_code_off + 16
    old_insns = bytearray(dex[old_insns_off : old_insns_off + old_insns_size * 2])
    expected_log = struct.pack("<HHHHH", 0x031A, 0x06C4, 0x1071, 0x009D, 0x0003)
    if bytes(old_insns[129 * 2 : 134 * 2]) != expected_log:
        raise ValueError("disconnect hook log instruction block does not match expected APK")

    # Keep the original return at code-unit 134 as the target of all existing
    # early branches. Only the disconnect arm jumps over it to the appended
    # real-disconnect sequence.
    struct.pack_into("<HHHHH", old_insns, 129 * 2, 0x002A, 0x0006, 0x0000, 0x0000, 0x0000)
    appended_insns = struct.pack(
        "<HHHHHHHHHHHHHH",
        0x0060,
        core_field_index,
        0x011A,
        string_a,
        0x0212,
        0x2223,
        object_array_type,
        0x3071,
        hook_utils_call,
        0x0210,
        0x0071,
        haptic_disconnect,
        0x0000,
        0x000E,
    )
    new_insns = old_insns + appended_insns
    new_insns_size = old_insns_size + len(appended_insns) // 2
    if new_insns_size != 149:
        raise ValueError("unexpected real disconnect instruction count")

    # Both old and new instruction counts are odd, so the copied tail keeps
    # the original alignment padding, try table and catch handlers intact.
    old_tail_off = old_insns_off + old_insns_size * 2 + 2
    old_tail = bytes(dex[old_tail_off:next_code_off])
    new_code = bytearray(
        struct.pack(
            "<HHHHII", registers, ins, outs, tries, debug_info, new_insns_size
        )
    )
    new_code.extend(new_insns)
    new_code.extend(b"\x00\x00")
    new_code.extend(old_tail)

    map_off = struct.unpack_from("<I", dex, 52)[0]
    map_size = struct.unpack_from("<I", dex, map_off)[0]
    map_entries = [
        struct.unpack_from("<HHII", dex, map_off + 4 + index * 12)
        for index in range(map_size)
    ]
    if not map_entries or map_entries[-1][0] != 0x1000:
        raise ValueError("DEX map list does not end with map_item")

    new_code_off = (len(dex) + 3) & ~3
    if new_code_off != len(dex):
        dex.extend(b"\x00" * (new_code_off - len(dex)))
    dex.extend(new_code)

    # Keep the map list offset-sorted. The appended code is a second code
    # section because moving the original sections would require rewriting
    # every annotation/debug/class-data offset in the APK.
    new_map_off = (len(dex) + 3) & ~3
    if new_map_off != len(dex):
        dex.extend(b"\x00" * (new_map_off - len(dex)))
    new_map_entries = [entry for entry in map_entries if entry[0] != 0x1000]
    new_map_entries.append((0x2001, 0, 1, new_code_off))
    new_map_entries.append((0x1000, 0, 1, new_map_off))
    dex.extend(struct.pack("<I", len(new_map_entries)))
    for entry in new_map_entries:
        dex.extend(struct.pack("<HHII", *entry))
    struct.pack_into("<I", dex, 52, new_map_off)

    old_ref = _encode_uleb128(old_code_off)
    new_ref = _encode_uleb128(new_code_off)
    if len(old_ref) != len(new_ref):
        raise ValueError("relocated code reference changed ULEB width")
    dex[code_ref_offset : code_ref_offset + len(old_ref)] = new_ref

    struct.pack_into("<I", dex, 32, len(dex))
    data_off = struct.unpack_from("<I", dex, 108)[0]
    struct.pack_into("<I", dex, 104, len(dex) - data_off)


def patch_battery_invalidation(dex: bytearray) -> None:
    """Do not replace the last valid battery sample with an invalid sentinel."""
    class_descriptor = "Lcom/aclaniakea/colorosporttuning/HookUtils;"
    code_off, _, _ = _find_method_code_reference(
        dex, class_descriptor, "invalidateHardwareBattery"
    )
    registers, ins, outs, tries, debug_info, insns_size = struct.unpack_from(
        "<HHHHII", dex, code_off
    )
    if (registers, ins, outs, tries, debug_info, insns_size) != (
        4,
        1,
        3,
        1,
        0x17E3B,
        29,
    ):
        raise ValueError("battery invalidation code item does not match expected APK")

    insns_off = code_off + 16
    # The second invoke writes ipe_pencil_battery_level=-1; the third writes
    # the same sentinel through the IPe provider. Keep the surrounding code
    # and exception table intact, but turn both side effects into NOPs.
    expected_global_put = bytes.fromhex("713076001002")
    expected_provider_put = bytes.fromhex("7130a2000302")
    if bytes(dex[insns_off + 20 * 2 : insns_off + 23 * 2]) != expected_global_put:
        raise ValueError("global invalid battery write does not match expected APK")
    if bytes(dex[insns_off + 25 * 2 : insns_off + 28 * 2]) != expected_provider_put:
        raise ValueError("provider invalid battery write does not match expected APK")
    dex[insns_off + 20 * 2 : insns_off + 23 * 2] = b"\x00" * 6
    dex[insns_off + 25 * 2 : insns_off + 28 * 2] = b"\x00" * 6


def patch_hall_disconnect_latch_clear(dex: bytearray) -> None:
    """Keep magnetic Hall edges from unlocking an explicit disconnect."""
    class_descriptor = "Lcom/aclaniakea/colorosporttuning/PenBridgeReceiver;"
    code_off, _, _ = _find_method_code_reference(
        dex, class_descriptor, "publishPhysicalEdge"
    )
    registers, ins, outs, tries, debug_info, insns_size = struct.unpack_from(
        "<HHHHII", dex, code_off
    )
    if (registers, ins, outs, tries, debug_info, insns_size) != (
        19,
        2,
        13,
        1,
        0x18CAA,
        117,
    ):
        raise ValueError("Hall edge method does not match expected APK")

    strings, _ = _dex_string_tables(dex)
    try:
        disconnect_key = strings.index("lenovo_pen_disconnect_requested")
    except ValueError as exc:
        raise ValueError("disconnect latch string is missing") from exc

    insns_off = code_off + 16
    clear_call = None
    for index in range(insns_size - 2):
        first = struct.unpack_from("<H", dex, insns_off + index * 2)[0]
        if (first & 0xFF) != 0x1A:
            continue
        string_index = struct.unpack_from("<H", dex, insns_off + (index + 1) * 2)[0]
        if string_index != disconnect_key:
            continue
        # The Hall method's next static invoke is the Settings.Global.putInt
        # that clears the latch. Skip the preceding getContentResolver call.
        for call_index in range(index + 2, insns_size - 2):
            call = struct.unpack_from("<H", dex, insns_off + call_index * 2)[0]
            if (call & 0xFF) == 0x71:
                clear_call = call_index
                break
        break
    if clear_call is None:
        raise ValueError("Hall disconnect latch clear call was not found")
    dex[insns_off + clear_call * 2 : insns_off + (clear_call + 3) * 2] = b"\x00" * 6


def patch_dex(payload: bytes) -> bytes:
    dex = bytearray(payload)
    if dex[:4] != b"dex\n":
        raise ValueError("classes.dex is not a DEX file")
    if struct.unpack_from("<I", dex, 32)[0] != len(dex):
        raise ValueError("unexpected DEX file size")

    method_header = KERNEL_WAKE_CODE_ITEM
    registers, ins, outs, tries, debug_info, insns_size = struct.unpack_from(
        "<HHHHII", dex, method_header
    )
    if (registers, ins, outs, tries, debug_info, insns_size) != (
        6,
        0,
        2,
        2,
        0x18ED7,
        0x47,
    ):
        raise ValueError("enableKernelPenWake code item does not match expected APK")

    insns = method_header + 16
    original_prefix = bytes(dex[insns : insns + 16])
    expected_prefix = bytes.fromhex("1a0012021c01b2001d011a024700")
    if original_prefix[: len(expected_prefix)] != expected_prefix:
        raise ValueError("enableKernelPenWake instructions do not match expected APK")
    first_write = bytes.fromhex("71101e0302000a02")
    second_write = bytes.fromhex("71101e0303000a03")
    if bytes(dex[0x154A6 : 0x154A6 + 8]) != first_write:
        raise ValueError("first vendor wake write call does not match expected APK")
    if bytes(dex[0x154B2 : 0x154B2 + 8]) != second_write:
        raise ValueError("second vendor wake write call does not match expected APK")
    # const/4 v2/v3, #0 followed by nops. The old move-result instructions
    # are nopped too, so the verifier still sees valid instruction ordering.
    dex[0x154A6 : 0x154A6 + 8] = bytes.fromhex("1202000000000000")
    dex[0x154B2 : 0x154B2 + 8] = bytes.fromhex("1203000000000000")

    expected_call = bytes.fromhex("7130d8025401")
    if bytes(dex[SCREEN_ON_APPLY_CALL : SCREEN_ON_APPLY_CALL + 6]) != expected_call:
        raise ValueError("SCREEN_ON applyPenHall call does not match expected APK")
    dex[SCREEN_ON_APPLY_CALL : SCREEN_ON_APPLY_CALL + 6] = b"\x00" * 6

    # The old release searched for s0.c(Integer, String). That method is an
    # OEM UI/profile callback, not a GATT connector. The actual connector is
    # g.b(String), inherited by s0. Change only the const-string operand in
    # invokeOriginalGattConnect; string id 879 is the existing "b" entry.
    expected_gatt = bytes.fromhex("1a0a8b036e10")
    if bytes(dex[ORIGINAL_GATT_CONNECT_METHOD : ORIGINAL_GATT_CONNECT_METHOD + len(expected_gatt)]) != expected_gatt:
        raise ValueError("original GATT connect callback lookup does not match expected APK")
    dex[ORIGINAL_GATT_CONNECT_STRING : ORIGINAL_GATT_CONNECT_STRING + 2] = bytes.fromhex("6f03")
    log_offset = dex.find(ORIGINAL_GATT_LOG_STRING)
    if log_offset < 0:
        raise ValueError("original GATT connect log string was not found")
    dex[log_offset : log_offset + len(ORIGINAL_GATT_LOG_STRING)] = FIXED_GATT_LOG_STRING

    # Do not inject code into the stock s0.c callback.  The CoreService
    # onCreate/onStartCommand hooks already schedule the real GATT entry
    # (BleManager.b), while the original callback only mirrors OEM UI state.
    # Calling b again from c creates duplicate sessions and makes the stock
    # magnetic capsule/charging/settings callbacks race each other.

    # Preserve the last valid level while a transient disconnect is being
    # reported. The settings UI treats the invalid sentinel as 0%.
    patch_battery_invalidation(dex)
    patch_hall_disconnect_latch_clear(dex)

    # The Root service now owns the only automatic boot connection window.
    # Disable the old system_server HID retry and both synthetic OEM wake
    # methods. Real ACL/GATT callbacks and explicit CONNECT_PENCIL remain
    # active, so switching to another bonded pen still works.
    patch_void_method_to_return(
        dex,
        "Lcom/aclaniakea/colorosporttuning/SystemStylusHooks;",
        "restorePenAfterBoot",
    )
    patch_void_method_to_return(
        dex,
        "Lcom/aclaniakea/colorosporttuning/LenovoPenUEventBridge;",
        "wakeOemForCurrentPen",
    )
    patch_void_method_to_return(
        dex,
        "Lcom/aclaniakea/colorosporttuning/LenovoPenUEventBridge;",
        "wakeOemBluetoothReceiver",
    )

    # The My Devices detail activity previously returned before rewriting the
    # panel intent whenever the OEM Settings.Global mirror lagged behind the
    # real Bluetooth connection. The Root side now publishes the connected
    # snapshot, but keep this UI entry point tolerant of that same race: turn
    # the `if (state.connected)` branch into a short goto over its return.
    expected_detail = bytes.fromhex("390003000e00")
    if bytes(dex[MYDEVICES_DETAIL_CONNECTED_BRANCH : MYDEVICES_DETAIL_CONNECTED_BRANCH + len(expected_detail)]) != expected_detail:
        raise ValueError("My Devices detail connected guard does not match expected APK")
    dex[MYDEVICES_DETAIL_CONNECTED_BRANCH : MYDEVICES_DETAIL_CONNECTED_BRANCH + len(expected_detail)] = bytes.fromhex("280300000e00")

    # The stock CoreService action also calls s0.z(), which closes the
    # BluetoothDevice/HID side but leaves the OEM BluetoothGatt object alive
    # on this port. Close that GATT object from the same action hook before the
    # original service work runs.
    patch_real_gatt_disconnect(dex)

    # DEX v035 stores a SHA-1 signature and Adler-32 checksum in its header.
    dex[12:32] = hashlib.sha1(dex[32:]).digest()
    struct.pack_into("<I", dex, 8, zlib.adler32(dex[12:]) & 0xFFFFFFFF)
    return bytes(dex)


def copy_zip_entry(out: zipfile.ZipFile, source: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = source.getinfo(name)
    copied = zipfile.ZipInfo(name, date_time=info.date_time)
    copied.compress_type = zipfile.ZIP_STORED
    copied.external_attr = info.external_attr
    copied.create_system = info.create_system
    out.writestr(copied, data)


def build(input_apk: str, output_apk: str) -> None:
    actual_sha = hashlib.sha256(open(input_apk, "rb").read()).hexdigest()
    if actual_sha != BASE_APK_SHA256:
        raise ValueError(
            f"unexpected input APK sha256={actual_sha}; expected {BASE_APK_SHA256}"
        )

    with zipfile.ZipFile(input_apk, "r") as source:
        entries = {name: source.read(name) for name in source.namelist()}
        entries["classes.dex"] = patch_dex(entries["classes.dex"])

    parent = os.path.dirname(os.path.abspath(output_apk))
    os.makedirs(parent, exist_ok=True)
    with zipfile.ZipFile(output_apk, "w", compression=zipfile.ZIP_STORED) as out:
        with zipfile.ZipFile(input_apk, "r") as source:
            for name in source.namelist():
                if name.upper().startswith("META-INF/"):
                    continue
                copy_zip_entry(out, source, name, entries[name])

    print(f"built {output_apk}")
    print(f"sha256 {hashlib.sha256(open(output_apk, 'rb').read()).hexdigest()}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(f"usage: {sys.argv[0]} INPUT_APK OUTPUT_APK")
    build(sys.argv[1], sys.argv[2])
