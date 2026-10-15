package com.savage.soulbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.savage.soulbrowser.data.BookmarkRepository;
import com.savage.soulbrowser.data.BrowserDatabase;
import com.savage.soulbrowser.data.BrowserPreferences;
import com.savage.soulbrowser.data.HistoryRepository;
import com.savage.soulbrowser.domain.SearchProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * AC-023, AC-024 and AC-025: history, bookmarks and settings survive the process that wrote them.
 *
 * <p>Each test closes the writer completely and reopens from the same context, which is what a
 * cold start does.
 */
public class PersistenceTest {

    private static final String URL = "https://example.com/persisted";

    private Context context;

    @Before
    public void setUp() {
        context = TestSupport.context();
        TestSupport.wipeLocalData();
    }

    @After
    public void tearDown() {
        TestSupport.wipeLocalData();
    }

    @Test
    public void historySurvivesHelperReopen() {
        BrowserDatabase first = new BrowserDatabase(context);
        assertTrue(new HistoryRepository(first).recordVisit(URL, "Persisted page", 1_234L));
        first.close();

        BrowserDatabase second = new BrowserDatabase(context);
        try {
            HistoryRepository reopened = new HistoryRepository(second);
            assertEquals(1, reopened.count());
            assertEquals(URL, reopened.recent(1).get(0).getUrl());
            assertEquals("Persisted page", reopened.recent(1).get(0).getTitle());
        } finally {
            second.close();
        }
    }

    @Test
    public void bookmarksSurviveHelperReopen() {
        BrowserDatabase first = new BrowserDatabase(context);
        assertEquals(BookmarkRepository.AddResult.ADDED,
                new BookmarkRepository(first).add(URL, "Persisted bookmark", 1_234L));
        first.close();

        BrowserDatabase second = new BrowserDatabase(context);
        try {
            BookmarkRepository reopened = new BookmarkRepository(second);
            assertEquals(1, reopened.count());
            assertTrue(reopened.contains(URL));
            assertEquals("a stored bookmark stays queued until it is synced",
                    1, reopened.syncPendingCount());
        } finally {
            second.close();
        }
    }

    @Test
    public void settingsSurviveReopen() {
        BrowserPreferences first = new BrowserPreferences(context);
        assertEquals("the default provider is DuckDuckGo",
                SearchProvider.DUCKDUCKGO, first.searchProvider());
        assertFalse("desktop mode is off by default", first.isDesktopSite());
        assertNull("a fresh install has no last URL", first.lastUrl());

        first.setSearchProvider(SearchProvider.GOOGLE);
        first.setDesktopSite(true);
        first.setLastUrl(URL);

        BrowserPreferences reopened = new BrowserPreferences(context);
        assertEquals(SearchProvider.GOOGLE, reopened.searchProvider());
        assertTrue(reopened.isDesktopSite());
        assertEquals(URL, reopened.lastUrl());
    }

    @Test
    public void refusedLastUrlIsNeverPersisted() {
        BrowserPreferences preferences = new BrowserPreferences(context);
        preferences.setLastUrl("http://example.com/cleartext");
        assertNull("a cleartext URL is refused on write", preferences.lastUrl());

        preferences.setLastUrl("javascript:alert(1)");
        assertNull("a script URL is refused on write", preferences.lastUrl());
    }
}
