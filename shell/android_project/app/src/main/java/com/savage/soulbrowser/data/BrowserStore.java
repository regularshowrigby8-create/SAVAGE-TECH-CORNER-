package com.savage.soulbrowser.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;

/**
 * Opens the local store and repairs it when the file on disk cannot be used.
 *
 * <p>A browser must never crash because of its own local cache: an unreadable or corrupt database
 * is deleted and recreated, so the user sees the empty state instead of a crash loop. The repair is
 * logged (never silent) and is limited to the app's own database file.
 */
public final class BrowserStore {

    private static final String TAG = "SavageBrowser";
    private static final String PROBE_SQL = "SELECT COUNT(*) FROM sqlite_master";

    private BrowserStore() {
    }

    /** @return a usable {@link BrowserDatabase}; the file is recreated when the probe fails */
    public static BrowserDatabase open(Context context) {
        BrowserDatabase database = new BrowserDatabase(context);
        if (probe(database)) {
            return database;
        }
        Log.w(TAG, "Local store unusable; recreating " + BrowserDatabase.DATABASE_NAME);
        database.close();
        BrowserDatabase.deleteDatabaseFiles(context, BrowserDatabase.DATABASE_NAME);
        BrowserDatabase recreated = new BrowserDatabase(context);
        if (!probe(recreated)) {
            Log.e(TAG, "Local store still unusable after recreation; running with empty results");
        }
        return recreated;
    }

    /** @return {@code true} when the schema can actually be read */
    static boolean probe(BrowserDatabase database) {
        try (SQLiteDatabase db = database.getWritableDatabase();
             Cursor cursor = db.rawQuery(PROBE_SQL, null)) {
            return cursor.moveToFirst();
        } catch (SQLiteException e) {
            return false;
        } catch (RuntimeException e) {
            // android.database.sqlite throws DatabaseUtils$... / IllegalStateException for a file
            // that is not a database at all; both mean "not usable".
            return false;
        }
    }
}
