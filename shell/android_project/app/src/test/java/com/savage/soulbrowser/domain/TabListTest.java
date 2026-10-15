package com.savage.soulbrowser.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** AC-008, AC-010, AC-012: tab rules never leave the browser without a tab. */
public class TabListTest {

    private static final String HOME = "https://savage-tech-corner.io/";

    @Test
    public void startsWithOneHomeTab() {
        TabList list = TabList.withHomeTab(HOME);
        assertEquals(1, list.size());
        assertEquals(0, list.activeIndex());
        assertEquals(HOME, list.active().getUrl());
    }

    @Test
    public void openTabActivatesTheNewTab() {
        TabList list = TabList.withHomeTab(HOME);
        String id = list.openTab("https://example.com/");
        assertNotNull(id);
        assertEquals(2, list.size());
        assertEquals(1, list.activeIndex());
        assertEquals("https://example.com/", list.active().getUrl());
    }

    @Test
    public void switchAndCloseKeepsAtLeastOneTab() {
        TabList list = TabList.withHomeTab(HOME);
        String second = list.openTab("https://example.com/");
        list.activate(list.tabs().get(0).getId());
        assertEquals(HOME, list.active().getUrl());
        assertFalse(list.close(second));
        assertEquals(1, list.size());
        assertEquals(HOME, list.active().getUrl());
    }

    @Test
    public void closingLastTabOpensHomeTab() {
        TabList list = TabList.withHomeTab(HOME);
        String only = list.active().getId();
        assertTrue(list.close(only));
        assertEquals(1, list.size());
        assertEquals(HOME, list.active().getUrl());
        assertFalse(list.contains(only));
    }

    @Test
    public void closingNonActiveTabKeepsActiveSelection() {
        TabList list = TabList.withHomeTab(HOME);
        String first = list.active().getId();
        String second = list.openTab("https://example.com/");
        assertFalse(list.close(first));
        assertEquals(1, list.size());
        assertEquals("https://example.com/", list.active().getUrl());
        assertTrue(list.contains(second));
    }

    @Test
    public void respectsMaximumTabCount() {
        TabList list = TabList.withHomeTab(HOME);
        for (int i = 0; i < TabList.MAX_TABS + 5; i++) {
            list.openTab("https://example.com/" + i);
        }
        assertEquals(TabList.MAX_TABS, list.size());
        assertNull(list.openTab("https://example.com/overflow"));
    }

    @Test
    public void updateChangesUrlAndTitle() {
        TabList list = TabList.withHomeTab(HOME);
        String id = list.active().getId();
        list.update(id, "https://example.com/", "Example");
        assertEquals("https://example.com/", list.active().getUrl());
        assertEquals("Example", list.active().getTitle());
    }

    @Test
    public void restoreFromEmptyOrCorruptStateGivesHomeTab() {
        assertEquals(1, TabList.restore(HOME, null, 0).size());
        assertEquals(1, TabList.restore(HOME, new ArrayList<>(), 3).size());
        List<TabList.Tab> corrupt = new ArrayList<>();
        corrupt.add(new TabList.Tab("x", null, null));
        TabList restored = TabList.restore(HOME, corrupt, 5);
        assertEquals(1, restored.size());
        assertEquals(HOME, restored.active().getUrl());
    }

    @Test
    public void restoreKeepsOrderAndClampsActiveIndex() {
        List<TabList.Tab> tabs = new ArrayList<>();
        tabs.add(new TabList.Tab("a", "https://one.example/", "One"));
        tabs.add(new TabList.Tab("b", "https://two.example/", "Two"));
        TabList restored = TabList.restore(HOME, tabs, 9);
        assertEquals(2, restored.size());
        assertEquals(1, restored.activeIndex());
        assertEquals("https://two.example/", restored.active().getUrl());
    }
}
