package com.savage.soulbrowser.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed policy for WebView permission requests (camera, microphone, MIDI, geolocation …).
 *
 * <p>The application declares <em>no</em> runtime permission, therefore no WebView permission
 * request can ever be granted. Unknown resources are denied as well: an unrecognised request is
 * treated as an escalation attempt rather than as something to allow by default.
 *
 * <p>{@link #DECLARED_RUNTIME_PERMISSIONS} is asserted against the real
 * {@code AndroidManifest.xml} by {@code AndroidManifestContractTest}, so this policy cannot drift
 * away from what the app is actually allowed to ask the user for.
 */
public final class PermissionPolicy {

    public enum Decision { GRANT, DENY }

    /** Runtime permissions this application declares. Intentionally empty. */
    public static final Set<String> DECLARED_RUNTIME_PERMISSIONS = Collections.emptySet();

    private static final Map<String, String> RESOURCE_TO_PERMISSION;

    static {
        Map<String, String> map = new HashMap<>();
        map.put("android.webkit.resource.AUDIO_CAPTURE", "android.permission.RECORD_AUDIO");
        map.put("android.webkit.resource.VIDEO_CAPTURE", "android.permission.CAMERA");
        map.put("android.webkit.resource.MIDI_SYSEX", "android.permission.MIDI_SYSEX");
        map.put("android.webkit.resource.PROTECTED_MEDIA_ID", "android.permission.PROTECTED_MEDIA_ID");
        map.put("android.webkit.resource.GEOLOCATION", "android.permission.ACCESS_FINE_LOCATION");
        RESOURCE_TO_PERMISSION = Collections.unmodifiableMap(map);
    }

    private PermissionPolicy() {
    }

    public static Decision evaluate(String originUrl, Set<String> resources) {
        if (resources == null || resources.isEmpty()) {
            return Decision.DENY;
        }
        if (UrlPolicy.verify(originUrl) != UrlPolicy.Verdict.ALLOWED) {
            return Decision.DENY;
        }
        for (String resource : resources) {
            String requiredPermission = resource == null ? null : RESOURCE_TO_PERMISSION.get(resource);
            if (requiredPermission == null) {
                return Decision.DENY;
            }
            if (!DECLARED_RUNTIME_PERMISSIONS.contains(requiredPermission)) {
                return Decision.DENY;
            }
        }
        return Decision.GRANT;
    }

    /** Geolocation prompts are always denied; the app never asks the user for location. */
    public static boolean isGeolocationAllowed(String originUrl) {
        return Decision.GRANT == evaluate(originUrl,
                Collections.singleton("android.webkit.resource.GEOLOCATION"));
    }

    /** Human-readable reason used for the (never silent) denial log line. */
    public static String denialReason(String originUrl, Set<String> resources) {
        if (resources == null || resources.isEmpty()) {
            return "no resource requested";
        }
        if (!TrustedOrigins.isPrivileged(originUrl)) {
            return "untrusted origin";
        }
        for (String resource : resources) {
            if (!RESOURCE_TO_PERMISSION.containsKey(resource)) {
                return "unknown resource " + resource;
            }
        }
        return "no runtime permission declared by the manifest";
    }
}
