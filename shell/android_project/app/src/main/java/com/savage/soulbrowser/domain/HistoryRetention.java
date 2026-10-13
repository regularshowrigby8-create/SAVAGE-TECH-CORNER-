package com.savage.soulbrowser.domain;

/** Retention rule for the local history table: newest entries win, the oldest are pruned. */
public final class HistoryRetention {

    /** Upper bound of locally stored history rows. */
    public static final int MAX_HISTORY_ENTRIES = 500;

    private HistoryRetention() {
    }

    /** How many oldest rows have to be deleted so that {@code total} stays within the cap. */
    public static int excessCount(int total, int maxEntries) {
        if (maxEntries < 0) {
            throw new IllegalArgumentException("maxEntries must not be negative");
        }
        return Math.max(0, total - maxEntries);
    }

    public static int excessCount(int total) {
        return excessCount(total, MAX_HISTORY_ENTRIES);
    }

    public static boolean needsPruning(int total) {
        return excessCount(total) > 0;
    }
}
