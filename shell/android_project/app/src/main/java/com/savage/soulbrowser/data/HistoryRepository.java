package com.savage.soulbrowser.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import com.savage.soulbrowser.domain.HistoryEntry;
import com.savage.soulbrowser.domain.HistoryRetention;
import com.savage.soulbrowser.domain.UrlPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * History storage: one row per canonical URL, a visit counter, and a bounded table
 * ({@link HistoryRetention#MAX_HISTORY_ENTRIES} newest entries).
 *
 * <p>Only HTTPS URLs are recorded — the browser never loads anything else.
 */
public final class HistoryRepository {

    private static final String TAG = "SavageBrowser";
    private static final int MAX_LIST_SIZE = 500;

    private final BrowserDatabase database;

    public HistoryRepository(BrowserDatabase database) {
        this.database = database;
    }

    /**
     * Records a visit.
     *
     * @return {@code true} when the visit was stored; {@code false} for a refused URL or a
     *         storage failure (which is logged, never thrown at the UI)
     */
    public boolean recordVisit(String url, String title, long nowMillis) {
        if (UrlPolicy.verify(url) != UrlPolicy.Verdict.ALLOWED) {
            return false;
        }
        String key = UrlPolicy.normalizeKey(url);
        if (key.isEmpty()) {
            return false;
        }
        SQLiteDatabase db;
        try {
            db = database.getWritableDatabase();
        } catch (SQLiteException e) {
            Log.e(TAG, "History store unavailable: " + e.getClass().getSimpleName());
            return false;
        }
        db.beginTransaction();
        try {
            int visitCount = 1;
            long existingId = -1;
            try (Cursor cursor = db.query(BrowserDatabase.TABLE_HISTORY,
                    new String[]{BrowserDatabase.COL_ID, BrowserDatabase.COL_VISIT_COUNT},
                    BrowserDatabase.COL_DEDUPE_KEY + " = ?", new String[]{key},
                    null, null, null)) {
                if (cursor.moveToFirst()) {
                    existingId = cursor.getLong(0);
                    visitCount = cursor.getInt(1) + 1;
                }
            }
            ContentValues values = new ContentValues();
            values.put(BrowserDatabase.COL_URL, url);
            values.put(BrowserDatabase.COL_DEDUPE_KEY, key);
            values.put(BrowserDatabase.COL_TITLE, title);
            values.put(BrowserDatabase.COL_LAST_VISIT, nowMillis);
            values.put(BrowserDatabase.COL_VISIT_COUNT, visitCount);
            if (existingId >= 0) {
                db.update(BrowserDatabase.TABLE_HISTORY, values,
                        BrowserDatabase.COL_ID + " = ?", new String[]{String.valueOf(existingId)});
            } else {
                db.insert(BrowserDatabase.TABLE_HISTORY, null, values);
            }
            db.setTransactionSuccessful();
        } catch (SQLiteException e) {
            Log.e(TAG, "Could not record visit: " + e.getClass().getSimpleName());
            return false;
        } finally {
            db.endTransaction();
        }
        pruneTo(HistoryRetention.MAX_HISTORY_ENTRIES);
        return true;
    }

    public List<HistoryEntry> recent(int limit) {
        List<HistoryEntry> entries = new ArrayList<>();
        int boundedLimit = Math.max(1, Math.min(limit, MAX_LIST_SIZE));
        try (Cursor cursor = database.getReadableDatabase().query(
                BrowserDatabase.TABLE_HISTORY, null, null, null, null, null,
                BrowserDatabase.COL_LAST_VISIT + " DESC", String.valueOf(boundedLimit))) {
            while (cursor.moveToNext()) {
                entries.add(readEntry(cursor));
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "History query failed: " + e.getClass().getSimpleName());
        }
        return entries;
    }

    public int count() {
        try (Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + BrowserDatabase.TABLE_HISTORY, null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } catch (SQLiteException e) {
            Log.e(TAG, "History count failed: " + e.getClass().getSimpleName());
            return 0;
        }
    }

    public boolean isEmpty() {
        return count() == 0;
    }

    public int delete(long id) {
        try {
            return database.getWritableDatabase().delete(BrowserDatabase.TABLE_HISTORY,
                    BrowserDatabase.COL_ID + " = ?", new String[]{String.valueOf(id)});
        } catch (SQLiteException e) {
            Log.e(TAG, "History delete failed: " + e.getClass().getSimpleName());
            return 0;
        }
    }

    public int clear() {
        try {
            return database.getWritableDatabase().delete(BrowserDatabase.TABLE_HISTORY, null, null);
        } catch (SQLiteException e) {
            Log.e(TAG, "History clear failed: " + e.getClass().getSimpleName());
            return 0;
        }
    }

    /** Deletes the oldest rows so that at most {@code maxEntries} remain. */
    public int pruneTo(int maxEntries) {
        int total = count();
        int excess = HistoryRetention.excessCount(total, maxEntries);
        if (excess == 0) {
            return 0;
        }
        try {
            SQLiteDatabase db = database.getWritableDatabase();
            StringBuilder ids = new StringBuilder();
            try (Cursor cursor = db.query(BrowserDatabase.TABLE_HISTORY,
                    new String[]{BrowserDatabase.COL_ID}, null, null, null, null,
                    BrowserDatabase.COL_LAST_VISIT + " ASC", String.valueOf(excess))) {
                while (cursor.moveToNext()) {
                    if (ids.length() > 0) {
                        ids.append(',');
                    }
                    ids.append(cursor.getLong(0));
                }
            }
            if (ids.length() == 0) {
                return 0;
            }
            return db.delete(BrowserDatabase.TABLE_HISTORY,
                    BrowserDatabase.COL_ID + " IN (" + ids + ")", null);
        } catch (SQLiteException e) {
            Log.e(TAG, "History prune failed: " + e.getClass().getSimpleName());
            return 0;
        }
    }

    static HistoryEntry readEntry(Cursor cursor) {
        int visits = cursor.getInt(cursor.getColumnIndexOrThrow(BrowserDatabase.COL_VISIT_COUNT));
        return new HistoryEntry(
                cursor.getLong(cursor.getColumnIndexOrThrow(BrowserDatabase.COL_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(BrowserDatabase.COL_URL)),
                cursor.getString(cursor.getColumnIndexOrThrow(BrowserDatabase.COL_TITLE)),
                cursor.getLong(cursor.getColumnIndexOrThrow(BrowserDatabase.COL_LAST_VISIT)),
                visits <= 0 ? 1 : visits);
    }
}
