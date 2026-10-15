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

    /**
     * Drops the cached store and repositories so the next access opens the database file again.
     *
     * <p>Used when the file on disk was replaced behind the process that cached the connection (a
     * repair, a restore, or the corruption-recovery test): without this, the old connection would
     * keep writing to a file the application can no longer read.
     */
    public synchronized void reopenLocalStore() {
        if (database != null) {
            database.close();
            database = null;
        }
        historyRepository = null;
        bookmarkRepository = null;
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
