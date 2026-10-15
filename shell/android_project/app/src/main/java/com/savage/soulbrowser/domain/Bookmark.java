package com.savage.soulbrowser.domain;

import java.util.Objects;

/** Immutable bookmark record. */
public final class Bookmark {

    private final long id;
    private final String url;
    private final String title;
    private final long createdAtMillis;
    private final SyncState syncState;

    public Bookmark(long id, String url, String title, long createdAtMillis, SyncState syncState) {
        this.id = id;
        this.url = url;
        this.title = title;
        this.createdAtMillis = createdAtMillis;
        this.syncState = syncState == null ? SyncState.PENDING : syncState;
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

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public SyncState getSyncState() {
        return syncState;
    }

    public String getDedupeKey() {
        return UrlPolicy.normalizeKey(url);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Bookmark)) {
            return false;
        }
        Bookmark that = (Bookmark) other;
        return id == that.id
                && createdAtMillis == that.createdAtMillis
                && Objects.equals(url, that.url)
                && Objects.equals(title, that.title)
                && syncState == that.syncState;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, url, title, createdAtMillis, syncState);
    }

    @Override
    public String toString() {
        return "Bookmark{" + id + ", " + url + ", " + syncState + "}";
    }
}
