package com.oplus.stdid;

import android.app.Service;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Minimal compatibility implementation of the stock StdID binder protocol.
 * It exists only for ports where the signed Oplus StdID package is absent.
 */
public final class IdentifyService extends Service {
    private static final String DESCRIPTOR = "com.oplus.stdid.IStdID";

    @Override public IBinder onBind(android.content.Intent intent) {
        return new StdIdBinder(getApplicationContext());
    }

    private static final class StdIdBinder extends Binder {
        private final Context context;

        StdIdBinder(Context context) {
            this.context = context;
            attachInterface(null, DESCRIPTOR);
        }

        @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != 1) return superOnTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            String caller = data.readString();
            data.readString(); // original certificate argument; verified by the caller-side platform path
            String type = data.readString();
            reply.writeNoException();
            reply.writeString(identifier(type, caller));
            return true;
        }

        private boolean superOnTransact(int code, Parcel data, Parcel reply, int flags) {
            try { return super.onTransact(code, data, reply, flags); }
            catch (android.os.RemoteException ignored) { return false; }
        }

        private String identifier(String type, String caller) {
            String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null || androidId.isEmpty()) return "";
            String scope = type == null ? "" : type;
            if ("APID".equals(scope)) scope += "|" + (caller == null ? "" : caller);
            return digest("ColorOS-StdID-Compat-v1|" + scope + "|" + androidId);
        }

        private static String digest(String input) {
            try {
                byte[] bytes = MessageDigest.getInstance("SHA-256")
                        .digest(input.getBytes(StandardCharsets.UTF_8));
                StringBuilder result = new StringBuilder(64);
                for (byte item : bytes) result.append(String.format("%02x", item & 0xff));
                return result.toString();
            } catch (Exception ignored) { return ""; }
        }
    }
}
