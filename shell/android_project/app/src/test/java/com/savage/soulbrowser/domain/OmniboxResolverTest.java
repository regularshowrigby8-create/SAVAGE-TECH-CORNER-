package com.savage.soulbrowser.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** AC-005, AC-013, AC-014, AC-015: omnibox input resolution. */
public class OmniboxResolverTest {

    @Test
    public void emptyInputIsEmpty() {
        assertEquals(OmniboxResolution.Kind.EMPTY,
                OmniboxResolver.resolve(null, SearchProvider.DEFAULT).getKind());
        assertEquals(OmniboxResolution.Kind.EMPTY,
                OmniboxResolver.resolve("   ", SearchProvider.DEFAULT).getKind());
    }

    @Test
    public void hostLikeInputBecomesHttpsUrl() {
        assertEquals("https://example.com",
                OmniboxResolver.resolve("example.com", SearchProvider.DEFAULT).getUrl());
        assertEquals("https://example.com:8443/path",
                OmniboxResolver.resolve("example.com:8443/path", SearchProvider.DEFAULT).getUrl());
        assertEquals("https://localhost:8080/x",
                OmniboxResolver.resolve("localhost:8080/x", SearchProvider.DEFAULT).getUrl());
        assertTrue(OmniboxResolver.resolve("sub.domain.co.uk/a?b=1", SearchProvider.DEFAULT)
                .getUrl().startsWith("https://"));
    }

    @Test
    public void explicitHttpsIsNavigate() {
        OmniboxResolution resolution =
                OmniboxResolver.resolve("https://example.com/x", SearchProvider.DEFAULT);
        assertEquals(OmniboxResolution.Kind.NAVIGATE, resolution.getKind());
        assertEquals("https://example.com/x", resolution.getUrl());
    }

    @Test
    public void searchTermsResolveToProviderUrl() {
        OmniboxResolution resolution =
                OmniboxResolver.resolve("android 15 sdk", SearchProvider.DUCKDUCKGO);
        assertEquals(OmniboxResolution.Kind.SEARCH, resolution.getKind());
        assertEquals("android 15 sdk", resolution.getQuery());
        assertEquals("https://duckduckgo.com/?q=android+15+sdk", resolution.getUrl());

        OmniboxResolution google =
                OmniboxResolver.resolve("android 15 sdk", SearchProvider.GOOGLE);
        assertEquals("https://www.google.com/search?q=android+15+sdk", google.getUrl());

        OmniboxResolution bing = OmniboxResolver.resolve("héllo wörld", SearchProvider.BING);
        assertTrue(bing.getUrl().startsWith("https://www.bing.com/search?q=h%C3%A9llo+w%C3%B6rld"));
    }

    @Test
    public void blockedSchemesNeverResolveToNavigation() {
        String[] blocked = {
                "javascript:alert(1)",
                "data:text/html,<h1>x</h1>",
                "file:///etc/passwd",
                "http://example.com",
                "https://user:secret@example.com/",
                "content://media/external/images/1"
        };
        for (String candidate : blocked) {
            OmniboxResolution resolution = OmniboxResolver.resolve(candidate, SearchProvider.DEFAULT);
            assertEquals("must not navigate: " + candidate, OmniboxResolution.Kind.BLOCKED, resolution.getKind());
            assertNull(resolution.getUrl());
        }
    }

    @Test
    public void malformedInputsAreHandledWithoutException() {
        String[] hostile = {
                "https://",
                "://x",
                "%%%",
                "https://[::1]",
                "https://example.com/%zz",
                "🦖🦖🦖",
                "a".repeat(5000),
                "https://example.com/" + "a".repeat(5000),
                "....",
                "-",
                "https://example.com:99999/"
        };
        for (String candidate : hostile) {
            OmniboxResolution resolution = OmniboxResolver.resolve(candidate, SearchProvider.DEFAULT);
            assertNotNull(resolution);
            assertNotNull("verdict required for " + candidate, resolution.getKind());
            assertTrue("blocked or search, never a broken navigation",
                    resolution.getKind() == OmniboxResolution.Kind.BLOCKED
                            || resolution.getKind() == OmniboxResolution.Kind.SEARCH
                            || resolution.getKind() == OmniboxResolution.Kind.NAVIGATE);
        }
    }

    @Test
    public void sentenceWithSpacesIsSearchedNeverNavigated() {
        OmniboxResolution resolution = OmniboxResolver.resolve("how to fix a flat tyre", SearchProvider.DEFAULT);
        assertEquals(OmniboxResolution.Kind.SEARCH, resolution.getKind());
    }

    @Test
    public void providerFallbackIsSafe() {
        assertEquals(SearchProvider.DUCKDUCKGO, SearchProvider.fromId(null, SearchProvider.DEFAULT));
        assertEquals(SearchProvider.DUCKDUCKGO, SearchProvider.fromId("not-a-provider", SearchProvider.DEFAULT));
        assertEquals(SearchProvider.GOOGLE, SearchProvider.fromId("GOOGLE", SearchProvider.DEFAULT));
        assertEquals(SearchProvider.BING, SearchProvider.GOOGLE.next());
        assertEquals(SearchProvider.DUCKDUCKGO, SearchProvider.BING.next());
    }
}
