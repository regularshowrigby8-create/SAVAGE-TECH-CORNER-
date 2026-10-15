package com.savage.soulbrowser.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** AC-028, AC-029, AC-043: every WebView permission request is denied, with a stated reason. */
public class PermissionPolicyTest {

    private static final String TRUSTED = "https://savage-tech-corner.io/";

    @Test
    public void allWebPermissionRequestsAreDenied() {
        assertEquals(PermissionPolicy.Decision.DENY,
                PermissionPolicy.evaluate(TRUSTED, Collections.singleton("android.webkit.resource.AUDIO_CAPTURE")));
        assertEquals(PermissionPolicy.Decision.DENY,
                PermissionPolicy.evaluate(TRUSTED, Collections.singleton("android.webkit.resource.VIDEO_CAPTURE")));
        assertEquals(PermissionPolicy.Decision.DENY,
                PermissionPolicy.evaluate(TRUSTED, Collections.singleton("android.webkit.resource.PROTECTED_MEDIA_ID")));
    }

    @Test
    public void mixedAndUnknownResourcesAreDenied() {
        Set<String> mixed = new HashSet<>();
        mixed.add("android.webkit.resource.AUDIO_CAPTURE");
        mixed.add("android.webkit.resource.UNKNOWN_FUTURE_RESOURCE");
        assertEquals(PermissionPolicy.Decision.DENY, PermissionPolicy.evaluate(TRUSTED, mixed));
    }

    @Test
    public void untrustedOriginIsDeniedEvenWithNoResources() {
        assertEquals(PermissionPolicy.Decision.DENY, PermissionPolicy.evaluate(null, Collections.singleton("x")));
        assertEquals(PermissionPolicy.Decision.DENY,
                PermissionPolicy.evaluate("http://example.com", Collections.singleton("android.webkit.resource.AUDIO_CAPTURE")));
        assertEquals(PermissionPolicy.Decision.DENY,
                PermissionPolicy.evaluate("https://example.com", Collections.singleton("android.webkit.resource.AUDIO_CAPTURE")));
    }

    @Test
    public void emptyRequestIsDenied() {
        assertEquals(PermissionPolicy.Decision.DENY, PermissionPolicy.evaluate(TRUSTED, null));
        assertEquals(PermissionPolicy.Decision.DENY,
                PermissionPolicy.evaluate(TRUSTED, Collections.<String>emptySet()));
    }

    @Test
    public void geolocationIsNeverGranted() {
        assertFalse(PermissionPolicy.isGeolocationAllowed(TRUSTED));
        assertFalse(PermissionPolicy.isGeolocationAllowed(null));
    }

    @Test
    public void denialIsReported() {
        assertTrue(PermissionPolicy.denialReason(TRUSTED,
                Collections.singleton("android.webkit.resource.AUDIO_CAPTURE")).contains("no runtime permission"));
        assertTrue(PermissionPolicy.denialReason("https://example.com",
                Collections.singleton("android.webkit.resource.AUDIO_CAPTURE")).contains("untrusted origin"));
        assertTrue(PermissionPolicy.denialReason(TRUSTED,
                Collections.singleton("android.webkit.resource.FUTURE")).contains("unknown resource"));
        assertTrue(PermissionPolicy.denialReason(TRUSTED, null).contains("no resource"));
    }

    @Test
    public void noRuntimePermissionIsDeclared() {
        assertTrue(PermissionPolicy.DECLARED_RUNTIME_PERMISSIONS.isEmpty());
    }
}
