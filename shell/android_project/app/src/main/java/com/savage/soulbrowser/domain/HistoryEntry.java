package com.savage.soulbrowser.domain;

import java.util.Objects;

/** Immutable history record: one row per canonical URL, with a visit counter. */
public final class HistoryEntry {

    private final long id;
    private final String url;
    private final String title;
    private final long lastVisitMillis;
    private final int visitCount;

    public HistoryEntry(long id, String url, String title, long lastVisitMillis, int visitCount) {
        this.id = id;
        this.url = url;
        this.title = title;
        this.lastVisitMillis = lastVisitMillis;
        this.visitCount = visitCount;
    }

    public long getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public long getLastVisitMillis() {
        return lastVisitMillis;
    }

    public int getVisitCount() {
        return visitCount;
    }

    public String getDedupeKey() {
        return UrlPolicy.normalizeKey(url);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof HistoryEntry)) {
            return false;
        }
        HistoryEntry that = (HistoryEntry) other;
        return id == that.id
                && lastVisitMillis == that.lastVisitMillis
                && visitCount == that.visitCount
                && Objects.equals(url, that.url)
                && Objects.equals(title, that.title);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, url, title, lastVisitMillis, visitCount);
    }

    @Override
    public String toString() {
        return "HistoryEntry{" + id + ", " + url + ", visits=" + visitCount + "}";
    }
}
