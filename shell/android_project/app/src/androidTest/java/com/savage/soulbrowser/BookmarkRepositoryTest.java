package com.savage.soulbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.savage.soulbrowser.data.BookmarkRepository;
import com.savage.soulbrowser.data.BrowserDatabase;
import com.savage.soulbrowser.domain.Bookmark;
import com.savage.soulbrowser.domain.SyncState;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * AC-006 and AC-034: bookmarks are stored and read back, storing the same page twice keeps a single
 * row, and a bookmark is queued for sync exactly once.
 */
public class BookmarkRepositoryTest {

    private static final String URL = "https://example.com/bookmark/one";

    private Context context;
    private BrowserDatabase database;
    private BookmarkRepository repository;

    @Before
    public void setUp() {
        context = TestSupport.context();
        TestSupport.wipeLocalData();
        database = new BrowserDatabase(context);
        repository = new BookmarkRepository(database);
    }

    @After
    public void tearDown() {
        database.close();
        TestSupport.wipeLocalData();
    }

    @Test
    public void upsertThenRead() {
        BookmarkRepository.AddResult result =
                repository.add(URL, "First title", 1_000L);
        assertEquals(BookmarkRepository.AddResult.ADDED, result);

        List<Bookmark> all = repository.all();
        assertEquals(1, all.size());
        Bookmark stored = all.get(0);
        assertEquals(URL, stored.getUrl());
        assertEquals("First title", stored.getTitle());
        assertEquals(1_000L, stored.getCreatedAtMillis());
        assertEquals("a fresh local bookmark waits for sync",
                SyncState.PENDING, stored.getSyncState());
        assertEquals("one bookmark is queued", 1, repository.syncPendingCount());
        assertTrue(repository.contains(URL));
    }

    @Test
    public void duplicateUpsertKeepsSingleRow() {
        assertEquals(BookmarkRepository.AddResult.ADDED,
                repository.add(URL, "First title", 1_000L));
        // A trailing slash and a different fragment are the same page.
        assertEquals(BookmarkRepository.AddResult.ALREADY_PRESENT,
                repository.add(URL + "/#section", "Second title", 2_000L));

        assertEquals("a duplicate must not create a second row", 1, repository.count());
        Bookmark stored = repository.all().get(0);
        assertEquals("the original entry is kept", "First title", stored.getTitle());
        assertEquals(1_000L, stored.getCreatedAtMillis());

        int marked = repository.markAllSynced();
        assertEquals("marking the queue drains it exactly once", 1, marked);
        assertEquals(0, repository.syncPendingCount());
        assertEquals("re-marking finds nothing to do", 0, repository.markAllSynced());
    }

    @Test
    public void refusedUrlIsNotStored() {
        assertEquals(BookmarkRepository.AddResult.REJECTED,
                repository.add("http://example.com/cleartext", "Cleartext", 1_000L));
        assertEquals(BookmarkRepository.AddResult.REJECTED,
                repository.add("file:///etc/hosts", "Local file", 1_000L));
        assertEquals("nothing unsafe is ever written", 0, repository.count());
        assertFalse(repository.contains("http://example.com/cleartext"));
    }

    @Test
    public void deleteRemovesExactlyOneRow() {
        repository.add(URL, "First", 1_000L);
        repository.add("https://example.com/bookmark/two", "Second", 2_000L);
        assertEquals(2, repository.count());

        long id = repository.all().get(0).getId();
        assertEquals("deleting reports the row it removed", 1, repository.delete(id));
        assertEquals(1, repository.count());
        assertEquals("deleting a vanished row changes nothing", 0, repository.delete(id));
    }
}
