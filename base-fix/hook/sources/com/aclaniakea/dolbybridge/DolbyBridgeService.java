package com.aclaniakea.dolbybridge;

import android.app.Service;
import android.content.Intent;
import android.media.audiofx.AudioEffect;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.Handler;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;
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
    static final int OEM_PROFILE_PARAM = 0x100100bb;
    static final int OEM_EQ_GAINS_PARAM = 0x100100bc;
    static final int OEM_EQ_ENABLE_PARAM = 0x100100c9;
    static final String PREFS = "dolby_bridge_state";
    static final String PREF_SCENE = "scene";
    static final String PREF_EQ = "eq_preset";
    static final String PREF_MUSIC_GAINS = "music_gains";
    static final String PREF_CUSTOM_GAINS = "custom_gains";
    static final String PREF_GEQ = "geq_enabled";

    // OPlus scene id -> DAX profile id (dax-default.xml)
    static final int[] SCENE_TO_PROFILE = {0, 2, 1, 8}; // 智能, 音乐, 影院, 游戏
    static final String[] PROFILE_TO_SCENE = {"0", "2", "1", "3", "4", "0", "0", "0", "8"}; // by DAX id

    AudioEffect mFx;
    Method mSetP, mGetP;
    final List<IBinder> mCallbacks = new ArrayList<>();
    volatile boolean mEnabled = true;
    volatile int mScene = 0;
    volatile int mEqPreset = 0;
    volatile boolean mGeqEnabled = false;
    volatile int[] mMusicGains = new int[10];
    volatile int[] mCustomGains = new int[10];

    final Binder mStub = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code >= 1 && code <= 16777215) data.enforceInterface(DESCRIPTOR);
            Log.i(TAG, "txn=" + code + " flags=0x" + Integer.toHexString(flags));
            switch (code) {
                case 1: // setEnabled(boolean)
                    boolean enabled = data.readInt() != 0;
                    Log.i(TAG, "txn setEnabled(" + enabled + ")");
                    setEnabled(enabled);
                    reply.writeNoException();
                    return true;
                case 2: // getEnabled() -> boolean
                    reply.writeNoException();
                    reply.writeInt(getEnabled() ? 1 : 0);
                    return true;
                case 3: // setMusicIeqPreset(int)
                    int scene = data.readInt();
                    Log.i(TAG, "txn setMusicIeqPreset(" + scene + ")");
                    setMusicIeqPreset(scene);
                    reply.writeNoException();
                    return true;
                case 4: // getMusicIeqPreset() -> int
                    reply.writeNoException();
                    reply.writeInt(getMusicIeqPreset());
                    return true;
                case 5: // setCustomGeqBandGains(int[], boolean, int)
                    int[] custom = data.createIntArray();
                    boolean customEnabled = data.readInt() != 0;
                    int customDevice = data.readInt();
                    setCustomGeqBandGains(custom, customEnabled, customDevice);
                    reply.writeNoException();
                    return true;
                case 6: // getEffectCategory() -> int
                    reply.writeNoException();
                    reply.writeInt(1);
                    return true;
                case 7: // setMusicGeqBandGains(int[])
                    setMusicGeqBandGains(data.createIntArray());
                    reply.writeNoException();
                    return true;
                case 8: // getMusicGeqBandGains() -> int[]
                    reply.writeNoException();
                    reply.writeIntArray(mMusicGains);
                    return true;
                case 9: // isBtDeviceSupported(String) -> boolean
                    data.readString();
                    reply.writeNoException();
                    reply.writeInt(0);
                    return true;
                case 10: // setParameter(String, String)
                    String setKey = data.readString();
                    String setValue = data.readString();
                    Log.i(TAG, "txn setParameter key=" + setKey + " value=" + setValue);
                    setParameter(setKey, setValue);
                    reply.writeNoException();
                    return true;
                case 11: // getParameters(String) -> String
                    String getKey = data.readString();
                    reply.writeNoException();
                    String result = getParameters(getKey);
                    Log.i(TAG, "txn getParameters key=" + getKey + " -> " + result);
                    reply.writeString(result);
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
                    data.readInt();
                    reply.writeNoException();
                    reply.writeIntArray(mCustomGains);
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
            int savedScene = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_SCENE, -1);
            if (savedScene >= 0 && savedScene < SCENE_TO_PROFILE.length) mScene = savedScene;
            mEqPreset = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_EQ, 0);
            mGeqEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_GEQ, false);
            mMusicGains = readGains(PREF_MUSIC_GAINS);
            mCustomGains = readGains(PREF_CUSTOM_GAINS);
            Log.i(TAG, "DAX attached, enabled=" + mEnabled + " scene=" + mScene);
            registerOemObservers();
        } catch (Throwable t) {
            Log.e(TAG, "DAX attach failed", t);
            mFx = null;
        }
    }

    void registerOemObservers() {
        final android.content.ContentResolver cr = getContentResolver();
        final String[] keys = {"system_dolby", "system_dolby_music_geq", "system_dolby_geq_state",
                "system_dolby_category", "system_dolby_music_ieq"};
        ContentObserver observer = new ContentObserver(new Handler()) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                applyOemSettings();
            }
        };
        for (String key : keys) cr.registerContentObserver(Settings.System.getUriFor(key), false, observer);
        applyOemSettings();
    }

    void applyOemSettings() {
        try {
            int dolby = Settings.System.getInt(getContentResolver(), "system_dolby", mEnabled ? 1 : 0);
            if ((dolby != 0) != mEnabled) setEnabled(dolby != 0);
            int category = Settings.System.getInt(getContentResolver(), "system_dolby_category", mScene);
            if (category >= 0 && category < SCENE_TO_PROFILE.length && category != mScene) {
                applyScene(category);
            }
            int eq = Settings.System.getInt(getContentResolver(), "system_dolby_music_ieq", mEqPreset);
            if (eq >= 0 && eq <= 3) mEqPreset = eq;
            mGeqEnabled = Settings.System.getInt(getContentResolver(), "system_dolby_geq_state", 0) != 0;
            daxSetEqEnabled(mGeqEnabled);
            String raw = Settings.System.getString(getContentResolver(), "system_dolby_music_geq");
            int[] gains = parseGains(raw);
            if (gains != null) {
                mMusicGains = gains;
                daxSetEqGains(gains);
            }
            Log.i(TAG, "OEM settings applied dolby=" + dolby + " scene=" + mScene
                    + " eq=" + mEqPreset + " geq=" + mGeqEnabled + " gains=" + raw);
        } catch (Throwable t) { Log.w(TAG, "OEM settings apply failed", t); }
    }

    static int[] parseGains(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split(",");
        if (parts.length != 10) return null;
        int[] out = new int[10];
        try { for (int i = 0; i < 10; i++) out[i] = Integer.parseInt(parts[i].trim()); }
        catch (Throwable t) { return null; }
        return out;
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
                Object rc = mSetP.invoke(mFx, le(DOLBY_ONOFF_ID), le(on ? 1 : 0));
                int readback = daxGetInt(DOLBY_ONOFF_ID);
                mEnabled = readback != 0;
                Log.i(TAG, "DAX set enabled=" + on + " rc=" + rc + " readback=" + readback);
            } catch (Throwable t) { Log.w(TAG, "setEnabled failed", t); }
        }
        notifyCallbacks(1); // EffectServiceStatusChangeCallback
    }

    boolean getEnabled() {
        return mEnabled;
    }

    synchronized void setMusicIeqPreset(int scene) {
        if (scene < 0 || scene > 3) return;
        mEqPreset = scene;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_EQ, scene).apply();
        notifyCallbacks(4); // EffectServiceProfileCallback
    }

    int getMusicIeqPreset() {
        return mEqPreset;
    }

    void setEffectCategory(int cat) {
        if (cat < 0 || cat >= SCENE_TO_PROFILE.length) {
            Log.w(TAG, "ignored invalid effect category=" + cat);
            return;
        }
        applyScene(cat);
    }

    synchronized void applyScene(int scene) {
        mScene = scene;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_SCENE, scene).apply();
        Log.i(TAG, "scene persisted=" + scene + " dax=" + SCENE_TO_PROFILE[scene]);
        trySetProfile(SCENE_TO_PROFILE[scene]);
        notifyCallbacks(4);
    }

    synchronized void setMusicGeqBandGains(int[] gains) {
        if (gains == null) return;
        mMusicGains = gains.clone();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_MUSIC_GAINS, gainsString(mMusicGains)).apply();
        Log.i(TAG, "music GEQ gains=" + java.util.Arrays.toString(mMusicGains));
        daxSetEqGains(mMusicGains);
    }

    synchronized void setCustomGeqBandGains(int[] gains, boolean enabled, int device) {
        if (gains == null) return;
        mCustomGains = gains.clone();
        mGeqEnabled = enabled;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_CUSTOM_GAINS, gainsString(mCustomGains))
                .putBoolean(PREF_GEQ, enabled).apply();
        Log.i(TAG, "custom GEQ enabled=" + enabled + " device=" + device
                + " gains=" + java.util.Arrays.toString(mCustomGains));
        daxSetEqEnabled(enabled);
        daxSetEqGains(mCustomGains);
    }

    void setParameter(String key, String value) {
        if (key == null) return;
        String v = value == null ? "" : value.trim();
        if (key.contains("dolby_state") || key.contains("dax_state") || key.contains("on_off")
                || "dolby_switch".equals(key)) {
            setEnabled(parseOn(v));
            return;
        }
        if ("dolby_geq_on_off".equals(key)) {
            mGeqEnabled = parseOn(v);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_GEQ, mGeqEnabled).apply();
            Log.i(TAG, "GEQ state=" + mGeqEnabled);
            notifyCallbacks(1);
            return;
        }
        if (key.contains("dolby_profile") || key.contains("profile")) {
            int requested = parseSuffix(v, "profile_");
            if (requested >= 0) setMusicIeqPreset(requested);
            else Log.w(TAG, "unrecognized profile value=" + v);
            return;
        }
        Log.w(TAG, "ignored unknown parameter key=" + key + " value=" + v);
    }

    int[] readGains(String key) {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, "");
        int[] out = new int[10];
        if (raw == null || raw.isEmpty()) return out;
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length && i < out.length; i++) {
            try { out[i] = Integer.parseInt(parts[i]); } catch (Throwable ignored) {}
        }
        return out;
    }

    static String gainsString(int[] gains) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < gains.length; i++) {
            if (i > 0) out.append(',');
            out.append(gains[i]);
        }
        return out.toString();
    }

    static boolean parseOn(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value) || "enabled".equalsIgnoreCase(value)
                || value.endsWith("_1");
    }

    static int parseSuffix(String value, String prefix) {
        try {
            String n = value.startsWith(prefix) ? value.substring(prefix.length()) : value;
            return Integer.parseInt(n);
        } catch (Throwable ignored) { return -1; }
    }

    String getParameters(String key) {
        if ("dolby_get_dolby_state".equals(key)) return "state_" + (mEnabled ? 1 : 0);
        if ("dolby_get_dolby_profile".equals(key)) return "profile_" + mScene;
        if ("dolby_get_device_status".equals(key)) return "0";
        if ("dolby_geq_on_off".equals(key)) return Boolean.toString(mGeqEnabled);
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
        if (mFx == null || mSetP == null) return;
        try {
            Object rc = mSetP.invoke(mFx, le(OEM_PROFILE_PARAM), le(daxProfile));
            Log.i(TAG, "DAX profile write scene=" + mScene + " dax=" + daxProfile + " rc=" + rc);
        } catch (Throwable t) { Log.w(TAG, "DAX profile write failed", t); }
    }

    void daxSetEqEnabled(boolean enabled) {
        if (mFx == null || mSetP == null) return;
        try {
            Object rc = mSetP.invoke(mFx, le(OEM_EQ_ENABLE_PARAM), le(enabled ? 1 : 0));
            Log.i(TAG, "DAX EQ enable=" + enabled + " rc=" + rc);
        } catch (Throwable t) { Log.w(TAG, "DAX EQ enable failed", t); }
    }

    void daxSetEqGains(int[] gains) {
        if (mFx == null || mSetP == null || gains == null || gains.length != 10) return;
        try {
            int volume = 0;
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                int current = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                volume = max > 100 ? current / 10 : current;
            }
            byte[] payload = new byte[48];
            putLe(payload, 0, volume);
            putLe(payload, 4, 10);
            for (int i = 0; i < 10; i++) putLe(payload, 8 + i * 4, gains[i]);
            Object rc = mSetP.invoke(mFx, le(OEM_EQ_GAINS_PARAM), payload);
            Log.i(TAG, "DAX EQ gains write volume=" + volume + " rc=" + rc);
        } catch (Throwable t) { Log.w(TAG, "DAX EQ gains failed", t); }
    }

    static void putLe(byte[] a, int off, int v) {
        a[off] = (byte) v; a[off + 1] = (byte) (v >> 8);
        a[off + 2] = (byte) (v >> 16); a[off + 3] = (byte) (v >> 24);
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
