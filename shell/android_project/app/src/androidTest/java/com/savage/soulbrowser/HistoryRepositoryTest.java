package com.savage.soulbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.savage.soulbrowser.data.BrowserDatabase;
import com.savage.soulbrowser.data.HistoryRepository;
import com.savage.soulbrowser.domain.HistoryEntry;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * AC-007 and AC-035: a visit is recorded once per page with a visit counter, revisiting updates the
 * existing row instead of adding another one, and refused addresses never enter the history.
 */
public class HistoryRepositoryTest {

    private static final String URL = "https://example.com/history/one";

    private Context context;
    private BrowserDatabase database;
    private HistoryRepository repository;

    @Before
    public void setUp() {
        context = TestSupport.context();
        TestSupport.wipeLocalData();
        database = new BrowserDatabase(context);
        repository = new HistoryRepository(database);
    }

    @After
    public void tearDown() {
        database.close();
        TestSupport.wipeLocalData();
    }

    @Test
    public void revisitUpdatesExistingRow() {
        assertTrue(repository.recordVisit(URL, "First visit", 1_000L));
        assertEquals(1, repository.count());

        assertTrue("a revisit is still a recorded event",
                repository.recordVisit(URL + "#anchor", "Second visit", 2_000L));

        assertEquals("a revisit must not add a row", 1, repository.count());
        HistoryEntry entry = repository.recent(10).get(0);
        assertEquals("the latest title wins", "Second visit", entry.getTitle());
        assertEquals("the timestamp moves forward", 2_000L, entry.getLastVisitMillis());
        assertTrue("the view count grows", entry.getVisitCount() >= 2);

        List<HistoryEntry> recent = repository.recent(10);
        assertEquals("newest first", URL, recent.get(0).getUrl());
    }

    @Test
    public void refusedUrlIsNotRecorded() {
        assertFalse(repository.recordVisit("http://example.com/cleartext", "x", 1_000L));
        assertFalse(repository.recordVisit("javascript:alert(1)", "x", 1_000L));
        assertFalse(repository.recordVisit("file:///etc/hosts", "x", 1_000L));
        assertTrue("nothing unsafe is written to the history", repository.isEmpty());
        assertEquals(0, repository.count());
    }

    @Test
    public void deleteAndClearBehaviour() {
        repository.recordVisit(URL, "One", 1_000L);
        repository.recordVisit("https://example.com/history/two", "Two", 2_000L);
        assertEquals(2, repository.count());

        long id = repository.recent(1).get(0).getId();
        assertEquals(1, repository.delete(id));
        assertEquals(1, repository.count());

        assertEquals("clearing removes the rest", 1, repository.clear());
        assertTrue(repository.isEmpty());
        assertEquals("clearing an empty history is a no-op", 0, repository.clear());
    }

    @Test
    public void retentionPrunesOldestEntries() {
        for (int i = 0; i < 12; i++) {
            repository.recordVisit("https://example.com/page/" + i, "Page " + i, 1_000L + i);
        }
        assertEquals(12, repository.count());

        int pruned = repository.pruneTo(10);
        assertEquals("pruning reports how many rows it removed", 2, pruned);
        assertEquals(10, repository.count());
        assertEquals("the newest entry survives", "https://example.com/page/11",
                repository.recent(1).get(0).getUrl());
        assertEquals("pruning to a larger limit changes nothing", 0, repository.pruneTo(50));
    }
}
