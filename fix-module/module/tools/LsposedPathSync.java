package com.aclaniakea.tools;

import android.database.sqlite.SQLiteDatabase;
import java.io.File;

/** Pins an allowlisted LSPosed module to an early-boot-stable APK path. */
public final class LsposedPathSync {
    private static final String BASE_MODULE = "com.aclaniakea.colorosostatsguard";
    private static final String PEN_MODULE = "com.aclaniakea.lenovopenbridge";
    // LSPosed stores the Android framework scope as "system". Keep this list
    // aligned with the APK's META-INF/xposed/scope.list. INSERT OR IGNORE only
    // adds required scopes and never removes scopes selected by the user.
    private static final String[] SCOPES = {
            "system",
            "com.android.settings",
            "com.coloros.phonemanager",
            "com.coloros.ocrscanner",
            "com.inkdye.lenovopentocoloros",
            "com.aiunit.aon",
            "com.heytap.speechassist",
            "com.oplus.ovoicemanager.wakeup",
            "com.oplus.battery",
            "com.oplus.gesture",
            "com.coloros.findmyphone"
    };

    private LsposedPathSync() {}

    public static void main(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "usage: LsposedPathSync <database> <apk> [module [scope...]]");
        }
        String database = args[0];
        String apk = args[1];
        String module = args.length >= 3 ? args[2] : BASE_MODULE;
        if (!database.startsWith("/data/adb/lspd/") || !apk.startsWith("/data/adb/modules/")) {
            throw new IllegalArgumentException("refusing unexpected path");
        }
        if (!BASE_MODULE.equals(module) && !PEN_MODULE.equals(module)) {
            throw new IllegalArgumentException("refusing unexpected module");
        }

        SQLiteDatabase.OpenParams params = new SQLiteDatabase.OpenParams.Builder()
                .setOpenFlags(SQLiteDatabase.OPEN_READWRITE
                        | SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                .setJournalMode("WAL")
                .setSynchronousMode("NORMAL")
                .build();
        SQLiteDatabase db = SQLiteDatabase.openDatabase(new File(database), params);
        db.beginTransaction();
        try {
            // Do not use REPLACE here: SQLite implements it as DELETE + INSERT,
            // which can cascade into LSPosed's scope rows on some schemas.
            db.execSQL("UPDATE modules SET apk_path=? WHERE module_pkg_name=?",
                    new Object[] {apk, module});
            db.execSQL("INSERT OR IGNORE INTO modules(module_pkg_name,apk_path) VALUES(?,?)",
                    new Object[] {module, apk});
            db.execSQL("UPDATE modules_state SET enabled=1"
                            + " WHERE module_pkg_name=? AND user_id=0",
                    new Object[] {module});
            db.execSQL("INSERT OR IGNORE INTO modules_state"
                            + "(module_pkg_name,user_id,enabled,scope_request_blocked)"
                            + " VALUES(?,0,1,0)",
                    new Object[] {module});
            String[] scopes;
            if (args.length >= 4) {
                scopes = new String[args.length - 3];
                System.arraycopy(args, 3, scopes, 0, scopes.length);
            } else if (BASE_MODULE.equals(module)) {
                scopes = SCOPES;
            } else {
                // Existing user-selected Pen scopes are preserved. The Pen
                // module passes its packaged scope list explicitly at boot.
                scopes = new String[0];
            }
            for (String scope : scopes) {
                db.execSQL("INSERT OR IGNORE INTO scope"
                                + "(module_pkg_name,app_pkg_name,user_id) VALUES(?,?,0)",
                        new Object[] {module, scope});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }
        System.out.println(module + " LSPosed path/scopes pinned to " + apk);
    }
}
