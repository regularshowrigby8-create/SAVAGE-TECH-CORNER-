package com.savage.soulbrowser.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pure tab model: identifiers, URLs, titles, the active index and the invariant that there is
 * always at least one tab. The Android layer owns the {@code WebView} instances; this class owns
 * the rules, which makes them unit-testable without an emulator.
 */
public final class TabList {

    public static final int MAX_TABS = 16;

    /** Immutable snapshot of a tab. */
    public static final class Tab {
        private final String id;
        private final String url;
        private final String title;

        public Tab(String id, String url, String title) {
            this.id = id;
            this.url = url;
            this.title = title;
        }

        public String getId() {
            return id;
        }

        public String getUrl() {
            return url;
        }

        public String getTitle() {
            return title;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Tab)) {
                return false;
            }
            Tab that = (Tab) other;
            return Objects.equals(id, that.id)
                    && Objects.equals(url, that.url)
                    && Objects.equals(title, that.title);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, url, title);
        }

        @Override
        public String toString() {
            return "Tab{" + id + ", " + url + "}";
        }
    }

    private final String homeUrl;
    private final List<Tab> tabs = new ArrayList<>();
    private int activeIndex = -1;
    private int nextSequence = 1;

    private TabList(String homeUrl) {
        this.homeUrl = homeUrl;
    }

    public static TabList withHomeTab(String homeUrl) {
        TabList list = new TabList(homeUrl);
        list.tabs.add(new Tab("tab-1", homeUrl, null));
        list.activeIndex = 0;
        list.nextSequence = 2;
        return list;
    }

    /** Rebuilds a tab list from persisted state; an empty or corrupt input yields the home tab. */
    public static TabList restore(String homeUrl, List<Tab> restored, int restoredActiveIndex) {
        if (restored == null || restored.isEmpty()) {
            return withHomeTab(homeUrl);
        }
        TabList list = new TabList(homeUrl);
        for (Tab tab : restored) {
            if (list.tabs.size() >= MAX_TABS) {
                break;
            }
            String url = tab != null && tab.getUrl() != null ? tab.getUrl() : homeUrl;
            String title = tab == null ? null : tab.getTitle();
            list.tabs.add(new Tab("tab-" + list.nextSequence++, url, title));
        }
        list.activeIndex = Math.max(0, Math.min(restoredActiveIndex, list.tabs.size() - 1));
        return list;
    }

    /**
     * Opens a new tab and activates it.
     *
     * @return the new tab id, or {@code null} when {@link #MAX_TABS} is already reached.
     */
    public String openTab(String url) {
        if (tabs.size() >= MAX_TABS) {
            return null;
        }
        String id = "tab-" + nextSequence++;
        tabs.add(new Tab(id, url, null));
        activeIndex = tabs.size() - 1;
        return id;
    }

    public void activate(String id) {
        int index = indexOf(id);
        if (index >= 0) {
            activeIndex = index;
        }
    }

    /**
     * Closes a tab. The last remaining tab is replaced by a fresh home tab, so the browser is
     * never left with zero tabs.
     *
     * @return {@code true} when the closed tab was the active one (the caller must switch views)
     */
    public boolean close(String id) {
        int index = indexOf(id);
        if (index < 0) {
            return false;
        }
        boolean wasActive = index == activeIndex;
        tabs.remove(index);
        if (tabs.isEmpty()) {
            openTab(homeUrl);
            return true;
        }
        if (wasActive) {
            activeIndex = Math.min(index, tabs.size() - 1);
        } else if (index < activeIndex) {
            activeIndex--;
        }
        return wasActive;
    }

    public void update(String id, String url, String title) {
        int index = indexOf(id);
        if (index < 0) {
            return;
        }
        Tab current = tabs.get(index);
        String nextUrl = url == null ? current.getUrl() : url;
        String nextTitle = title == null ? current.getTitle() : title;
        tabs.set(index, new Tab(current.getId(), nextUrl, nextTitle));
    }

    public int indexOf(String id) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    public boolean contains(String id) {
        return indexOf(id) >= 0;
    }

    public Tab active() {
        return activeIndex >= 0 && activeIndex < tabs.size() ? tabs.get(activeIndex) : null;
    }

    public int activeIndex() {
        return activeIndex;
    }

    public int size() {
        return tabs.size();
    }

    public List<Tab> tabs() {
        return Collections.unmodifiableList(new ArrayList<>(tabs));
    }

    public String homeUrl() {
        return homeUrl;
    }
}
