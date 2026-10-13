package com.savage.soulbrowser.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/** AC-011, AC-018: no row is ever rendered blank. */
public class DisplayTextTest {

    @Test
    public void titleFallsBackToHost() {
        assertEquals("example.com", DisplayText.titleFor(null, "https://example.com/path"));
        assertEquals("example.com", DisplayText.titleFor("   ", "https://example.com/path"));
        assertEquals("www.example.com", DisplayText.titleFor("", "https://www.example.com/path"));
        assertEquals("", DisplayText.titleFor(null, null));
    }

    @Test
    public void realTitleWinsAndIsCollapsed() {
        assertEquals("Example Domain", DisplayText.titleFor("  Example \n Domain ", "https://example.com"));
    }

    @Test
    public void titleForNeverReturnsBlankForAUsableUrl() {
        assertFalse(DisplayText.titleFor(null, "https://example.com/").trim().isEmpty());
    }

    @Test
    public void shortenKeepsBothEnds() {
        String shortened = DisplayText.shorten("https://example.com/a/very/long/path/to/a/page.html", 24);
        assertEquals(24, shortened.length());
        assertEquals("https://exa…/a/page.html", shortened);
        assertEquals("short", DisplayText.shorten("short", 24));
    }

    @Test
    public void hostForIsEmptyForInvalidInput() {
        assertEquals("example.com", DisplayText.hostFor("https://example.com/x"));
        assertEquals("", DisplayText.hostFor("not a url"));
        assertEquals("", DisplayText.hostFor(null));
    }
}
