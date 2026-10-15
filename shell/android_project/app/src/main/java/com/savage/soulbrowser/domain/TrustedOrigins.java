package com.savage.soulbrowser.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Origin allow-list for privileged JavaScript-bridge calls.
 *
 * <p>Only the exact Savage Tech Corner hosts over HTTPS are privileged. Sub-domain spoofing
 * ({@code evil-savage-tech-corner.io}), suffix spoofing ({@code savage-tech-corner.io.evil.com})
 * and non-default ports are all rejected.
 */
public final class TrustedOrigins {

    public static final Set<String> PRIVILEGED_HOSTS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("savage-tech-corner.io", "app.savage-tech-corner.io")));

    private TrustedOrigins() {
    }

    public static boolean isPrivileged(String rawUrl) {
        if (rawUrl == null) {
            return false;
        }
        UrlPolicy.Verdict verdict = UrlPolicy.verify(rawUrl);
        if (verdict != UrlPolicy.Verdict.ALLOWED) {
            return false;
        }
        String host = UrlPolicy.hostOf(rawUrl);
        if (host == null) {
            return false;
        }
        if (!PRIVILEGED_HOSTS.contains(host.toLowerCase(Locale.US))) {
            return false;
        }
        int port = portOf(rawUrl);
        return port == -1 || port == UrlPolicy.DEFAULT_HTTPS_PORT;
    }

    private static int portOf(String url) {
        try {
            return new java.net.URI(url.trim()).getPort();
        } catch (java.net.URISyntaxException e) {
            return -1;
        }
    }
}
