package com.savage.soulbrowser.domain;

/**
 * Local record state of a bookmark, mirroring {@code docs/ARCHITECTURE.md} §5.3:
 * bookmarks are persisted locally first and queued for a later synchronisation pass.
 */
public enum SyncState {
    /** Stored locally, not yet handed to the sync service. */
    PENDING(0),
    /** Confirmed by the sync service. */
    SYNCED(1);

    private final int code;

    SyncState(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static SyncState fromCode(int code) {
        return code == SYNCED.code ? SYNCED : PENDING;
    }
}
