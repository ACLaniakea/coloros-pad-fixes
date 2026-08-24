package com.aclaniakea.colorosporttuning;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/* loaded from: classes.dex */
final class PenStateStore {
    private static final String PREF = "pen_state";

    /* JADX WARN: Unreachable blocks removed: 1, instructions: 1 */
    static PenState read(Context context) {
        SharedPreferences sharedPreferences;
        StringBuilder sb;
        String string;
        try {
            sharedPreferences = context.getSharedPreferences(PREF, 0);
        } catch (Throwable unused) {
            sharedPreferences = null;
        }
        String string2 = HookUtils.penAddress(context);
        String string3 = Settings.Global.getString(context.getContentResolver(), "ipe_pencil_bt_device_name");
        if (sharedPreferences != null) {
            if (string2 == null || string2.length() == 0) {
                string2 = sharedPreferences.getString("address", string2);
            }
        }
        String str = string2;
        if (sharedPreferences != null) {
            string3 = sharedPreferences.getString("name", string3);
        }
        // Device Space's title is the stock Bluetooth alias, not the firmware
        // hardware-revision field. Hall broadcasts used to overwrite the Pro
        // alias with the generic hardware name after every magnetic attach.
        try {
            if (str != null && !str.isEmpty()) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                BluetoothDevice device = adapter == null ? null : adapter.getRemoteDevice(str);
                String alias = device == null ? null : device.getAlias();
                if (alias == null || alias.trim().isEmpty()) {
                    alias = device == null ? null : device.getName();
                }
                if (alias != null && !alias.trim().isEmpty()) {
                    string3 = alias;
                }
            }
        } catch (Throwable unused) {
        }
        String str2 = string3;
        boolean z = HookUtils.linkConnected(context) > 0;
        try {
            if (Settings.Global.getInt(context.getContentResolver(), "lenovo_pen_disconnect_requested", 0) == 1) {
                z = false;
            }
        } catch (Throwable unused2) {
        }
        boolean z2 = z;
        int iHardwareBattery = HookUtils.hardwareBattery(context);
        if (iHardwareBattery < 0 && z2) {
            int iLastValidBattery = HookUtils.lastValidBattery(context);
            if (iLastValidBattery >= 0) {
                iHardwareBattery = iLastValidBattery;
            }
        }
        int i = iHardwareBattery;
        // A magnetic pen cannot be charging while both Hall sensors report
        // detached. Do not preserve an OEM/provider value from the previous
        // dock session in the process-local SharedPreferences cache.
        int iStoredCharging = sharedPreferences == null ? Settings.Global.getInt(context.getContentResolver(), "ipe_pencil_charging_state", 0) : sharedPreferences.getInt("charging", Settings.Global.getInt(context.getContentResolver(), "ipe_pencil_charging_state", 0));
        int i2 = HookUtils.effectiveCharging(context, iStoredCharging);
        String string4 = sharedPreferences != null ? sharedPreferences.getString("type", "SECOND_GENERATION_PENCIL_LITE") : "SECOND_GENERATION_PENCIL_LITE";
        String string5 = Settings.Global.getString(context.getContentResolver(), "lenovo_pen_firmware");
        if (string5 == null || string5.trim().isEmpty() || "1.0.0".equals(string5)) {
            String localFirmware = sharedPreferences != null
                    ? sharedPreferences.getString("firmware", "1.0.0") : "1.0.0";
            if (localFirmware != null && !localFirmware.trim().isEmpty()) {
                string5 = localFirmware;
            }
        }
        if (string5 == null || string5.trim().isEmpty()) {
            string5 = "1.0.0";
        }
        String string6 = sharedPreferences != null ? sharedPreferences.getString("hardware", "Lenovo Tab Pen") : "Lenovo Tab Pen";
        if (sharedPreferences == null) {
            sb = new StringBuilder("LENOVO-");
            string = sb.append(str != null ? str.replace(":", "") : "PEN").toString();
        } else {
            sb = new StringBuilder("LENOVO-");
            string = sharedPreferences.getString("serial", sb.append(str != null ? str.replace(":", "") : "PEN").toString());
        }
        return new PenState(z2, str, str2, i, i2, string4, string5, string6, string, sharedPreferences != null ? sharedPreferences.getString("source", "settings") : "settings", sharedPreferences != null ? sharedPreferences.getLong("updated", 0L) : 0L);
    }

    static void write(Context context, PenState penState) {
        if (penState.battery >= 0 && isHardwareSource(penState.source)) {
            HookUtils.markHardwareBattery(context, penState.battery);
        }
        int effectiveCharging = HookUtils.effectiveCharging(context, penState.charging);
        if (effectiveCharging < 0) {
            effectiveCharging = 0;
        }
        int iStoredBattery = penState.battery;
        if (iStoredBattery < 0 && penState.connected) {
            iStoredBattery = HookUtils.lastValidBattery(context);
        }
        try {
            SharedPreferences.Editor editorPutLong = context.getSharedPreferences(PREF, 0).edit().putBoolean("connected", penState.connected).putString("address", penState.address).putString("name", penState.name).putInt("charging", effectiveCharging).putString("type", penState.type).putString("firmware", penState.firmware).putString("hardware", penState.hardware).putString("serial", penState.serial).putString("source", penState.source).putLong("updated", penState.updatedAt);
            if (iStoredBattery >= 0) {
                editorPutLong.putInt("battery", iStoredBattery).putInt("last_valid_battery", iStoredBattery);
            } else {
                editorPutLong.putInt("battery", -1);
            }
            editorPutLong.apply();
        } catch (Throwable unused) {
        }
        try {
            Settings.Global.putString(context.getContentResolver(), "ipe_pencil_mac_addr", penState.address);
            Settings.Global.putString(context.getContentResolver(), "ipe_pencil_bt_device_name", penState.name);
            Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_link_connected", penState.connected ? 1 : 0);
            if (iStoredBattery >= 0) {
                Settings.Global.putInt(context.getContentResolver(), "ipe_pencil_battery_level", iStoredBattery);
                Settings.Global.putInt(context.getContentResolver(), "lenovo_pen_last_valid_battery", iStoredBattery);
            } else if (!penState.connected) {
                Settings.Global.putInt(context.getContentResolver(), "ipe_pencil_battery_level", -1);
            }
            Settings.Global.putInt(context.getContentResolver(), "ipe_pencil_charging_state", effectiveCharging);
            HookUtils.setIpePreferenceInt(context, "pencil_sp_charging_state", effectiveCharging);
            if (iStoredBattery >= 0) {
                HookUtils.setIpePreferenceInt(context, "pencil_sp_battery_level", iStoredBattery);
            } else if (!penState.connected) {
                HookUtils.setIpePreferenceInt(context, "pencil_sp_battery_level", -1);
            }
            // Device Space uses these two keys to decide whether the pen card
            // and its detail activity exist. They describe the live Bluetooth
            // link, not the adaptive refresh policy.
            int i = penState.connected ? 1 : 0;
            Settings.Global.putInt(context.getContentResolver(), "ipe_pencil_present", i);
            Settings.Global.putString(context.getContentResolver(), "lenovo_pen_type", penState.type);
            Settings.Global.putString(context.getContentResolver(), "lenovo_pen_firmware", penState.firmware);
            Settings.Global.putString(context.getContentResolver(), "ipe_pencil_fw", penState.firmware);
            HookUtils.setIpePreferenceString(context, "pencil_sp_fw_version", penState.firmware);
            Settings.Global.putString(context.getContentResolver(), "lenovo_pen_hardware", penState.hardware);
            Settings.Global.putString(context.getContentResolver(), "lenovo_pen_serial", penState.serial);
            Settings.Global.putInt(context.getContentResolver(), "stylus_handwriting_enabled", 1);
            Settings.Secure.putInt(context.getContentResolver(), "stylus_handwriting_enabled", 1);
            for (String str : PenBridgeConstants.CONNECT_KEYS) {
                Settings.Global.putInt(context.getContentResolver(), str, penState.connectState());
            }
        } catch (Throwable unused2) {
        }
    }

    private static boolean isHardwareSource(String str) {
        return "kernel_pen_framework".equals(str) || "ipe_ble_gatt".equals(str) || "ipe_ble_gatt_charge".equals(str);
    }

    private PenStateStore() {
    }
}
