package com.savage.soulbrowser.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** AC-022, AC-040: the navigation security policy is fail-closed. */
public class UrlPolicyTest {

    @Test
    public void cleartextIsBlocked() {
        assertEquals(UrlPolicy.Verdict.BLOCKED_CLEARTEXT, UrlPolicy.verify("http://example.com/"));
        assertEquals(UrlPolicy.Verdict.BLOCKED_CLEARTEXT, UrlPolicy.verify("HTTP://EXAMPLE.com"));
        assertFalse(UrlPolicy.isAllowed("http://example.com"));
        assertTrue(UrlPolicy.isCleartext("http://example.com"));
    }

    @Test
    public void httpsIsAllowed() {
        assertEquals(UrlPolicy.Verdict.ALLOWED, UrlPolicy.verify("https://example.com"));
        assertEquals(UrlPolicy.Verdict.ALLOWED, UrlPolicy.verify("https://example.com/path?q=1#frag"));
        assertTrue(UrlPolicy.isAllowed("https://savage-tech-corner.io/"));
        assertTrue(UrlPolicy.isHttps("https://example.com"));
    }

    @Test
    public void dangerousSchemesAreBlocked() {
        String[] blocked = {
                "javascript:alert(1)",
                "data:text/html,<h1>x</h1>",
                "file:///etc/passwd",
                "content://com.example/media/1",
                "blob:https://example.com/1234",
                "about:blank",
                "intent://scan/#Intent;scheme=zxing;end",
                "ftp://example.com/file.txt",
                "tel:+15551234567",
                "mailto:someone@example.com"
        };
        for (String candidate : blocked) {
            assertFalse("must be blocked: " + candidate, UrlPolicy.isAllowed(candidate));
            assertTrue("must be a blocked scheme: " + candidate,
                    UrlPolicy.verify(candidate) == UrlPolicy.Verdict.BLOCKED_SCHEME
                            || UrlPolicy.verify(candidate) == UrlPolicy.Verdict.MALFORMED);
        }
    }

    @Test
    public void credentialsAreBlocked() {
        assertEquals(UrlPolicy.Verdict.BLOCKED_CREDENTIALS,
                UrlPolicy.verify("https://user:secret@example.com/"));
        assertEquals(UrlPolicy.Verdict.BLOCKED_CREDENTIALS, UrlPolicy.verify("https://user@example.com/"));
    }

    @Test
    public void malformedInputsAreRejected() {
        assertEquals(UrlPolicy.Verdict.EMPTY, UrlPolicy.verify(null));
        assertEquals(UrlPolicy.Verdict.EMPTY, UrlPolicy.verify("   "));
        assertEquals(UrlPolicy.Verdict.MALFORMED, UrlPolicy.verify("not a url"));
        assertEquals(UrlPolicy.Verdict.MALFORMED, UrlPolicy.verify("https://"));
        assertEquals(UrlPolicy.Verdict.MALFORMED, UrlPolicy.verify("https://example.com/\nHeader: 1"));
        StringBuilder oversized = new StringBuilder("https://example.com/");
        for (int i = 0; i < 5000; i++) {
            oversized.append('a');
        }
        assertEquals(UrlPolicy.Verdict.MALFORMED, UrlPolicy.verify(oversized.toString()));
    }

    @Test
    public void hostValidationRejectsOdditiesAndAcceptsInternationalNames() {
        assertEquals(UrlPolicy.Verdict.ALLOWED, UrlPolicy.verify("https://münchen.example/"));
        // a space makes the input an invalid URI, so it never reaches host validation
        assertEquals(UrlPolicy.Verdict.MALFORMED, UrlPolicy.verify("https://exa mple.com/"));
        assertEquals(UrlPolicy.Verdict.BLOCKED_HOST, UrlPolicy.verify("https://example.com%00.evil/"));
    }

    @Test
    public void nonDefaultHttpsPortIsAllowed() {
        assertEquals(UrlPolicy.Verdict.ALLOWED, UrlPolicy.verify("https://example.com:8443/path"));
    }

    @Test
    public void normalizeKeyCanonicalisesEquivalentUrls() {
        assertEquals(UrlPolicy.normalizeKey("https://example.com/"),
                UrlPolicy.normalizeKey("HTTPS://Example.com"));
        assertEquals(UrlPolicy.normalizeKey("https://example.com/"),
                UrlPolicy.normalizeKey("https://example.com:443/"));
        assertEquals(UrlPolicy.normalizeKey("https://example.com/a"),
                UrlPolicy.normalizeKey("https://example.com/a#section"));
        assertTrue(UrlPolicy.normalizeKey("https://example.com/a?b=1")
                .endsWith("/a?b=1"));
        // A trailing slash is the same page: storing both would count one visit twice.
        assertEquals(UrlPolicy.normalizeKey("https://example.com/a"),
                UrlPolicy.normalizeKey("https://example.com/a/"));
        assertEquals(UrlPolicy.normalizeKey("https://example.com/a/"),
                UrlPolicy.normalizeKey("https://example.com/a/#section"));
        assertEquals(UrlPolicy.normalizeKey("https://example.com/a?b=1"),
                UrlPolicy.normalizeKey("https://example.com/a/?b=1"));
        // The site root keeps its slash, and a non-root path keeps its shape otherwise.
        assertEquals("https://example.com/", UrlPolicy.normalizeKey("https://example.com"));
        assertEquals("https://example.com/a/b", UrlPolicy.normalizeKey("https://example.com/a/b/"));
    }

    @Test
    public void originOfBuildsSchemeHostPort() {
        assertEquals("https://example.com", UrlPolicy.originOf("https://example.com/x"));
        assertEquals("https://example.com:8443", UrlPolicy.originOf("https://example.com:8443/x"));
        assertEquals("https://example.com", UrlPolicy.originOf("https://example.com:443/x"));
        assertEquals(null, UrlPolicy.originOf("not a url"));
    }
}
