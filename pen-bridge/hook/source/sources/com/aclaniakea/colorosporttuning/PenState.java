package com.aclaniakea.colorosporttuning;

/* loaded from: classes.dex */
final class PenState {
    final String address;
    final int battery;
    final int charging;
    final boolean connected;
    final String firmware;
    final String hardware;
    final String name;
    final String serial;
    final String source;
    final String type;
    final long updatedAt;

    PenState(boolean z, String str, String str2, int i, int i2, String str3, String str4, String str5, String str6, String str7, long j) {
        this.connected = z;
        this.address = s(str);
        this.name = s(str2);
        this.battery = i < 0 ? -1 : Math.min(100, i);
        this.charging = Math.max(0, i2);
        this.type = s(str3);
        this.firmware = s(str4);
        this.hardware = s(str5);
        this.serial = s(str6);
        this.source = s(str7);
        this.updatedAt = j;
    }

    int connectState() {
        return this.connected ? 2 : 0;
    }

    String macNoColon() {
        return this.address.replace(":", "");
    }

    static String s(String str) {
        return str == null ? "" : str.trim();
    }
}
