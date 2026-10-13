package com.savage.soulbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.savage.soulbrowser.data.BookmarkRepository;
import com.savage.soulbrowser.data.BrowserDatabase;
import com.savage.soulbrowser.data.HistoryRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * AC-039: on a fresh database every read returns empty content - never {@code null}, never an
 * exception - so an empty screen is a normal state and not an error.
 */
public class RepositoryEmptyStateTest {

    private Context context;
    private BrowserDatabase database;

    @Before
    public void setUp() {
        context = TestSupport.context();
        TestSupport.wipeLocalData();
        database = new BrowserDatabase(context);
    }

    @After
    public void tearDown() {
        database.close();
        TestSupport.wipeLocalData();
    }

    @Test
    public void queriesOnFreshDatabaseReturnEmptyLists() {
        HistoryRepository history = new HistoryRepository(database);
        BookmarkRepository bookmarks = new BookmarkRepository(database);

        assertTrue(history.isEmpty());
        assertEquals(0, history.count());
        assertTrue("an empty history is an empty list, not null", history.recent(20).isEmpty());
        assertEquals("no rows need pruning", 0, history.pruneTo(10));
        assertEquals("nothing to delete", 0, history.delete(1L));

        assertEquals(0, bookmarks.count());
        assertTrue("an empty bookmark list is an empty list, not null", bookmarks.all().isEmpty());
        assertEquals(0, bookmarks.syncPendingCount());
        assertFalse("an unknown URL is not a bookmark", bookmarks.contains("https://example.com/none"));
        assertFalse(bookmarks.remove("https://example.com/none"));
        assertEquals(0, bookmarks.delete(1L));
        assertEquals("nothing to mark as synced", 0, bookmarks.markAllSynced());
    }
}
