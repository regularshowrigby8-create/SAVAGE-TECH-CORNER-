package com.savage.soulbrowser.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** AC-040, AC-041: only the exact privileged origins may call the JavaScript bridge. */
public class TrustedOriginsTest {

    @Test
    public void privilegedExactHttpsHostsAllowed() {
        assertTrue(TrustedOrigins.isPrivileged("https://savage-tech-corner.io/"));
        assertTrue(TrustedOrigins.isPrivileged("https://savage-tech-corner.io/dashboard?a=1"));
        assertTrue(TrustedOrigins.isPrivileged("https://app.savage-tech-corner.io/settings"));
        assertTrue(TrustedOrigins.isPrivileged("https://SAVAGE-TECH-CORNER.IO/"));
        assertTrue(TrustedOrigins.isPrivileged("https://savage-tech-corner.io:443/"));
    }

    @Test
    public void spoofedHostsAreRejected() {
        assertFalse(TrustedOrigins.isPrivileged("https://evil-savage-tech-corner.io/"));
        assertFalse(TrustedOrigins.isPrivileged("https://savage-tech-corner.io.evil.com/"));
        assertFalse(TrustedOrigins.isPrivileged("https://notsavage-tech-corner.io/"));
        assertFalse(TrustedOrigins.isPrivileged("https://savage-tech-corner.io:8443/"));
        assertFalse(TrustedOrigins.isPrivileged("http://savage-tech-corner.io/"));
        assertFalse(TrustedOrigins.isPrivileged("https://user@savage-tech-corner.io/"));
    }

    @Test
    public void untrustedOriginsAreRejected() {
        assertFalse(TrustedOrigins.isPrivileged(null));
        assertFalse(TrustedOrigins.isPrivileged(""));
        assertFalse(TrustedOrigins.isPrivileged("about:blank"));
        assertFalse(TrustedOrigins.isPrivileged("https://example.com/"));
        assertFalse(TrustedOrigins.isPrivileged("https://savage-tech-corner.com/"));
    }
}
