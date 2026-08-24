package com.aclaniakea.colorosporttuning;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/* loaded from: classes.dex */
public final class PenStateProvider extends ContentProvider {
    private static final String[] COLS = {"connected", "address", "name", "battery", "charging", "pen_type", "firmware", "hardware", "serial", "source", "updated_at"};

    @Override // android.content.ContentProvider
    public int delete(Uri uri, String str, String[] strArr) {
        return 0;
    }

    @Override // android.content.ContentProvider
    public Uri insert(Uri uri, ContentValues contentValues) {
        return null;
    }

    @Override // android.content.ContentProvider
    public boolean onCreate() {
        return true;
    }

    @Override // android.content.ContentProvider
    public int update(Uri uri, ContentValues contentValues, String str, String[] strArr) {
        return 0;
    }

    @Override // android.content.ContentProvider
    public Cursor query(Uri uri, String[] strArr, String str, String[] strArr2, String str2) {
        PenState penState = PenStateStore.read(getContext());
        MatrixCursor matrixCursor = new MatrixCursor(COLS, 1);
        matrixCursor.addRow(new Object[]{Integer.valueOf(penState.connected ? 1 : 0), penState.address, penState.name, Integer.valueOf(penState.battery), Integer.valueOf(penState.charging), penState.type, penState.firmware, penState.hardware, penState.serial, penState.source, Long.valueOf(penState.updatedAt)});
        return matrixCursor;
    }

    @Override // android.content.ContentProvider
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.codex.penstate";
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        if (!"haptic".equals(method)) {
            result.putBoolean("accepted", false);
            return result;
        }
        int caller = Binder.getCallingUid();
        if (caller != 0 && caller != 1000 && caller != Process.myUid()) {
            result.putBoolean("accepted", false);
            return result;
        }
        Bundle data = extras == null ? Bundle.EMPTY : extras;
        Intent command = new Intent(PenBridgeReceiver.ACTION_HAPTIC_TRANSPORT)
                .putExtra("op", arg == null ? "" : arg)
                .putExtra("address", data.getString("address", ""))
                .putExtra("toolType", data.getInt("toolType", 2))
                .putExtra("enabled", data.getBoolean("enabled", true));
        PenBridgeReceiver.handleHapticTransport(getContext(), command);
        result.putBoolean("accepted", true);
        return result;
    }
}
