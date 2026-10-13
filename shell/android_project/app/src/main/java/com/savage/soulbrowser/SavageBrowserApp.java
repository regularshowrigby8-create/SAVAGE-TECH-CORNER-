package com.savage.soulbrowser;

import android.app.Application;

import com.savage.soulbrowser.data.BookmarkRepository;
import com.savage.soulbrowser.data.BrowserDatabase;
import com.savage.soulbrowser.data.BrowserPreferences;
import com.savage.soulbrowser.data.BrowserStore;
import com.savage.soulbrowser.data.HistoryRepository;

/**
 * Application-scoped container for the local store, so activities share one database connection and
 * one preferences instance. Creation is lazy and fail-safe: {@link BrowserStore#open} repairs an
 * unusable database file instead of crashing the process at startup.
 */
public final class SavageBrowserApp extends Application {

    private BrowserDatabase database;
    private HistoryRepository historyRepository;
    private BookmarkRepository bookmarkRepository;
    private BrowserPreferences preferences;

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = new BrowserPreferences(this);
    }

    public static SavageBrowserApp from(android.content.Context context) {
        return (SavageBrowserApp) context.getApplicationContext();
    }

    public synchronized BrowserDatabase database() {
        if (database == null) {
            database = BrowserStore.open(this);
        }
        return database;
    }

    public synchronized HistoryRepository historyRepository() {
        if (historyRepository == null) {
            historyRepository = new HistoryRepository(database());
        }
        return historyRepository;
    }

    public synchronized BookmarkRepository bookmarkRepository() {
        if (bookmarkRepository == null) {
            bookmarkRepository = new BookmarkRepository(database());
        }
        return bookmarkRepository;
    }

    public BrowserPreferences preferences() {
        if (preferences == null) {
            preferences = new BrowserPreferences(this);
        }
        return preferences;
    }
}
