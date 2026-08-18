package com.aclaniakea.dolbybridge;

import android.app.Service;
import android.content.Intent;
import android.media.audiofx.AudioEffect;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Dolby control-service bridge for the ColorOS port.
 *
 * The port Settings APK binds to {@code com.oplus.dolbyeffect.controlservice.START}
 * (implementing {@code com.oplus.audio.effectcenter.IOplusEffectService}) to drive the
 * Dolby Atmos UI. The port ROM ships the vendor DMS/DAX HAL but no control service, so
 * this service fills that gap: it attaches to the DAX effect
 * (9d4921da-8225-4f29-aefa-39537a04bcaa) via AudioEffect and maps the OPlus AIDL
 * surface onto the DAX parameters verified on TB710FU:
 *   param 5 : Dolby on/off (int)
 *   param 6 : current profile name (string, e.g. "Dynamic")
 *   param 4 : active endpoint (string)
 */
public class DolbyBridgeService extends Service {
    static final String TAG = "DolbyBridge";
    static final String DESCRIPTOR = "com.oplus.audio.effectcenter.IOplusEffectService";
    static final String CALLBACK_DESCRIPTOR = "com.oplus.audio.effectcenter.IEffectServiceCallback";
    static final UUID DAX = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa");

    static final int DOLBY_ONOFF_ID = 5;
    static final int PROFILE_NAME_ID = 6;
    static final int ENDPOINT_ID = 4;

    // OPlus scene id -> DAX profile id (dax-default.xml)
    static final int[] SCENE_TO_PROFILE = {0, 2, 1, 8}; // 智能, 音乐, 影院, 游戏
    static final String[] PROFILE_TO_SCENE = {"0", "2", "1", "3", "4", "0", "0", "0", "8"}; // by DAX id

    AudioEffect mFx;
    Method mSetP, mGetP;
    final List<IBinder> mCallbacks = new ArrayList<>();
    volatile boolean mEnabled = true;
    volatile int mScene = 0;

    final Binder mStub = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code >= 1 && code <= 16777215) data.enforceInterface(DESCRIPTOR);
            switch (code) {
                case 1: // setEnabled(boolean)
                    setEnabled(data.readInt() != 0);
                    reply.writeNoException();
                    return true;
                case 2: // getEnabled() -> boolean
                    reply.writeNoException();
                    reply.writeInt(getEnabled() ? 1 : 0);
                    return true;
                case 3: // setMusicIeqPreset(int)
                    setMusicIeqPreset(data.readInt());
                    reply.writeNoException();
                    return true;
                case 4: // getMusicIeqPreset() -> int
                    reply.writeNoException();
                    reply.writeInt(getMusicIeqPreset());
                    return true;
                case 5: // setCustomGeqBandGains(int[], boolean, int)
                    reply.writeNoException();
                    return true;
                case 6: // getEffectCategory() -> int
                    reply.writeNoException();
                    reply.writeInt(1);
                    return true;
                case 7: // setMusicGeqBandGains(int[])
                    reply.writeNoException();
                    return true;
                case 8: // getMusicGeqBandGains() -> int[]
                    reply.writeNoException();
                    reply.writeIntArray(new int[10]);
                    return true;
                case 9: // isBtDeviceSupported(String) -> boolean
                    data.readString();
                    reply.writeNoException();
                    reply.writeInt(0);
                    return true;
                case 10: // setParameter(String, String)
                    setParameter(data.readString(), data.readString());
                    reply.writeNoException();
                    return true;
                case 11: // getParameters(String) -> String
                    reply.writeNoException();
                    reply.writeString(getParameters(data.readString()));
                    return true;
                case 12: // registerCallback(IEffectServiceCallback)
                    registerCb(data.readStrongBinder());
                    reply.writeNoException();
                    return true;
                case 13: // unRegisterCallback(IEffectServiceCallback)
                    unregisterCb(data.readStrongBinder());
                    reply.writeNoException();
                    return true;
                case 14: // setEffectCategory(int)
                    setEffectCategory(data.readInt());
                    reply.writeNoException();
                    return true;
                case 15: // getCustomGeqBandGains(boolean) -> int[]
                    reply.writeNoException();
                    reply.writeIntArray(new int[10]);
                    return true;
                case 16: // getCustomPresetEQType(boolean) -> int
                    reply.writeNoException();
                    reply.writeInt(0);
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Constructor<AudioEffect> ctor = AudioEffect.class.getDeclaredConstructor(UUID.class, UUID.class, int.class, int.class);
            ctor.setAccessible(true);
            mFx = ctor.newInstance(DAX, DAX, 100, 0);
            mSetP = AudioEffect.class.getMethod("setParameter", byte[].class, byte[].class);
            mGetP = AudioEffect.class.getMethod("getParameter", byte[].class, byte[].class);
            mEnabled = daxGetInt(DOLBY_ONOFF_ID) != 0;
            mScene = sceneFromProfile(daxGetStr(PROFILE_NAME_ID));
            Log.i(TAG, "DAX attached, enabled=" + mEnabled + " scene=" + mScene);
        } catch (Throwable t) {
            Log.e(TAG, "DAX attach failed", t);
            mFx = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mStub;
    }

    @Override
    public void onDestroy() {
        synchronized (mCallbacks) { mCallbacks.clear(); }
        if (mFx != null) {
            try { mFx.release(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    // ---------- IOplusEffectService surface ----------

    synchronized void setEnabled(boolean on) {
        mEnabled = on;
        if (mFx != null) {
            try {
                mSetP.invoke(mFx, le(DOLBY_ONOFF_ID), new byte[]{0,0,0,0,(byte)(on?1:0),0,0,0});
            } catch (Throwable t) { Log.w(TAG, "setEnabled failed", t); }
        }
        notifyCallbacks(1); // EffectServiceStatusChangeCallback
    }

    boolean getEnabled() {
        return mEnabled;
    }

    synchronized void setMusicIeqPreset(int scene) {
        mScene = scene;
        if (scene >= 0 && scene < SCENE_TO_PROFILE.length && mFx != null) {
            trySetProfile(SCENE_TO_PROFILE[scene]);
        }
        notifyCallbacks(4); // EffectServiceProfileCallback
    }

    int getMusicIeqPreset() {
        return mScene;
    }

    void setEffectCategory(int cat) {
        // 1 = Dolby. No privileged settings writes from a normal-app service.
    }

    void setParameter(String key, String value) {
        // EQ toggle persisted by the Settings side; nothing privileged to write here.
    }

    String getParameters(String key) {
        if ("dolby_get_dolby_state".equals(key)) return "state_" + (mEnabled ? 1 : 0);
        if ("dolby_get_dolby_profile".equals(key)) return "profile_" + mScene;
        if ("dolby_get_device_status".equals(key)) return "0";
        if ("dolby_geq_on_off".equals(key)) return "false";
        return "";
    }

    // ---------- DAX helpers ----------

    static byte[] le(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    static int rdle(byte[] a, int off) {
        return (a[off] & 0xff) | ((a[off+1] & 0xff) << 8) | ((a[off+2] & 0xff) << 16) | ((a[off+3] & 0xff) << 24);
    }

    int daxGetInt(int id) {
        if (mGetP == null) return 0;
        try {
            byte[] v = new byte[8];
            mGetP.invoke(mFx, le(id), v);
            return rdle(v, 0);
        } catch (Throwable t) { return 0; }
    }

    String daxGetStr(int id) {
        if (mGetP == null) return "";
        try {
            byte[] v = new byte[64];
            Object rc = mGetP.invoke(mFx, le(id), v);
            int n = rc instanceof Integer ? (Integer) rc : 0;
            if (n > 0 && n <= v.length) return new String(v, 0, n, StandardCharsets.ISO_8859_1).trim();
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * Profile switch. The DAX small-id space exposes no validated profile selector on
     * TB710FU, and blind writes to unknown ids crash the DMS/audio HAL, so we only
     * persist the selection and let the UI reflect it. (Bottom-level DMS profile
     * application is a follow-up once a safe param is identified.)
     */
    void trySetProfile(int daxProfile) {
        Log.i(TAG, "scene switch persisted (scene=" + mScene + ", dax=" + daxProfile + ")");
    }

    static int daxProfileNameToId(String name) {
        if ("Movie".equals(name)) return 1;
        if ("Music".equals(name)) return 2;
        if ("Voice".equals(name)) return 3;
        if ("SpatialAudio".equals(name)) return 4;
        if ("Game".equals(name)) return 8;
        return 0; // Dynamic / unknown
    }

    static int sceneFromProfile(String name) {
        int pid = daxProfileNameToId(name);
        switch (pid) {
            case 1: return 2; // Movie -> 影院
            case 2: return 1; // Music -> 音乐
            case 8: return 3; // Game -> 游戏
            default: return 0; // Dynamic -> 智能
        }
    }

    void registerCb(IBinder b) {
        if (b == null) return;
        synchronized (mCallbacks) { if (!mCallbacks.contains(b)) mCallbacks.add(b); }
    }

    void unregisterCb(IBinder b) {
        synchronized (mCallbacks) { mCallbacks.remove(b); }
    }

    void notifyCallbacks(int cbCode) {
        List<IBinder> snapshot;
        synchronized (mCallbacks) { snapshot = new ArrayList<>(mCallbacks); }
        for (IBinder b : snapshot) {
            if (b == null) continue;
            try {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                data.writeInterfaceToken(CALLBACK_DESCRIPTOR);
                switch (cbCode) {
                    case 1: // EffectServiceStatusChangeCallback
                        b.transact(1, data, reply, 0);
                        break;
                    case 4: // EffectServiceProfileCallback(int)
                        data.writeInt(mScene);
                        b.transact(4, data, reply, 0);
                        break;
                }
                reply.recycle();
                data.recycle();
            } catch (Throwable ignored) {}
        }
    }
}
