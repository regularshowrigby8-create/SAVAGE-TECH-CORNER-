package com.savage.soulbrowser.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

/**
 * Local SQLite store for history and bookmarks.
 *
 * <p>Schema version 1 is the first published schema. {@link #onUpgrade} and {@link #onDowngrade}
 * recreate the tables, which is fail-safe: a client with an unexpected schema starts with an empty
 * but valid store instead of crashing on every query. Any future schema change must replace that
 * behaviour with an explicit migration.
 */
public class BrowserDatabase extends SQLiteOpenHelper {

    private static final String TAG = "SavageBrowser";

    public static final String DATABASE_NAME = "savage_browser.db";
    public static final int DATABASE_VERSION = 1;

    public static final String TABLE_HISTORY = "history";
    public static final String TABLE_BOOKMARKS = "bookmarks";

    public static final String COL_ID = "_id";
    public static final String COL_URL = "url";
    public static final String COL_DEDUPE_KEY = "dedupe_key";
    public static final String COL_TITLE = "title";
    public static final String COL_LAST_VISIT = "last_visit";
    public static final String COL_VISIT_COUNT = "visit_count";
    public static final String COL_CREATED_AT = "created_at";
    public static final String COL_SYNC_STATE = "sync_state";

    private static final String CREATE_HISTORY =
            "CREATE TABLE " + TABLE_HISTORY + " ("
                    + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + COL_URL + " TEXT NOT NULL, "
                    + COL_DEDUPE_KEY + " TEXT NOT NULL UNIQUE, "
                    + COL_TITLE + " TEXT, "
                    + COL_LAST_VISIT + " INTEGER NOT NULL, "
                    + COL_VISIT_COUNT + " INTEGER NOT NULL DEFAULT 1)";

    private static final String CREATE_HISTORY_INDEX =
            "CREATE INDEX index_history_last_visit ON " + TABLE_HISTORY + "(" + COL_LAST_VISIT + ")";

    private static final String CREATE_BOOKMARKS =
            "CREATE TABLE " + TABLE_BOOKMARKS + " ("
                    + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + COL_URL + " TEXT NOT NULL, "
                    + COL_DEDUPE_KEY + " TEXT NOT NULL UNIQUE, "
                    + COL_TITLE + " TEXT, "
                    + COL_CREATED_AT + " INTEGER NOT NULL, "
                    + COL_SYNC_STATE + " INTEGER NOT NULL DEFAULT 0)";

    private static final String CREATE_BOOKMARKS_INDEX =
            "CREATE INDEX index_bookmarks_created ON " + TABLE_BOOKMARKS + "(" + COL_CREATED_AT + ")";

    public BrowserDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    /**
     * Version-parameterised constructor used by tests to exercise the upgrade/downgrade paths and
     * corruption recovery without shipping a second schema.
     */
    public BrowserDatabase(Context context, String databaseName, int version) {
        super(context, databaseName, null, version);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createSchema(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.w(TAG, "Recreating local store for schema change " + oldVersion + " -> " + newVersion);
        recreate(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.w(TAG, "Recreating local store for schema downgrade " + oldVersion + " -> " + newVersion);
        recreate(db);
    }

    private static void createSchema(SQLiteDatabase db) {
        db.execSQL(CREATE_HISTORY);
        db.execSQL(CREATE_HISTORY_INDEX);
        db.execSQL(CREATE_BOOKMARKS);
        db.execSQL(CREATE_BOOKMARKS_INDEX);
    }

    private static void recreate(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_HISTORY);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_BOOKMARKS);
        createSchema(db);
    }

    /** Deletes the on-disk database; used to recover from a corrupt file. Never throws. */
    public static boolean deleteDatabaseFiles(Context context, String databaseName) {
        boolean deleted = true;
        deleted &= context.deleteDatabase(databaseName);
        for (String suffix : new String[]{"-journal", "-wal", "-shm"}) {
            java.io.File file = context.getDatabasePath(databaseName + suffix);
            if (file.exists()) {
                deleted &= file.delete();
            }
        }
        return deleted;
    }

    static boolean tableExists(SQLiteDatabase db, String table) {
        try (Cursor cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{table})) {
            return cursor.moveToFirst();
        }
    }
}
