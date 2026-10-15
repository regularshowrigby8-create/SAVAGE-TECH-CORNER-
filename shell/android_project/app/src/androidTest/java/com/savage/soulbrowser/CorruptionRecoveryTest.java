package com.savage.soulbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.savage.soulbrowser.data.BookmarkRepository;
import com.savage.soulbrowser.data.BrowserDatabase;
import com.savage.soulbrowser.data.BrowserStore;
import com.savage.soulbrowser.data.HistoryRepository;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * AC-038 and AC-039: a corrupted or missing local database is recovered instead of crashing, a
 * schema change recreates the tables, and a fresh database answers queries with empty lists.
 */
public class CorruptionRecoveryTest {

    private static final String URL = "https://example.com/recovered";

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
    public void corruptDatabaseIsRecreated() throws IOException {
        File file = context.getDatabasePath(BrowserDatabase.DATABASE_NAME);
        File parent = file.getParentFile();
        assertNotNull(parent);
        assertTrue("the database directory must be creatable", parent.exists() || parent.mkdirs());
        byte[] garbage = "this is not a sqlite database, it is a test fixture"
                .getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[512]);
            out.write(garbage);
        }
        assertTrue("the fixture must really be on disk", file.length() > 0);

        BrowserDatabase database = BrowserStore.open(context);
        try {
            HistoryRepository history = new HistoryRepository(database);
            BookmarkRepository bookmarks = new BookmarkRepository(database);

            assertEquals("the store is usable again", 0, history.count());
            assertEquals(0, bookmarks.count());
            assertTrue("the regenerated store accepts writes",
                    history.recordVisit(URL, "After corruption", 1_000L));
            assertEquals(1, history.count());
            assertEquals("the recovery really replaced the file",
                    1, new HistoryRepository(database).recent(1).size());
        } finally {
            database.close();
        }
    }

    @Test
    public void schemaVersionChangeRecreatesTables() {
        BrowserDatabase version1 = new BrowserDatabase(context, "savage_test_schema.db", 1);
        assertTrue(new HistoryRepository(version1).recordVisit(URL, "Old schema", 1_000L));
        version1.close();

        BrowserDatabase version2 = new BrowserDatabase(context, "savage_test_schema.db", 2);
        try {
            assertEquals("an unexpected schema is replaced, not read", 0,
                    new HistoryRepository(version2).count());
            assertTrue("the recreated schema is writable",
                    new HistoryRepository(version2).recordVisit(URL, "New schema", 2_000L));
        } finally {
            version2.close();
            assertTrue("cleanup", BrowserDatabase.deleteDatabaseFiles(context, "savage_test_schema.db"));
        }
    }

    @Test
    public void missingDataIsRecoveredWithoutReadingStaleFields() {
        assertFalse("nothing exists yet", context.getDatabasePath(BrowserDatabase.DATABASE_NAME).exists());

        BrowserDatabase database = BrowserStore.open(context);
        try {
            assertTrue("a missing store is created empty",
                    new HistoryRepository(database).isEmpty());
            assertTrue(new BookmarkRepository(database).all().isEmpty());
        } finally {
            database.close();
        }
    }
}
