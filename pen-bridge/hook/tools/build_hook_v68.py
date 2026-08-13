#!/usr/bin/env python3
"""Build the profile-safe Hook from the verified v1.0.63 APK.

This deliberately starts from the stable real-disconnect release instead of
the later boot-recovery builds. The patch makes Device Space presence follow
the actual Bluetooth state rather than the adaptive-refresh state, while
keeping every stock s0 profile callback intact.
"""

from __future__ import annotations

import hashlib
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


BASE_APK_SHA256 = "48e0ba71d7f7f9c33c85ba7dde7dbf39f95a0d065717f1cf2fa9cdddd68f2df0"
TOOLS_DIR = Path(os.environ.get("PEN_SMALI_TOOLS_DIR", "/tmp/codex-dex-tools"))
JARS = (
    "antlr-runtime-3.5.2.jar",
    "baksmali-2.5.2.jar",
    "dexlib2-2.5.2.jar",
    "guava-27.1-android.jar",
    "jcommander-1.64.jar",
    "smali-2.5.2.jar",
    "util-2.5.2.jar",
)


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise ValueError(f"{path.name}: expected one patch target, found {count}")
    path.write_text(text.replace(old, new), encoding="utf-8")


def patch_is_known(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    pattern = re.compile(
        r"\.method private static isKnown\(Landroid/content/Context;Ljava/lang/String;\)Z\n"
        r".*?\.end method",
        re.DOTALL,
    )
    replacement = """.method private static isKnown(Landroid/content/Context;Ljava/lang/String;)Z
    .registers 4

    if-eqz p1, :cond_2c

    if-nez p0, :cond_a

    invoke-static {}, Lcom/oplus/ipemanager/btadsorb/ota/i;->i()Lcom/oplus/ipemanager/btadsorb/IPeApplication;

    move-result-object p0

    :cond_a
    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->state(Landroid/content/Context;)Lcom/aclaniakea/colorosporttuning/PenState;

    move-result-object p0

    iget-object v0, p0, Lcom/aclaniakea/colorosporttuning/PenState;->address:Ljava/lang/String;

    invoke-static {v0, p1}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->samePenAddress(Ljava/lang/String;Ljava/lang/String;)Z

    move-result p0

    if-nez p0, :cond_28

    sget-object p0, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->lastStockProfileMac:Ljava/lang/String;

    invoke-static {p0, p1}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->samePenAddress(Ljava/lang/String;Ljava/lang/String;)Z

    move-result p0

    if-nez p0, :cond_28

    sget-object p0, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->lastStockGattConnectMac:Ljava/lang/String;

    invoke-static {p0, p1}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->samePenAddress(Ljava/lang/String;Ljava/lang/String;)Z

    move-result p0

    if-eqz p0, :cond_2c

    :cond_28
    const/4 p0, 0x1

    return p0

    :cond_2c
    const/4 p0, 0x0

    return p0
.end method"""
    text, count = pattern.subn(replacement, text)
    if count != 1:
        raise ValueError(f"{path.name}: expected one isKnown patch target, found {count}")
    path.write_text(text, encoding="utf-8")


def patch_device_card_type(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    pattern = re.compile(
        r"\.method protected beforeHookedMethod\(Lde/robv/android/xposed/"
        r"XC_MethodHook\$MethodHookParam;\)V\n.*?\.end method",
        re.DOTALL,
    )
    replacement = """.method protected beforeHookedMethod(Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;)V
    .registers 3

    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->method:Ljava/lang/reflect/Member;

    check-cast v0, Ljava/lang/reflect/Method;

    invoke-virtual {v0}, Ljava/lang/reflect/Method;->getReturnType()Ljava/lang/Class;

    move-result-object v0

    const-string v1, "PENCIL"

    invoke-static {v0, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->adapt(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v0

    if-eqz v0, :cond_1c

    invoke-virtual {p1, v0}, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->setResult(Ljava/lang/Object;)V

    const-string p1, "IPe device-card Binder: forcing PENCIL for Lenovo Pen Bridge"

    invoke-static {p1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    :cond_1c
    return-void
.end method"""
    text, count = pattern.subn(replacement, text)
    if count != 1:
        raise ValueError(f"{path.name}: expected one device-card hook target, found {count}")
    path.write_text(text, encoding="utf-8")


def patch_device_card_proxy_type(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    pattern = re.compile(
        r"\.method protected afterHookedMethod\(Lde/robv/android/xposed/"
        r"XC_MethodHook\$MethodHookParam;\)V\n.*?\.end method",
        re.DOTALL,
    )
    replacement = """.method protected afterHookedMethod(Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;)V
    .registers 3

    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->method:Ljava/lang/reflect/Member;

    check-cast v0, Ljava/lang/reflect/Method;

    invoke-virtual {v0}, Ljava/lang/reflect/Method;->getReturnType()Ljava/lang/Class;

    move-result-object v0

    const-string v1, "PENCIL"

    invoke-static {v0, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->adapt(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v0

    if-eqz v0, :cond_1c

    invoke-virtual {p1, v0}, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->setResult(Ljava/lang/Object;)V

    const-string p1, "IPe device-card AIDL proxy: forcing PENCIL for Lenovo Pen Bridge"

    invoke-static {p1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    :cond_1c
    return-void
.end method"""
    text, count = pattern.subn(replacement, text)
    if count != 1:
        raise ValueError(f"{path.name}: expected one device-card proxy hook target, found {count}")
    path.write_text(text, encoding="utf-8")


PENCIL_PANEL_CONNECT_SMALI = '.class Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$44;\n.super Lde/robv/android/xposed/XC_MethodHook;\n.source "IpeManagerHooks.java"\n\n\n# annotations\n.annotation system Ldalvik/annotation/EnclosingMethod;\n    value = Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->installPencilPanelControlBridge(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V\n.end annotation\n\n.annotation system Ldalvik/annotation/InnerClass;\n    accessFlags = 0x0\n    name = null\n.end annotation\n\n\n# direct methods\n.method constructor <init>()V\n    .registers 1\n\n    invoke-direct {p0}, Lde/robv/android/xposed/XC_MethodHook;-><init>()V\n\n    return-void\n.end method\n\n\n# virtual methods\n.method protected beforeHookedMethod(Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;)V\n    .registers 7\n\n    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->thisObject:Ljava/lang/Object;\n\n    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->context(Ljava/lang/Object;)Landroid/content/Context;\n\n    move-result-object v0\n\n    if-eqz v0, :cond_47\n\n    iget-object v1, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->args:[Ljava/lang/Object;\n\n    # invokes: Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->stringArg([Ljava/lang/Object;)Ljava/lang/String;\n    invoke-static {v1}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->access$1200([Ljava/lang/Object;)Ljava/lang/String;\n\n    move-result-object v1\n\n    if-eqz v1, :cond_47\n\n    invoke-virtual {v1}, Ljava/lang/String;->length()I\n\n    move-result v2\n\n    if-eqz v2, :cond_47\n\n    new-instance v2, Landroid/content/Intent;\n\n    const-string v3, "com.oplus.ipemanager.action.CONNECT_PENCIL"\n\n    invoke-direct {v2, v3}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V\n\n    const-string v3, "device_mac_info"\n\n    invoke-virtual {v2, v3, v1}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;\n\n    move-result-object v2\n\n    const-string v3, "com.oplus.ipemanager"\n\n\n    const-string v4, "com.oplus.ipemanager.btadsorb.CoreService"\n\n\n    invoke-virtual {v2, v3, v4}, Landroid/content/Intent;->setClassName(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;\n\n    move-result-object v2\n\n    invoke-virtual {v0, v2}, Landroid/content/Context;->startService(Landroid/content/Intent;)Landroid/content/ComponentName;\n\n    new-instance v0, Ljava/lang/StringBuilder;\n\n    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V\n\n    const-string v2, "IPe PencilPanel connect routed to stock CONNECT_PENCIL mac="\n\n    invoke-virtual {v0, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n\n    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n\n    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;\n\n    move-result-object v0\n\n    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V\n\n    :cond_47\n    return-void\n.end method\n'


PENCIL_PANEL_DISCONNECT_SMALI = '.class Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$45;\n.super Lde/robv/android/xposed/XC_MethodHook;\n.source "IpeManagerHooks.java"\n\n\n# annotations\n.annotation system Ldalvik/annotation/EnclosingMethod;\n    value = Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->installPencilPanelControlBridge(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V\n.end annotation\n\n.annotation system Ldalvik/annotation/InnerClass;\n    accessFlags = 0x0\n    name = null\n.end annotation\n\n\n# direct methods\n.method constructor <init>()V\n    .registers 1\n\n    invoke-direct {p0}, Lde/robv/android/xposed/XC_MethodHook;-><init>()V\n\n    return-void\n.end method\n\n\n# virtual methods\n.method protected beforeHookedMethod(Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;)V\n    .registers 7\n\n    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->thisObject:Ljava/lang/Object;\n\n    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->context(Ljava/lang/Object;)Landroid/content/Context;\n\n    move-result-object v0\n\n    if-eqz v0, :cond_47\n\n    iget-object v1, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->args:[Ljava/lang/Object;\n\n    # invokes: Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->stringArg([Ljava/lang/Object;)Ljava/lang/String;\n    invoke-static {v1}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->access$1200([Ljava/lang/Object;)Ljava/lang/String;\n\n    move-result-object v1\n\n    if-eqz v1, :cond_47\n\n    invoke-virtual {v1}, Ljava/lang/String;->length()I\n\n    move-result v2\n\n    if-eqz v2, :cond_47\n\n    new-instance v2, Landroid/content/Intent;\n\n    const-string v3, "com.oplus.ipemanager.action.DISCONNECT_PENCIL"\n\n    invoke-direct {v2, v3}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V\n\n    const-string v3, "device_mac_info"\n\n    invoke-virtual {v2, v3, v1}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;\n\n    move-result-object v2\n\n    const-string v3, "com.oplus.ipemanager"\n\n\n    const-string v4, "com.oplus.ipemanager.btadsorb.CoreService"\n\n\n    invoke-virtual {v2, v3, v4}, Landroid/content/Intent;->setClassName(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;\n\n    move-result-object v2\n\n    invoke-virtual {v0, v2}, Landroid/content/Context;->startService(Landroid/content/Intent;)Landroid/content/ComponentName;\n\n    new-instance v0, Ljava/lang/StringBuilder;\n\n    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V\n\n    const-string v2, "IPe PencilPanel disconnect routed to stock DISCONNECT_PENCIL mac="\n\n    invoke-virtual {v0, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n\n    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n\n    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;\n\n    move-result-object v0\n\n    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V\n\n    :cond_47\n    return-void\n.end method\n'


INSTALL_PENCIL_PANEL_CONTROL_METHOD = '.method private static installPencilPanelControlBridge(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V\n    .registers 5\n\n    new-instance v0, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$44;\n\n    invoke-direct {v0}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$44;-><init>()V\n\n    iget-object v1, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;\n\n    const-string v2, "com.oplus.ipemanager.btadsorb.ble.f0"\n\n    const-string v3, "connect"\n\n    invoke-static {v1, v2, v3, v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I\n\n    new-instance v0, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$45;\n\n    invoke-direct {v0}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$45;-><init>()V\n\n    iget-object v1, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;\n\n    const-string v3, "disconnect"\n\n    invoke-static {v1, v2, v3, v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I\n\n    const-string p0, "IPe PencilPanel control bridge installed"\n\n    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V\n\n    return-void\n.end method\n'
def add_pencil_panel_control_bridge(root: Path) -> None:
    package = root / "com" / "codex" / "colorosporttuning"
    (package / "IpeManagerHooks$44.smali").write_text(PENCIL_PANEL_CONNECT_SMALI, encoding="utf-8")
    (package / "IpeManagerHooks$45.smali").write_text(PENCIL_PANEL_DISCONNECT_SMALI, encoding="utf-8")

    hooks_path = package / "IpeManagerHooks.smali"
    install_anchor = """    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->installWritingHapticPreference(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V

    .line 186
    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->installMyDevicesStateBridge(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V"""
    install_replacement = """    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->installWritingHapticPreference(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V

    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->installPencilPanelControlBridge(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V

    .line 186
    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->installMyDevicesStateBridge(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V"""
    replace_once(hooks_path, install_anchor, install_replacement)

    text = hooks_path.read_text(encoding="utf-8")
    method_anchor = ".method private static installWritingHapticPreference(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V"
    if text.count(method_anchor) != 1:
        raise ValueError("IpeManagerHooks.smali: expected one method anchor, found " + str(text.count(method_anchor)))
    text = text.replace(method_anchor, INSTALL_PENCIL_PANEL_CONTROL_METHOD + "\n\n" + method_anchor, 1)
    hooks_path.write_text(text, encoding="utf-8")


def patch_handoff_charging(path: Path) -> None:
    old = """    .line 376
    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->oemCharging(Landroid/content/Context;)I

    move-result v8

    if-ltz v8, :cond_96

    move v4, v8

    .line 378
    :cond_96
    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->physicalDocked(Landroid/content/Context;)I

    move-result v8

    if-nez v8, :cond_a4

    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->oemCharging(Landroid/content/Context;)I

    move-result v8

    if-gez v8, :cond_a4

    move v12, v7

    goto :goto_a5

    :cond_a4
    move v12, v4
"""
    new = """    .line 378
    :cond_96
    move v12, v4
"""
    replace_once(path, old, new)


def patch_pen_state_charging(path: Path) -> None:
    old = """    invoke-static {v2}, Lcom/aclaniakea/colorosporttuning/HookUtils;->physicalDocked(Landroid/content/Context;)I

    move-result v4

    if-nez v4, :cond_87

    invoke-static {v2}, Lcom/aclaniakea/colorosporttuning/HookUtils;->oemCharging(Landroid/content/Context;)I

    move-result v2

    if-gez v2, :cond_87

    move v10, v0

    goto :goto_88
"""
    new = """    invoke-static {v2}, Lcom/aclaniakea/colorosporttuning/HookUtils;->physicalDocked(Landroid/content/Context;)I

    move-result v4

    if-nez v4, :cond_87

    invoke-virtual {v2}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object v5

    const-string v4, "lenovo_pen_hardware_charge_valid"

    const/4 v6, 0x0

    invoke-static {v5, v4, v6}, Landroid/provider/Settings$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I

    move-result v4

    if-nez v4, :cond_87

    invoke-static {v2}, Lcom/aclaniakea/colorosporttuning/HookUtils;->oemCharging(Landroid/content/Context;)I

    move-result v2

    if-gez v2, :cond_87

    move v10, v0

    goto :goto_88
"""
    replace_once(path, old, new)


def patch_publish_hardware_charging(path: Path) -> None:
    old = """    move/from16 v1, p3

    .line 262
    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->physicalDocked(Landroid/content/Context;)I"""
    new = """    move/from16 v1, p3

    invoke-virtual {v0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object v2

    const-string v3, "lenovo_pen_hardware_charge_valid"

    const/4 v4, 0x0

    invoke-static {v2, v3, v4}, Landroid/provider/Settings$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I

    move-result v2

    if-eqz v2, :cond_stock

    invoke-virtual {v0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object v2

    const-string v3, "lenovo_pen_hardware_charge_state"

    const/4 v4, -0x1

    invoke-static {v2, v3, v4}, Landroid/provider/Settings$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I

    move-result v2

    if-ltz v2, :cond_stock

    const/4 v3, 0x2

    if-ge v2, v3, :cond_stock

    invoke-static {v0, v1, v2}, Lcom/aclaniakea/colorosporttuning/HookUtils;->markOemCharging(Landroid/content/Context;II)V

    move v9, v2

    const-string v2, "IPe BLE charge sample overridden by CPS"

    invoke-static {v2}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    goto :goto_28

    :cond_stock
    .line 262
    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->physicalDocked(Landroid/content/Context;)I"""
    replace_once(path, old, new)


def patch_panel_disconnect_acl(path: Path) -> None:
    old_tail = """    invoke-static {}, Lcom/aclaniakea/colorosporttuning/PenHapticGatt;->disconnect()V

    return-void
.end method"""
    new_tail = """    invoke-static {}, Lcom/aclaniakea/colorosporttuning/PenHapticGatt;->disconnect()V

    sget-object v0, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->lastStockGattConnectMac:Ljava/lang/String;

    if-eqz v0, :cond_done

    invoke-static {}, Landroid/bluetooth/BluetoothAdapter;->getDefaultAdapter()Landroid/bluetooth/BluetoothAdapter;

    move-result-object v1

    if-eqz v1, :cond_done

    invoke-virtual {v1, v0}, Landroid/bluetooth/BluetoothAdapter;->getRemoteDevice(Ljava/lang/String;)Landroid/bluetooth/BluetoothDevice;

    move-result-object v0

    if-eqz v0, :cond_done

    const/4 v1, 0x0

    new-array v1, v1, [Ljava/lang/Object;

    const-string v2, "disconnect"

    invoke-static {v0, v2, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->call(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;

    const/4 v1, 0x2

    new-array v1, v1, [Ljava/lang/Object;

    const/4 v2, 0x7

    invoke-static {v2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v2

    const/4 v3, 0x0

    invoke-static {v3}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object v3

    const/4 v4, 0x0

    aput-object v2, v1, v4

    const/4 v4, 0x1

    aput-object v3, v1, v4

    const-string v2, "setConnectionPolicy"

    invoke-static {v0, v2, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->call(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;

    const/4 v1, 0x2

    new-array v1, v1, [Ljava/lang/Object;

    const/4 v2, 0x4

    invoke-static {v2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v2

    const/4 v3, 0x0

    invoke-static {v3}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object v3

    const/4 v4, 0x0

    aput-object v2, v1, v4

    const/4 v4, 0x1

    aput-object v3, v1, v4

    const-string v2, "setConnectionPolicy"

    invoke-static {v0, v2, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->call(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;

    const-string v0, "IPe ACL/HID disconnect requested at Bluetooth layer"

    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    :cond_done
    return-void
.end method"""
    replace_once(path, old_tail, new_tail)
    text = path.read_text(encoding="utf-8")
    old_reg = "    .registers 5"
    if text.count(old_reg) != 1:
        raise ValueError(f"{path.name}: expected one .registers 5, found {text.count(old_reg)}")
    text = text.replace(old_reg, "    .registers 10", 1)
    path.write_text(text, encoding="utf-8")


def patch_connect_policy_restore(path: Path) -> None:
    old = """    .line 659
    :cond_10
    sget-object p0, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->coreBleManager:Ljava/lang/Object;"""
    new = """    .line 659
    :cond_10
    invoke-static {}, Landroid/bluetooth/BluetoothAdapter;->getDefaultAdapter()Landroid/bluetooth/BluetoothAdapter;

    move-result-object v0

    if-eqz v0, :cond_pol

    invoke-virtual {v0, p1}, Landroid/bluetooth/BluetoothAdapter;->getRemoteDevice(Ljava/lang/String;)Landroid/bluetooth/BluetoothDevice;

    move-result-object v0

    if-eqz v0, :cond_pol

    const/4 v1, 0x2

    new-array v1, v1, [Ljava/lang/Object;

    const/4 v2, 0x7

    invoke-static {v2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v2

    const/4 v3, 0x1

    invoke-static {v3}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object v3

    const/4 v4, 0x0

    aput-object v2, v1, v4

    const/4 v4, 0x1

    aput-object v3, v1, v4

    const-string v2, "setConnectionPolicy"

    invoke-static {v0, v2, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->call(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;

    const/4 v1, 0x2

    new-array v1, v1, [Ljava/lang/Object;

    const/4 v2, 0x4

    invoke-static {v2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v2

    const/4 v3, 0x1

    invoke-static {v3}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object v3

    const/4 v4, 0x0

    aput-object v2, v1, v4

    const/4 v4, 0x1

    aput-object v3, v1, v4

    const-string v2, "setConnectionPolicy"

    invoke-static {v0, v2, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->call(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;

    :cond_pol
    sget-object p0, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->coreBleManager:Ljava/lang/Object;"""
    replace_once(path, old, new)


def patch_connect_user_latch(path: Path) -> None:
    old = """    if-eqz v0, :cond_52

    .line 90
    :try_start_45
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    invoke-static {p0, v1, v2}, Landroid/provider/Settings$Global;->putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z"""
    new = """    if-eqz v0, :cond_52

    .line 90
    :try_start_45
    const-string v3, "codex_auto_connect"

    const/4 v4, 0x0

    invoke-virtual {p1, v3, v4}, Landroid/content/Intent;->getBooleanExtra(Ljava/lang/String;Z)Z

    move-result v3

    if-nez v3, :cond_user_latch

    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object v5

    const-string v3, "lenovo_pen_user_disconnect_requested"

    invoke-static {v5, v3, v4}, Landroid/provider/Settings$Global;->putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z

    :cond_user_latch
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    invoke-static {p0, v1, v2}, Landroid/provider/Settings$Global;->putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z"""
    replace_once(path, old, new)


def patch_disconnect_user_latch_and_mac(path: Path) -> None:
    old = """    const-string v0, "com.oplus.ipemanager.action.DISCONNECT_PENCIL"

    invoke-virtual {p1}, Landroid/content/Intent;->getAction()Ljava/lang/String;"""
    new = """    const-string v0, "com.oplus.ipemanager.action.DISCONNECT_PENCIL"

    const-string v4, "device_mac_info"

    invoke-virtual {p1, v4}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v5

    invoke-virtual {p1}, Landroid/content/Intent;->getAction()Ljava/lang/String;"""
    replace_once(path, old, new)

    text = path.read_text(encoding="utf-8")
    old2 = """    invoke-static {p1, v1, v0}, Landroid/provider/Settings$Global;->putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z

    .line 94"""
    new2 = """    invoke-static {p1, v1, v0}, Landroid/provider/Settings$Global;->putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z

    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p1

    const-string v0, "lenovo_pen_user_disconnect_requested"

    const/4 v1, 0x1

    invoke-static {p1, v0, v1}, Landroid/provider/Settings$Global;->putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z

    .line 94"""
    if text.count(old2) != 1:
        raise ValueError("disconnect putInt anchor: " + str(text.count(old2)))
    text = text.replace(old2, new2, 1)
    old3 = """    sget-object v0, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->lastStockGattConnectMac:Ljava/lang/String;

    if-eqz v0, :cond_done"""
    new3 = """    move-object v0, v5

    if-eqz v0, :cond_done"""
    if text.count(old3) != 1:
        raise ValueError("lastStockGattConnectMac anchor: " + str(text.count(old3)))
    text = text.replace(old3, new3, 1)
    path.write_text(text, encoding="utf-8")


PENCIL_PANEL_DISCONNECT_SMALI__PLACEHOLDER__ = """.class Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$46;
.super Lde/robv/android/xposed/XC_MethodHook;
.source "IpeManagerHooks.java"


# annotations
.annotation system Ldalvik/annotation/EnclosingMethod;
    value = Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->installMyDevicesCardBatteryBridge(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x0
    name = null
.end annotation


# direct methods
.method constructor <init>()V
    .registers 1

    invoke-direct {p0}, Lde/robv/android/xposed/XC_MethodHook;-><init>()V

    return-void
.end method


# virtual methods
.method protected afterHookedMethod(Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;)V
    .registers 7

    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->thisObject:Ljava/lang/Object;

    if-eqz v0, :cond_done

    const-string v1, "getBatteryType"

    const/4 v2, 0x0

    new-array v2, v2, [Ljava/lang/Object;

    invoke-static {v0, v1, v2}, Lcom/aclaniakea/colorosporttuning/HookUtils;->call(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v0

    if-eqz v0, :cond_done

    invoke-virtual {v0}, Ljava/lang/Object;->getClass()Ljava/lang/Class;

    move-result-object v1

    invoke-virtual {v1}, Ljava/lang/Class;->getSimpleName()Ljava/lang/String;

    move-result-object v1

    const-string v2, "IPe card battery getCharge type="

    invoke-virtual {v2, v1}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v2

    invoke-static {v2}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    const-string v2, "SINGLE"

    invoke-virtual {v1, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v1

    if-eqz v1, :cond_done

    invoke-static {}, Landroid/app/ActivityThread;->currentApplication()Landroid/app/Application;

    move-result-object v0

    if-eqz v0, :cond_done

    invoke-virtual {v0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object v0

    const-string v1, "ipe_pencil_charging_state"

    const/4 v2, 0x0

    invoke-static {v0, v1, v2}, Landroid/provider/Settings$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I

    move-result v0

    if-nez v0, :cond_3e

    const/4 v0, 0x0

    goto :goto_3f

    :cond_3e
    const/4 v0, 0x1

    :goto_3f
    invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object v0

    invoke-virtual {p1, v0}, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->setResult(Ljava/lang/Object;)V

    const-string v0, "IPe DeviceSpace card charging overridden"

    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    :cond_done
    return-void
.end method
"""


INSTALL_CARD_BATTERY__PLACEHOLDER__ = """.method private static installMyDevicesCardBatteryBridge(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V
    .registers 5

    new-instance v0, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$46;

    invoke-direct {v0}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$46;-><init>()V

    iget-object v1, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;

    const-string v2, "com.oplus.mydevices.domain.entities.device.BatteryInfo"

    const-string v3, "getCharge"

    invoke-static {v1, v2, v3, v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I

    new-instance v0, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$47;

    invoke-direct {v0}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$47;-><init>()V

    iget-object v1, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;

    const-string v2, "com.heytap.mydevices.core.bluetooth.b"

    const-string v3, "f"

    invoke-static {v1, v2, v3, v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I

    new-instance v0, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$49;

    invoke-direct {v0}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$49;-><init>()V

    iget-object v1, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;

    const-string v2, "com.oplus.mydevices.quickapp.homecard.view.BatteryLottieView"

    const-string v3, "setBatteryInfo"

    invoke-static {v1, v2, v3, v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I

    new-instance v0, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$49;

    invoke-direct {v0}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$49;-><init>()V

    iget-object v1, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;

    const-string v2, "com.oplus.mydevices.domain.entities.cards.QuickCardDeviceData"

    const-string v3, "getBatteryMain"

    invoke-static {v1, v2, v3, v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I

    return-void
.end method
"""




CARD_DIAG_SMALI_49__ = '.class Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$49;\n.super Lde/robv/android/xposed/XC_MethodHook;\n.source "IpeManagerHooks.java"\n\n\n# annotations\n.annotation system Ldalvik/annotation/EnclosingMethod;\n    value = Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->installMyDevicesCardBatteryBridge(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V\n.end annotation\n\n.annotation system Ldalvik/annotation/InnerClass;\n    accessFlags = 0x0\n    name = null\n.end annotation\n\n\n# direct methods\n.method constructor <init>()V\n    .registers 1\n\n    invoke-direct {p0}, Lde/robv/android/xposed/XC_MethodHook;-><init>()V\n\n    return-void\n.end method\n\n\n# virtual methods\n.method protected beforeHookedMethod(Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;)V\n    .registers 5\n\n    const-string v0, "IPe card battery path hit"\n\n    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V\n\n    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->args:[Ljava/lang/Object;\n\n    if-eqz v0, :cond_done\n\n    array-length v1, v0\n\n    if-eqz v1, :cond_done\n\n    const/4 v1, 0x0\n\n    aget-object v2, v0, v1\n\n    if-eqz v2, :cond_done\n\n    const-string v3, "getValue"\n\n    new-array v4, v1, [Ljava/lang/Object;\n\n    invoke-static {v2, v3, v4}, Lcom/aclaniakea/colorosporttuning/HookUtils;->call(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;\n\n    move-result-object v2\n\n    const-string v3, "IPe card battery value="\n\n    invoke-static {v3}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V\n\n    :cond_done\n    return-void\n.end method\n'


IPE_CARD_BATTERY_SMALI_47__ = '.class Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$47;\n.super Lde/robv/android/xposed/XC_MethodHook;\n.source "IpeManagerHooks.java"\n\n\n# annotations\n.annotation system Ldalvik/annotation/EnclosingMethod;\n    value = Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->installMyDevicesCardBatteryBridge(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V\n.end annotation\n\n.annotation system Ldalvik/annotation/InnerClass;\n    accessFlags = 0x0\n    name = null\n.end annotation\n\n\n# direct methods\n.method constructor <init>()V\n    .registers 1\n\n    invoke-direct {p0}, Lde/robv/android/xposed/XC_MethodHook;-><init>()V\n\n    return-void\n.end method\n\n\n# virtual methods\n.method protected afterHookedMethod(Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;)V\n    .registers 9\n\n    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->args:[Ljava/lang/Object;\n\n    if-eqz v0, :cond_done\n\n    array-length v1, v0\n\n    const/4 v2, 0x2\n\n    if-ge v1, v2, :cond_done\n\n    const/4 v1, 0x0\n\n    aget-object v2, v0, v1\n\n    if-eqz v2, :cond_done\n\n    check-cast v2, Ljava/lang/Integer;\n\n    invoke-virtual {v2}, Ljava/lang/Integer;->intValue()I\n\n    move-result v2\n\n    const/16 v3, 0x7d0\n\n    if-ne v2, v3, :cond_done\n\n    const/4 v2, 0x1\n\n    aget-object v3, v0, v2\n\n    if-eqz v3, :cond_done\n\n    invoke-virtual {v3}, Ljava/lang/Object;->getClass()Ljava/lang/Class;\n\n    move-result-object v4\n\n    invoke-virtual {v4}, Ljava/lang/Class;->getName()Ljava/lang/String;\n\n    move-result-object v4\n\n    const-string v5, "[I"\n\n    invoke-virtual {v4, v5}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z\n\n    move-result v4\n\n    if-eqz v4, :cond_done\n\n    check-cast v3, [I\n\n    array-length v4, v3\n\n    const/4 v5, 0x4\n\n    if-ge v4, v5, :cond_done\n\n    invoke-static {}, Landroid/app/ActivityThread;->currentApplication()Landroid/app/Application;\n\n    move-result-object v4\n\n    if-eqz v4, :cond_done\n\n    invoke-virtual {v4}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;\n\n    move-result-object v4\n\n    const-string v5, "ipe_pencil_charging_state"\n\n    const/4 v6, 0x0\n\n    invoke-static {v4, v5, v6}, Landroid/provider/Settings$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I\n\n    move-result v4\n\n    if-nez v4, :cond_done\n\n    const/4 v4, 0x3\n\n    aput v6, v3, v4\n\n    const-string v4, "IPe card battery charge cleared"\n\n    invoke-static {v4}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V\n\n    :cond_done\n    return-void\n.end method\n'


def add_card_battery_bridge(root: Path) -> None:
    package = root / "com" / "codex" / "colorosporttuning"
    (package / "IpeManagerHooks$46.smali").write_text(PENCIL_PANEL_DISCONNECT_SMALI__PLACEHOLDER__, encoding="utf-8")
    hooks = package / "IpeManagerHooks.smali"
    t = hooks.read_text(encoding="utf-8")
    ma = ".method private static installWritingHapticPreference(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V"
    assert t.count(ma) == 1
    t = t.replace(ma, INSTALL_CARD_BATTERY__PLACEHOLDER__ + chr(10)*2 + ma, 1)
    ia = "    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->installPencilPanelControlBridge(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V"
    assert t.count(ia) == 1
    t = t.replace(ia, ia + chr(10) + "    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->installMyDevicesCardBatteryBridge(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V", 1)
    (package / "IpeManagerHooks$47.smali").write_text(IPE_CARD_BATTERY_SMALI_47__, encoding="utf-8")
    (package / "IpeManagerHooks$49.smali").write_text(CARD_DIAG_SMALI_49__, encoding="utf-8")
    hooks.write_text(t, encoding="utf-8")


def patch_connect_restore_policy(path: Path) -> None:
    old = """    const-string p0, "stock CONNECT_PENCIL cleared settings disconnect latch"

    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    return-void"""
    new = """    const-string p0, "stock CONNECT_PENCIL cleared settings disconnect latch"

    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    const-string v3, "device_mac_info"

    invoke-virtual {p1, v3}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v3

    if-eqz v3, :cond_pol_done

    invoke-static {}, Landroid/bluetooth/BluetoothAdapter;->getDefaultAdapter()Landroid/bluetooth/BluetoothAdapter;

    move-result-object v4

    if-eqz v4, :cond_pol_done

    invoke-virtual {v4, v3}, Landroid/bluetooth/BluetoothAdapter;->getRemoteDevice(Ljava/lang/String;)Landroid/bluetooth/BluetoothDevice;

    move-result-object v3

    if-eqz v3, :cond_pol_done

    const/4 v4, 0x2

    new-array v4, v4, [Ljava/lang/Object;

    const/4 v5, 0x7

    invoke-static {v5}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v5

    const/4 v6, 0x1

    invoke-static {v6}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object v6

    const/4 v7, 0x0

    aput-object v5, v4, v7

    const/4 v7, 0x1

    aput-object v6, v4, v7

    const-string v5, "setConnectionPolicy"

    invoke-static {v3, v5, v4}, Lcom/aclaniakea/colorosporttuning/HookUtils;->call(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;

    const/4 v4, 0x2

    new-array v4, v4, [Ljava/lang/Object;

    const/4 v5, 0x4

    invoke-static {v5}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v5

    const/4 v6, 0x1

    invoke-static {v6}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object v6

    const/4 v7, 0x0

    aput-object v5, v4, v7

    const/4 v7, 0x1

    aput-object v6, v4, v7

    const-string v5, "setConnectionPolicy"

    invoke-static {v3, v5, v4}, Lcom/aclaniakea/colorosporttuning/HookUtils;->call(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;

    const-string v3, "IPe CONNECT_PENCIL restored GATT/HID reconnect policy"

    invoke-static {v3}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    :cond_pol_done
    return-void"""
    replace_once(path, old, new)



def repair_object_static_gets(root: Path) -> None:
    """Repair a malformed opcode in the v1.0.63 baseline dex.

    The source dex uses ``sget`` for an Object field in IpeManagerHooks$3.
    Older ART accepted it, but the current ROM verifier rejects the whole
    hook class before install() can register any hooks.
    """
    pattern = re.compile(r"(?m)^(\s*)sget (?=[vp]\d+, L[^;]+;->[^:]+:(?:L|\[))")
    repaired = 0
    for path in root.rglob("*.smali"):
        text = path.read_text(encoding="utf-8")
        text, count = pattern.subn(r"\1sget-object ", text)
        if count:
            path.write_text(text, encoding="utf-8")
            repaired += count
    if repaired != 1:
        raise ValueError(f"expected one malformed object sget in baseline, repaired {repaired}")



def patch_state_real_bt(root: Path) -> None:
    """Two-way sync in HookUtils.state().

    The v1.0.63 baseline computes the UI-connected flag as
    ``linkConnected && !disconnect_requested``.  That lets a stale settings
    latch keep Device Space on "disconnected" while the Bluetooth stack has a
    live HOGP link.  Make the real Bluetooth profile state authoritative:
    connected when either the mirror or the actual profile says so, and never
    force it to false from the latch.
    """
    path = root / "com" / "codex" / "colorosporttuning" / "HookUtils.smali"
    text = path.read_text(encoding="utf-8")
    old = """    .line 171
    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->linkConnected(Landroid/content/Context;)I

    move-result v0

    const/4 v1, 0x1

    const/4 v2, 0x0

    if-lez v0, :cond_2f

    move v0, v1

    goto :goto_30

    :cond_2f
    move v0, v2

    .line 172
    :goto_30
    :try_start_30
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object v3

    const-string v5, "lenovo_pen_disconnect_requested"

    invoke-static {v3, v5, v2}, Landroid/provider/Settings$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I

    move-result v3
    :try_end_3a
    .catchall {:try_start_30 .. :try_end_3a} :catchall_3d

    if-ne v3, v1, :cond_3d

    move v0, v2

    :catchall_3d
    :cond_3d
    move v3, v0
"""
    new = """    .line 171
    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->linkConnected(Landroid/content/Context;)I

    move-result v0

    const/4 v1, 0x1

    const/4 v2, 0x0

    if-lez v0, :cond_2f

    move v0, v1

    goto :goto_30

    :cond_2f
    invoke-static {p0, v4}, Lcom/aclaniakea/colorosporttuning/HookUtils;->bluetoothConnected(Landroid/content/Context;Ljava/lang/String;)Z

    move-result v0

    .line 172
    :goto_30
    move v3, v0
"""
    if old not in text:
        raise ValueError("state() disconnect-latch block not found")
    text = text.replace(old, new)
    path.write_text(text, encoding="utf-8")


def patch_settings_connect_state(root: Path) -> None:
    """Push the real Bluetooth connect state to the OEM settings UI."""
    tools = Path(__file__).resolve().parent
    hooks_path = root / "com" / "codex" / "colorosporttuning" / "IpeManagerHooks.smali"
    text = hooks_path.read_text(encoding="utf-8")
    if "syncSettingsConnectState" not in text:
        text = text.rstrip() + "\n" + (tools / "smali" / "SYNC_CONNECT_STATE.smali").read_text(encoding="utf-8")
        hooks_path.write_text(text, encoding="utf-8")

    receiver = root / "com" / "codex" / "colorosporttuning" / "IpeManagerHooks$17.smali"
    text = receiver.read_text(encoding="utf-8")
    old = (
        "    invoke-static {p0, p2}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->access$1600(Landroid/content/Context;Landroid/content/Intent;)V\n"
        "\n"
        "    return-void"
    )
    new = (
        "    invoke-static {p0, p2}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->access$1600(Landroid/content/Context;Landroid/content/Intent;)V\n"
        "\n"
        "    invoke-static {p0, p2}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->syncSettingsConnectState(Landroid/content/Context;Landroid/content/Intent;)V\n"
        "\n"
        "    return-void"
    )
    if old not in text:
        raise ValueError("receiver notifySettingsPage call site not found")
    receiver.write_text(text.replace(old, new), encoding="utf-8")


def patch_mydevices_battery(root: Path) -> None:
    """Override the mydevices card/detail battery info with the pen truth."""
    tools = Path(__file__).resolve().parent
    package = root / "com" / "codex" / "colorosporttuning"
    for name in ("MyDevicesHooks$6", "MyDevicesHooks$7", "MyDevicesHooks$8", "MyDevicesHooks$10", "CardBatteryHooks", "CardBatteryHooks$1", "CardBatteryHooks$2", "CardBatteryHooks$3", "CardBatteryHooks$4", "CardBatteryHooks$5", "CardBatteryHooks$6", "CardBatteryHooks$7"):
        (package / (name + ".smali")).write_text(
            (tools / "smali" / (name + ".smali")).read_text(encoding="utf-8"),
            encoding="utf-8",
        )
    hooks_path = package / "MyDevicesHooks.smali"
    text = hooks_path.read_text(encoding="utf-8")
    old = (
        "    .line 23\n"
        "    iget-object p0, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;\n"
        "\n"
        "    new-instance v0, Lcom/aclaniakea/colorosporttuning/MyDevicesHooks$3;"
    )
    new = (
        "    .line 23\n"
        "    iget-object v0, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;\n"
        "\n"
        "    new-instance v1, Lcom/aclaniakea/colorosporttuning/MyDevicesHooks$6;\n"
        "\n"
        "    invoke-direct {v1}, Lcom/aclaniakea/colorosporttuning/MyDevicesHooks$6;-><init>()V\n"
        "\n"
        "    const-string v2, \"com.heytap.mydevices.core.bluetooth.OplusBluetoothAdapterWrapper\"\n"
        "\n"
        "    const-string v3, \"o\"\n"
        "\n"
        "    invoke-static {v0, v2, v3, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I\n"
        "\n"
        "    iget-object v0, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;\n"
        "\n"
        "    new-instance v1, Lcom/aclaniakea/colorosporttuning/MyDevicesHooks$7;\n"
        "\n"
        "    invoke-direct {v1}, Lcom/aclaniakea/colorosporttuning/MyDevicesHooks$7;-><init>()V\n"
        "\n"
        "    const-string v2, \"com.heytap.mydevices.core.bluetooth.OplusBluetoothAdapterWrapper\"\n"
        "\n"
        "    const-string v3, \"P\"\n"
        "\n"
        "    invoke-static {v0, v2, v3, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I\n"
        "\n"
        "    iget-object v0, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;\n"
        "\n"
        "    new-instance v1, Lcom/aclaniakea/colorosporttuning/MyDevicesHooks$8;\n"
        "\n"
        "    invoke-direct {v1}, Lcom/aclaniakea/colorosporttuning/MyDevicesHooks$8;-><init>()V\n"
        "\n"
        "    const-string v2, \"com.heytap.mydevices.core.device.BlueToothScannerImpl\"\n"
        "\n"
        "    const-string v3, \"onBatteryLevelChanged\"\n"
        "\n"
        "    invoke-static {v0, v2, v3, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I\n"
        "\n"
        "    iget-object v0, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;\n"
        "\n"
        "    new-instance v1, Lcom/aclaniakea/colorosporttuning/MyDevicesHooks$10;\n"
        "\n"
        "    invoke-direct {v1}, Lcom/aclaniakea/colorosporttuning/MyDevicesHooks$10;-><init>()V\n"
        "\n"
        "    const-string v2, \"com.oplus.mydevices.quickapp.homecard.view.BatteryLottieView\"\n"
        "\n"
        "    const-string v3, \"setBatteryInfo\"\n"
        "\n"
        "    invoke-static {v0, v2, v3, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I\n"
        "\n"
        "    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->install(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V\n"
        "\n"
        "    iget-object p0, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;\n"
        "\n"
        "    new-instance v0, Lcom/aclaniakea/colorosporttuning/MyDevicesHooks$3;"
    )
    if old not in text:
        raise ValueError("MyDevicesHooks.install registration point not found")
    hooks_path.write_text(text.replace(old, new), encoding="utf-8")

def patch_panel_connect_state(root: Path) -> None:
    """Force the IPe pencil panel to display the real Bluetooth link state."""
    tools = Path(__file__).resolve().parent
    package = root / "com" / "codex" / "colorosporttuning"
    (package / "IpeManagerHooks$46.smali").write_text(
        (tools / "smali" / "IpeManagerHooks$46.smali").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    hooks_path = package / "IpeManagerHooks.smali"
    text = hooks_path.read_text(encoding="utf-8")
    old = (
        "    const-string v3, \"notifyChargingState\"\n"
        "\n"
        "    invoke-static {v0, v2, v3, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I\n"
    )
    new = old + (
        "\n"
        "    iget-object v0, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;\n"
        "\n"
        "    new-instance v1, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$46;\n"
        "\n"
        "    invoke-direct {v1}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$46;-><init>()V\n"
        "\n"
        "    const-string v2, \"com.oplus.ipemanager.btadsorb.pencilPanel.fragment.k1\"\n"
        "\n"
        "    const-string v3, \"b\"\n"
        "\n"
        "    invoke-static {v0, v2, v3, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I\n"
    )
    if old not in text:
        raise ValueError("installMyDevicesStateBridge notifyChargingState registration not found")
    hooks_path.write_text(text.replace(old, new, 1), encoding="utf-8")


def patch_pen_input_gate(root: Path) -> None:
    """Gate the NVTCapacitivePen input device on the real BT link state."""
    tools = Path(__file__).resolve().parent
    package = root / "com" / "codex" / "colorosporttuning"
    (package / "PenInputGate.smali").write_text(
        (tools / "smali" / "PenInputGate.smali").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    hooks_path = package / "SystemStylusHooks.smali"
    text = hooks_path.read_text(encoding="utf-8")
    old = """    invoke-static {p0, v0, v1}, Lcom/aclaniakea/colorosporttuning/PenHapticGatt;->startWriting(Landroid/content/Context;Ljava/lang/String;I)V

    new-instance p0, Ljava/lang/StringBuilder;"""
    new = """    invoke-static {p0, v0, v1}, Lcom/aclaniakea/colorosporttuning/PenHapticGatt;->startWriting(Landroid/content/Context;Ljava/lang/String;I)V

    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/SystemStylusHooks;->haptic(Landroid/content/Context;)V

    new-instance p0, Ljava/lang/StringBuilder;"""
    if old not in text:
        raise ValueError("onMotion startWriting anchor not found")
    hooks_path.write_text(text.replace(old, new, 1), encoding="utf-8")

    runnable = package / "SystemStylusHooks$1.smali"
    text = runnable.read_text(encoding="utf-8")
    old = (
        "    if-eqz v0, :cond_9\n"
        "\n"
        "    # invokes: Lcom/aclaniakea/colorosporttuning/SystemStylusHooks;->pollPenHall(Landroid/content/Context;)V\n"
        "    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/SystemStylusHooks;->access$100(Landroid/content/Context;)V\n"
    )
    new = old + (
        "\n"
        "    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/PenInputGate;->sync(Landroid/content/Context;)V\n"
    )
    if old not in text:
        raise ValueError("SystemStylusHooks$1.pollPenHall call site not found")
    runnable.write_text(text.replace(old, new), encoding="utf-8")




def patch_refresh_policy(root: Path) -> None:
    """Only lock 120 Hz while the pen is actually writing; otherwise allow 144."""
    tools = Path(__file__).resolve().parent
    package = root / "com" / "codex" / "colorosporttuning"
    (package / "RefreshRateLock.smali").write_text(
        (tools / "smali" / "RefreshRateLock.smali").read_text(encoding="utf-8"),
        encoding="utf-8",
    )

    hooks_path = package / "SystemStylusHooks.smali"
    text = hooks_path.read_text(encoding="utf-8")

    # 1) setRefreshActive: apply min/peak_refresh_rate only when the state
    # actually changes (dedup gate), never on every pen motion event.
    old = """    :try_start_25
    sput-boolean p1, Lcom/aclaniakea/colorosporttuning/SystemStylusHooks;->refreshActive:Z
    :try_end_27"""
    new = """    :try_start_25
    sput-boolean p1, Lcom/aclaniakea/colorosporttuning/SystemStylusHooks;->refreshActive:Z

    invoke-static {p0, p1}, Lcom/aclaniakea/colorosporttuning/RefreshRateLock;->apply(Landroid/content/Context;Z)V
    :try_end_27"""
    if old not in text:
        raise ValueError("setRefreshActive state-change anchor not found")
    text = text.replace(old, new, 1)

    # 2) applyPenHall: undocked + screenOn must NOT lock 120 (only writing does)
    old = """    sget-boolean p2, Lcom/aclaniakea/colorosporttuning/SystemStylusHooks;->screenOn:Z

    if-eqz p2, :cond_3d

    invoke-static {p0, v2}, Lcom/aclaniakea/colorosporttuning/SystemStylusHooks;->setRefreshActive(Landroid/content/Context;Z)V
"""
    new = """    sget-boolean p2, Lcom/aclaniakea/colorosporttuning/SystemStylusHooks;->screenOn:Z

    if-eqz p2, :cond_3d

    invoke-static {p0, v3}, Lcom/aclaniakea/colorosporttuning/SystemStylusHooks;->setRefreshActive(Landroid/content/Context;Z)V
"""
    if old not in text:
        raise ValueError("applyPenHall screenOn anchor not found")
    text = text.replace(old, new, 1)

    # 3) stopWriting: release the 120 Hz lock when the pen stops writing
    old = """    const-string v1, "stop writing haptic"

    invoke-static {v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V
    :try_end_19"""
    new = """    const-string v1, "stop writing haptic"

    invoke-static {v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    sget-object v1, Lcom/aclaniakea/colorosporttuning/SystemStylusHooks;->refreshContext:Landroid/content/Context;

    if-eqz v1, :cond_norelease

    const/4 v2, 0x0

    invoke-static {v1, v2}, Lcom/aclaniakea/colorosporttuning/SystemStylusHooks;->setRefreshActive(Landroid/content/Context;Z)V

    :cond_norelease
    :try_end_19"""
    if old not in text:
        raise ValueError("stopWriting anchor not found")
    text = text.replace(old, new, 1)

    hooks_path.write_text(text, encoding="utf-8")

def patch_smali(root: Path) -> None:
    package = root / "com" / "codex" / "colorosporttuning"

    repair_object_static_gets(root)
    patch_state_real_bt(root)
    patch_settings_connect_state(root)
    patch_mydevices_battery(root)
    patch_panel_connect_state(root)
    patch_pen_input_gate(root)
    patch_is_known(package / "IpeManagerHooks.smali")
    patch_device_card_type(package / "IpeManagerHooks$12.smali")
    patch_device_card_proxy_type(package / "IpeManagerHooks$13.smali")
    add_pencil_panel_control_bridge(root)
    add_card_battery_bridge(root)
    patch_handoff_charging(package / "IpeManagerHooks.smali")
    patch_publish_hardware_charging(package / "IpeManagerHooks.smali")
    patch_connect_policy_restore(package / "IpeManagerHooks.smali")
    patch_panel_disconnect_acl(package / "IpeManagerHooks$3.smali")
    patch_connect_user_latch(package / "IpeManagerHooks$3.smali")
    patch_connect_restore_policy(package / "IpeManagerHooks$3.smali")
    patch_disconnect_user_latch_and_mac(package / "IpeManagerHooks$3.smali")
    patch_pen_state_charging(package / "PenStateStore.smali")

    replace_once(
        package / "IpeManagerHooks.smali",
        """    const-string v1, \"onResume\"

    invoke-static {p0, v3, v1, v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I
""",
        """    const-string v1, \"onCreatePreferences\"

    invoke-static {p0, v3, v1, v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I

    const-string v1, \"onResume\"

    invoke-static {p0, v3, v1, v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I
""",
    )

    replace_once(
        package / "PenStateStore.smali",
        """    .line 58
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object v2

    const-string v3, \"lenovo_pen_refresh_active\"

    invoke-static {v2, v3, v1}, Landroid/provider/Settings$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I

    move-result v2
""",
        """    .line 58
    iget-boolean v2, p1, Lcom/aclaniakea/colorosporttuning/PenState;->connected:Z
""",
    )

    replace_once(
        package / "SystemStylusHooks.smali",
        """    .line 211
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object v3

    const-string v4, \"settings_enable_oppo_pencil\"

    invoke-static {v3, v4, p1}, Landroid/provider/Settings$Global;->putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z

    .line 212
    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object p0

    const-string v3, \"ipe_pencil_present\"

    invoke-static {p0, v3, p1}, Landroid/provider/Settings$Global;->putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z

""",
        "",
    )

    replace_once(
        package / "IpeManagerHooks.smali",
        """    invoke-virtual {p0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object v1

    const-string v2, \"lenovo_pen_refresh_active\"

    invoke-static {v1, v2, v3}, Landroid/provider/Settings$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I

    move-result v1
""",
        """    iget-boolean v1, v0, Lcom/aclaniakea/colorosporttuning/PenState;->connected:Z
""",
    )


def classpath() -> str:
    paths = [TOOLS_DIR / name for name in JARS]
    missing = [str(path) for path in paths if not path.is_file()]
    if missing:
        raise FileNotFoundError("missing Smali tool jars: " + ", ".join(missing))
    return os.pathsep.join(str(path) for path in paths)


def run_java(*args: str) -> None:
    subprocess.run(["java", "-cp", classpath(), *args], check=True)


def copy_entry(out: zipfile.ZipFile, source: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = source.getinfo(name)
    copied = zipfile.ZipInfo(name, date_time=info.date_time)
    copied.compress_type = zipfile.ZIP_STORED
    copied.external_attr = info.external_attr
    copied.create_system = info.create_system
    out.writestr(copied, data)


def build(input_apk: Path, output_apk: Path) -> None:
    actual_sha = hashlib.sha256(input_apk.read_bytes()).hexdigest()
    if actual_sha != BASE_APK_SHA256:
        raise ValueError(f"unexpected input APK sha256={actual_sha}")

    with tempfile.TemporaryDirectory(prefix="lenovo-pen-hook-v68-") as temp:
        temp_path = Path(temp)
        dex_path = temp_path / "classes.dex"
        smali_path = temp_path / "smali"
        rebuilt_dex = temp_path / "rebuilt.dex"
        with zipfile.ZipFile(input_apk) as source:
            dex_path.write_bytes(source.read("classes.dex"))

        run_java("org.jf.baksmali.Main", "disassemble", str(dex_path), "-o", str(smali_path))
        patch_smali(smali_path)
        run_java("org.jf.smali.Main", "assemble", str(smali_path), "-o", str(rebuilt_dex))
        patched_dex = rebuilt_dex.read_bytes()
        if not patched_dex.startswith(b"dex\n"):
            raise ValueError("Smali assembler did not produce a DEX file")

        output_apk.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(input_apk) as source, zipfile.ZipFile(
            output_apk, "w", compression=zipfile.ZIP_STORED
        ) as out:
            for name in source.namelist():
                if name.upper().startswith("META-INF/"):
                    continue
                copy_entry(out, source, name, patched_dex if name == "classes.dex" else source.read(name))
            scope_info = zipfile.ZipInfo("META-INF/xposed/scope.list", date_time=(2026, 8, 7, 0, 0, 0))
            scope_info.compress_type = zipfile.ZIP_STORED
            scope_lines = [
                "com.oplus.ipemanager",
                "com.heytap.mydevices",
                "system",
                "com.coloros.note",
                "com.oplus.screenshot",
                "com.oplus.wirelesssettings",
                "com.oplus.healthservice",
                "com.oplus.exsystemservice",
            ]
            out.writestr(scope_info, b"\xef\xbb\xbf" + "\r\n".join(scope_lines).encode("utf-8") + b"\r\n")

    print(f"built {output_apk}")
    print(f"sha256 {hashlib.sha256(output_apk.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(f"usage: {sys.argv[0]} INPUT_APK OUTPUT_APK")
    build(Path(sys.argv[1]), Path(sys.argv[2]))
