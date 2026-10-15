package com.savage.soulbrowser.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** AC-021: there is no code path that proceeds past a TLS error. */
public class SslDecisionTest {

    @Test
    public void cancelIsAlwaysCalled() {
        assertEquals(SslDecision.Action.CANCEL, SslDecision.decide("example.com", 0));
        assertEquals(SslDecision.Action.CANCEL, SslDecision.decide("example.com", 3));
        assertEquals(SslDecision.Action.CANCEL, SslDecision.decide("savage-tech-corner.io", 5));
        assertEquals(SslDecision.Action.CANCEL, SslDecision.decide(null, -1));
        assertEquals(SslDecision.Action.CANCEL, SslDecision.decide("example.com", 9999));
    }

    @Test
    public void onlyOneActionExistsSoNoBypassCanBeSelected() {
        assertEquals(1, SslDecision.Action.values().length);
        assertTrue(SslDecision.decide("example.com", 3).name().equals("CANCEL"));
        assertFalse(SslDecision.Action.valueOf("CANCEL") == null);
    }

    @Test
    public void describeCoversKnownAndUnknownCodes() {
        assertEquals("certificate expired", SslDecision.describe(1));
        assertEquals("hostname mismatch", SslDecision.describe(2));
        assertEquals("certificate has been revoked", SslDecision.describe(5));
        assertEquals("TLS error 42", SslDecision.describe(42));
    }
}
