package com.savage.soulbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.savage.soulbrowser.browser.SecureSoulBridge;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * AC-040, AC-041 and AC-042: the privileged JavaScript bridge is origin-gated and validates every
 * payload before it is used.
 *
 * <p>The bridge reads the calling page URL through the {@code PageUrlProvider} seam, so the gate can
 * be driven with a deterministic page URL while everything else - validation, sanitizing, the
 * returned denial token - is the production code path. The production constructor wraps the real
 * {@code WebView} in the same provider type, and the platform only calls these methods through
 * {@code @JavascriptInterface}, which is present on the real methods.
 */
@RunWith(AndroidJUnit4.class)
public class BridgePolicyTest {

    private static final String PRIVILEGED = "https://savage-tech-corner.io/dashboard";
    private static final String OTHER_ORIGIN = "https://evil.example.com/savage-tech-corner.io";

    @Test
    public void feedbackValidation() {
        RecordingToastHost host = new RecordingToastHost();
        SecureSoulBridge bridge = new SecureSoulBridge(() -> PRIVILEGED, host, "3.0.0");

        assertTrue("a plain message is accepted by the bridge",
                bridge.postFeedback("Hello from the dashboard"));
        assertEquals(1, host.messages.size());
        assertEquals("Hello from the dashboard", host.messages.get(0));

        assertFalse("control characters are rejected",
                bridge.postFeedback("line\u0000break"));
        assertFalse("injection attempts are rejected",
                bridge.postFeedback("<script>alert(1)</script>"));
        assertFalse("an oversized payload is rejected",
                bridge.postFeedback(repeat("a", 501)));
        assertEquals("only the valid call reached the UI", 1, host.messages.size());

        assertTrue("the payload length limit is exactly 500 characters",
                bridge.postFeedback(repeat("b", 500)));
        assertEquals(2, host.messages.size());
        assertFalse("null is rejected instead of throwing", bridge.postFeedback(null));
        assertEquals(2, host.messages.size());
    }

    @Test
    public void untrustedOriginsAreRejected() {
        RecordingToastHost host = new RecordingToastHost();
        SecureSoulBridge bridge = new SecureSoulBridge(() -> OTHER_ORIGIN, host, "3.0.0");

        assertEquals("no version is leaked to an untrusted page",
                SecureSoulBridge.ACCESS_DENIED, bridge.getAppVersion());
        assertFalse("an untrusted page cannot post feedback",
                bridge.postFeedback("Let me in"));
        assertTrue("nothing was shown on behalf of the untrusted page", host.messages.isEmpty());
    }

    @Test
    public void spoofedAndUnknownOriginsAreRejected() {
        for (String url : new String[] {
                "https://savage-tech-corner.io.evil.example.com/",
                "http://savage-tech-corner.io/",
                "https://savage-tech-corner.io@evil.example.com/",
                "https://not-savage.example.com/",
                null}) {
            RecordingToastHost host = new RecordingToastHost();
            SecureSoulBridge bridge = new SecureSoulBridge(() -> url, host, "3.0.0");
            assertEquals("must be denied for " + url,
                    SecureSoulBridge.ACCESS_DENIED, bridge.getAppVersion());
            assertFalse("must not accept feedback for " + url, bridge.postFeedback("payload"));
            assertTrue(host.messages.isEmpty());
        }
    }

    @Test
    public void privilegedOriginGetsTheVersionAndSanitizedEcho() {
        RecordingToastHost host = new RecordingToastHost();
        java.util.concurrent.atomic.AtomicReference<String> page =
                new java.util.concurrent.atomic.AtomicReference<>(PRIVILEGED);
        SecureSoulBridge bridge = new SecureSoulBridge(page::get, host, "3.0.0");

        assertEquals("3.0.0", bridge.getAppVersion());
        assertTrue(bridge.postFeedback("  spaced   out  "));
        assertEquals("the message is whitespace-normalised before it is shown",
                "spaced out", host.messages.get(0));

        // The gate is evaluated for every call: navigating away revokes access immediately.
        page.set(OTHER_ORIGIN);
        assertEquals("the gate must be re-evaluated per call, not cached",
                SecureSoulBridge.ACCESS_DENIED, bridge.getAppVersion());
        assertFalse(bridge.postFeedback("after navigation"));
        assertEquals("nothing is echoed once the page is no longer privileged",
                1, host.messages.size());
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static final class RecordingToastHost implements SecureSoulBridge.ToastHost {
        final List<String> messages = new ArrayList<>();

        @Override
        public void showToast(String message) {
            assertNotNull("a toast must always carry a message", message);
            messages.add(message);
        }
    }
}
