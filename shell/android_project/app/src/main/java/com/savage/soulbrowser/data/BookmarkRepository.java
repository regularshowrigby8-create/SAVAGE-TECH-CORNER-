package com.savage.soulbrowser.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import com.savage.soulbrowser.domain.Bookmark;
import com.savage.soulbrowser.domain.UrlPolicy;

import java.util.ArrayList;
import java.util.List;

/** Bookmark storage: one row per canonical URL, local-first with a pending-sync flag. */
public final class BookmarkRepository {

    /** Outcome of {@link #add(String, String, long)}. */
    public enum AddResult {
        ADDED,
        ALREADY_PRESENT,
        REJECTED
    }

    private static final String TAG = "SavageBrowser";

    private final BrowserDatabase database;

    public BookmarkRepository(BrowserDatabase database) {
        this.database = database;
    }

    /**
     * Stores a bookmark. A page that is already stored is not written again and not rewritten: the
     * user's existing entry keeps its title, its URL and its creation time (the dedupe key is the
     * canonical URL, protected by a UNIQUE constraint). The caller learns from the result that the
     * entry was already there, so the star can advertise removal instead of re-adding.
     */
    public AddResult add(String url, String title, long nowMillis) {
        if (UrlPolicy.verify(url) != UrlPolicy.Verdict.ALLOWED) {
            return AddResult.REJECTED;
        }
        String key = UrlPolicy.normalizeKey(url);
        if (key.isEmpty()) {
            return AddResult.REJECTED;
        }
        try {
            SQLiteDatabase db = database.getWritableDatabase();
            if (contains(url)) {
                // Same page: the stored row is the user's data and stays exactly as it is.
                return AddResult.ALREADY_PRESENT;
            }
            ContentValues values = new ContentValues();
            values.put(BrowserDatabase.COL_URL, url);
            values.put(BrowserDatabase.COL_DEDUPE_KEY, key);
            values.put(BrowserDatabase.COL_TITLE, title);
            values.put(BrowserDatabase.COL_CREATED_AT, nowMillis);
            values.put(BrowserDatabase.COL_SYNC_STATE,
                    com.savage.soulbrowser.domain.SyncState.PENDING.code());
            db.insertOrThrow(BrowserDatabase.TABLE_BOOKMARKS, null, values);
            return AddResult.ADDED;
        } catch (SQLiteException e) {
            Log.e(TAG, "Could not store bookmark: " + e.getClass().getSimpleName());
            return AddResult.REJECTED;
        }
    }

    public boolean contains(String url) {
        String key = UrlPolicy.normalizeKey(url);
        if (key.isEmpty()) {
            return false;
        }
        try (Cursor cursor = database.getReadableDatabase().query(
                BrowserDatabase.TABLE_BOOKMARKS, new String[]{BrowserDatabase.COL_ID},
                BrowserDatabase.COL_DEDUPE_KEY + " = ?", new String[]{key},
                null, null, null)) {
            return cursor.moveToFirst();
        } catch (SQLiteException e) {
            Log.e(TAG, "Bookmark lookup failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** @return {@code true} when a row was removed */
    public boolean remove(String url) {
        String key = UrlPolicy.normalizeKey(url);
        if (key.isEmpty()) {
            return false;
        }
        try {
            return database.getWritableDatabase().delete(BrowserDatabase.TABLE_BOOKMARKS,
                    BrowserDatabase.COL_DEDUPE_KEY + " = ?", new String[]{key}) > 0;
        } catch (SQLiteException e) {
            Log.e(TAG, "Bookmark delete failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    public int delete(long id) {
        try {
            return database.getWritableDatabase().delete(BrowserDatabase.TABLE_BOOKMARKS,
                    BrowserDatabase.COL_ID + " = ?", new String[]{String.valueOf(id)});
        } catch (SQLiteException e) {
            Log.e(TAG, "Bookmark delete failed: " + e.getClass().getSimpleName());
            return 0;
        }
    }

    public List<Bookmark> all() {
        List<Bookmark> bookmarks = new ArrayList<>();
        try (Cursor cursor = database.getReadableDatabase().query(
                BrowserDatabase.TABLE_BOOKMARKS, null, null, null, null, null,
                BrowserDatabase.COL_CREATED_AT + " DESC")) {
            while (cursor.moveToNext()) {
                bookmarks.add(new Bookmark(
                        cursor.getLong(cursor.getColumnIndexOrThrow(BrowserDatabase.COL_ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(BrowserDatabase.COL_URL)),
                        cursor.getString(cursor.getColumnIndexOrThrow(BrowserDatabase.COL_TITLE)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(BrowserDatabase.COL_CREATED_AT)),
                        com.savage.soulbrowser.domain.SyncState.fromCode(
                                cursor.getInt(cursor.getColumnIndexOrThrow(BrowserDatabase.COL_SYNC_STATE)))));
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "Bookmark query failed: " + e.getClass().getSimpleName());
        }
        return bookmarks;
    }

    public int count() {
        try (Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + BrowserDatabase.TABLE_BOOKMARKS, null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } catch (SQLiteException e) {
            Log.e(TAG, "Bookmark count failed: " + e.getClass().getSimpleName());
            return 0;
        }
    }

    public int syncPendingCount() {
        try (Cursor cursor = database.getReadableDatabase().query(
                BrowserDatabase.TABLE_BOOKMARKS, new String[]{BrowserDatabase.COL_ID},
                BrowserDatabase.COL_SYNC_STATE + " = ?",
                new String[]{String.valueOf(com.savage.soulbrowser.domain.SyncState.PENDING.code())},
                null, null, null)) {
            return cursor.getCount();
        } catch (SQLiteException e) {
            Log.e(TAG, "Bookmark sync count failed: " + e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * Marks every pending record as synchronised. This is the completion hook the sync service
     * (see {@code docs/ARCHITECTURE.md} §5.3) calls once a batch has been acknowledged; it is
     * exercised by {@code BookmarkRepositoryTest} and never claims a network round-trip happened.
     *
     * @return the number of rows updated
     */
    public int markAllSynced() {
        ContentValues values = new ContentValues();
        values.put(BrowserDatabase.COL_SYNC_STATE,
                com.savage.soulbrowser.domain.SyncState.SYNCED.code());
        try {
            return database.getWritableDatabase().update(BrowserDatabase.TABLE_BOOKMARKS, values,
                    BrowserDatabase.COL_SYNC_STATE + " = ?",
                    new String[]{String.valueOf(com.savage.soulbrowser.domain.SyncState.PENDING.code())});
        } catch (SQLiteException e) {
            Log.e(TAG, "Bookmark sync update failed: " + e.getClass().getSimpleName());
            return 0;
        }
    }
}
