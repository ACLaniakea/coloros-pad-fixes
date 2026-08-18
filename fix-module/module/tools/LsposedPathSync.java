package com.aclaniakea.tools;

import android.database.sqlite.SQLiteDatabase;
import java.io.File;

/** Pins the BaseFix LSPosed module to an early-boot-stable APK path. */
public final class LsposedPathSync {
    private static final String MODULE = "com.aclaniakea.colorosostatsguard";

    private LsposedPathSync() {}

    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: LsposedPathSync <database> <apk>");
        }
        String database = args[0];
        String apk = args[1];
        if (!database.startsWith("/data/adb/lspd/") || !apk.startsWith("/data/adb/modules/")) {
            throw new IllegalArgumentException("refusing unexpected path");
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
                    new Object[] {apk, MODULE});
            db.execSQL("INSERT OR IGNORE INTO modules(module_pkg_name,apk_path) VALUES(?,?)",
                    new Object[] {MODULE, apk});
            db.execSQL("UPDATE modules_state SET enabled=1"
                            + " WHERE module_pkg_name=? AND user_id=0",
                    new Object[] {MODULE});
            db.execSQL("INSERT OR IGNORE INTO modules_state"
                            + "(module_pkg_name,user_id,enabled,scope_request_blocked)"
                            + " VALUES(?,0,1,0)",
                    new Object[] {MODULE});
            db.execSQL("INSERT OR IGNORE INTO scope(module_pkg_name,app_pkg_name,user_id)"
                            + " VALUES(?,'system',0)",
                    new Object[] {MODULE});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }
        System.out.println("BaseFix LSPosed path pinned to " + apk);
    }
}
