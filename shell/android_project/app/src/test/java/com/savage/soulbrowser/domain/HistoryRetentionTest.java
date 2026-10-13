package com.savage.soulbrowser.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** AC-035: the history table is bounded and prunes its oldest rows. */
public class HistoryRetentionTest {

    @Test
    public void excessCountMath() {
        assertEquals(0, HistoryRetention.excessCount(10, 500));
        assertEquals(0, HistoryRetention.excessCount(500, 500));
        assertEquals(1, HistoryRetention.excessCount(501, 500));
        assertEquals(25, HistoryRetention.excessCount(525, 500));
    }

    @Test
    public void needsPruningOnlyAboveCap() {
        assertFalse(HistoryRetention.needsPruning(HistoryRetention.MAX_HISTORY_ENTRIES));
        assertTrue(HistoryRetention.needsPruning(HistoryRetention.MAX_HISTORY_ENTRIES + 1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeCapIsRejected() {
        HistoryRetention.excessCount(1, -1);
    }
}
