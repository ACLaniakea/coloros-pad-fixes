package com.aclaniakea.tools;

import android.database.sqlite.SQLiteDatabase;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Pins an allowlisted LSPosed module to an early-boot-stable APK path. */
public final class LsposedPathSync {
    private static final String BASE_MODULE = "com.aclaniakea.colorosostatsguard";
    private static final String PEN_MODULE = "com.aclaniakea.lenovopenbridge";
    private static final String ZUI_CAMERA_MODULE = "com.aclaniakea.zuicameracompat";
    // LSPosed stores the Android framework scope as "system", which is what
    // the packaged scope.list already calls "android"; that one name is
    // translated below. Everything else is read straight out of the APK's
    // META-INF/xposed/scope.list rather than restated here.
    //
    // This used to be a hand-written array with a comment asking whoever
    // edited scope.list to remember to update it too. It drifted exactly as
    // you would expect: com.oplus.athena was added to scope.list for the RAM
    // expansion bridge and never reached this list, so LSPosed - which only
    // imports scope.list on first install, never on upgrade - kept injecting
    // the module everywhere except the one process that needed it.
    private static final String SCOPE_ENTRY = "META-INF/xposed/scope.list";
    private static final String FRAMEWORK_SCOPE_IN_LIST = "android";
    private static final String FRAMEWORK_SCOPE_IN_DB = "system";

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
        if (!BASE_MODULE.equals(module) && !PEN_MODULE.equals(module)
                && !ZUI_CAMERA_MODULE.equals(module)) {
            throw new IllegalArgumentException("refusing unexpected module");
        }

        SQLiteDatabase.OpenParams params = new SQLiteDatabase.OpenParams.Builder()
                .setOpenFlags(SQLiteDatabase.OPEN_READWRITE
                        | SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                .setJournalMode("WAL")
                .setSynchronousMode("NORMAL")
                .build();
        int scopeCount = 0;
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
            } else if (BASE_MODULE.equals(module) || ZUI_CAMERA_MODULE.equals(module)) {
                scopes = packagedScopes(apk);
            } else {
                // Existing user-selected Pen scopes are preserved. The Pen
                // module passes its packaged scope list explicitly at boot.
                scopes = new String[0];
            }
            scopeCount = scopes.length;
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
        System.out.println(module + " LSPosed path/scopes pinned to " + apk
                + " (" + scopeCount + " scopes)");
    }

    /**
     * The module's own declared scope list, read from the APK being pinned.
     * Reading it from the artifact rather than restating it here means the two
     * can never disagree: whatever LSPosed would have imported on a first
     * install is exactly what gets reconciled on every boot.
     *
     * <p>An unreadable or absent list yields no scopes. That is the safe
     * outcome - INSERT OR IGNORE only ever adds rows, so doing nothing leaves
     * the user's existing selection untouched.
     */
    private static String[] packagedScopes(String apk) {
        List<String> scopes = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry(SCOPE_ENTRY);
            if (entry == null) {
                System.out.println("no " + SCOPE_ENTRY + " in " + apk);
                return new String[0];
            }
            try (InputStream in = zip.getInputStream(entry);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String scope = line.trim();
                    if (scope.isEmpty() || scope.startsWith("#")) {
                        continue;
                    }
                    if (FRAMEWORK_SCOPE_IN_LIST.equals(scope)) {
                        scope = FRAMEWORK_SCOPE_IN_DB;
                    }
                    if (!scopes.contains(scope)) {
                        scopes.add(scope);
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("failed to read " + SCOPE_ENTRY + ": " + e);
            return new String[0];
        }
        return scopes.toArray(new String[0]);
    }
}
