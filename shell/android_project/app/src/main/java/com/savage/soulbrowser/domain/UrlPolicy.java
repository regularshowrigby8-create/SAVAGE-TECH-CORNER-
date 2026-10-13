package com.savage.soulbrowser.domain;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The single navigation security policy of Savage Browser.
 *
 * <p>Pure Java (no Android imports) so that every decision is unit-testable on the JVM.
 * The policy is fail-closed: anything that is not a well-formed, credential-free {@code https}
 * URL is refused.
 */
public final class UrlPolicy {

    /** Result of evaluating a candidate URL. */
    public enum Verdict {
        ALLOWED,
        EMPTY,
        MALFORMED,
        /** Cleartext {@code http} — refused because this browser never sends cleartext. */
        BLOCKED_CLEARTEXT,
        /** {@code javascript:}, {@code data:}, {@code file:}, {@code content:}, … — refused. */
        BLOCKED_SCHEME,
        /** Credentials embedded in the URL ({@code https://user:pass@host}) — refused. */
        BLOCKED_CREDENTIALS,
        /** No usable host name — refused. */
        BLOCKED_HOST
    }

    public static final String SCHEME_HTTPS = "https";
    public static final String SCHEME_HTTP = "http";
    public static final int DEFAULT_HTTPS_PORT = 443;
    public static final int MAX_URL_LENGTH = 4096;

    /** Schemes that must never reach the WebView, even though Android knows how to handle some. */
    private static final Set<String> DANGEROUS_SCHEMES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    "javascript", "data", "file", "content", "blob", "about", "intent",
                    "android-app", "chrome", "chrome-native", "view-source", "jar", "resource",
                    "filesystem", "ftp", "ftps", "ws", "wss", "mailto", "tel", "sms", "smsto",
                    "market", "geo", "magnet", "bitcoin", "ethereum", "webrtc")));

    private UrlPolicy() {
    }

    /** @return {@code true} when the URL is a cleartext {@code http} URL. */
    public static boolean isCleartext(String url) {
        return url != null && SCHEME_HTTP.equalsIgnoreCase(schemeOf(url));
    }

    /** @return {@code true} when the URL uses the encrypted {@code https} scheme. */
    public static boolean isHttps(String url) {
        return url != null && SCHEME_HTTPS.equalsIgnoreCase(schemeOf(url));
    }

    /** @return {@code true} only when {@link #verify(String)} returns {@link Verdict#ALLOWED}. */
    public static boolean isAllowed(String url) {
        return verify(url) == Verdict.ALLOWED;
    }

    /** @return {@code true} when the scheme may never be handed to the WebView. */
    public static boolean isBlockedScheme(String scheme) {
        if (scheme == null) {
            return true;
        }
        String normalised = scheme.toLowerCase(Locale.US);
        if (SCHEME_HTTPS.equals(normalised)) {
            return false;
        }
        if (SCHEME_HTTP.equals(normalised) || DANGEROUS_SCHEMES.contains(normalised)) {
            return true;
        }
        return true;
    }

    /** Full evaluation of a candidate navigation target. */
    public static Verdict verify(String url) {
        if (url == null) {
            return Verdict.EMPTY;
        }
        String candidate = url.trim();
        if (candidate.isEmpty()) {
            return Verdict.EMPTY;
        }
        if (candidate.length() > MAX_URL_LENGTH) {
            return Verdict.MALFORMED;
        }
        if (containsControlCharacter(candidate)) {
            return Verdict.MALFORMED;
        }

        final URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            return Verdict.MALFORMED;
        }

        String scheme = uri.getScheme();
        if (scheme == null || scheme.isEmpty()) {
            return Verdict.MALFORMED;
        }
        if (SCHEME_HTTP.equalsIgnoreCase(scheme)) {
            return Verdict.BLOCKED_CLEARTEXT;
        }
        if (!SCHEME_HTTPS.equalsIgnoreCase(scheme)) {
            return Verdict.BLOCKED_SCHEME;
        }
        if (uri.getRawUserInfo() != null) {
            return Verdict.BLOCKED_CREDENTIALS;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            // Java's URI gives no host for a non-ASCII authority; validate the punycode form
            // instead so that "https://münchen.example/" is treated like "https://xn--...".
            host = asciiHostOf(uri);
        }
        if (host == null || host.isEmpty() || !isSyntacticallyValidHost(host)) {
            return Verdict.BLOCKED_HOST;
        }
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            return Verdict.BLOCKED_HOST;
        }
        return Verdict.ALLOWED;
    }

    /** Scheme of a URL, lower-cased, or {@code null}. */
    public static String schemeOf(String url) {
        if (url == null) {
            return null;
        }
        int colon = url.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String scheme = url.substring(0, colon);
        for (int i = 0; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            boolean legal = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'));
            if (!legal) {
                return null;
            }
        }
        return scheme.toLowerCase(Locale.US);
    }

    /**
     * Host of a URL in ASCII (punycode) form, lower-cased and without a trailing dot, or
     * {@code null} when the URL has no usable host.
     */
    public static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                host = asciiHostOf(uri);
            }
            return normaliseHost(host);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** {@code scheme://host[:port]} of a URL, or {@code null}. */
    public static String originOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            String host = normaliseHost(uri.getHost());
            if (scheme == null || host == null) {
                return null;
            }
            int port = uri.getPort();
            if (port == -1 || (SCHEME_HTTPS.equalsIgnoreCase(scheme) && port == DEFAULT_HTTPS_PORT)) {
                return scheme.toLowerCase(Locale.US) + "://" + host;
            }
            return scheme.toLowerCase(Locale.US) + "://" + host + ":" + port;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * Canonical form used as the de-duplication key for bookmarks and history: lower-cased
     * scheme and host, no default port, no fragment, a non-empty path, and no trailing slash
     * outside the site root.
     *
     * <p>{@code https://example.com/page} and {@code https://example.com/page/} are the same page on
     * every server that redirects one to the other, and a browser that stored them twice would count
     * one visit twice and offer the same bookmark twice. The site root keeps its slash.
     */
    public static String normalizeKey(String url) {
        if (url == null) {
            return "";
        }
        final URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return url.trim().toLowerCase(Locale.US);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        String host = normaliseHost(uri.getHost());
        if (host == null) {
            return url.trim().toLowerCase(Locale.US);
        }
        StringBuilder key = new StringBuilder(scheme).append("://").append(host);
        int port = uri.getPort();
        if (port != -1 && !(SCHEME_HTTPS.equals(scheme) && port == DEFAULT_HTTPS_PORT)) {
            key.append(':').append(port);
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            key.append('/');
        } else {
            key.append(path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
        }
        if (uri.getRawQuery() != null) {
            key.append('?').append(uri.getRawQuery());
        }
        return key.toString();
    }

    private static String normaliseHost(String host) {
        if (host == null) {
            return null;
        }
        String value = host.toLowerCase(Locale.US);
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }

    /**
     * ASCII (punycode) form of the authority of a URI whose host is not pure ASCII.
     *
     * @return the ASCII host, or {@code null} when the authority carries no usable host name
     */
    private static String asciiHostOf(URI uri) {
        String authority = uri.getRawAuthority();
        if (authority == null) {
            return null;
        }
        String candidate = authority;
        int at = candidate.lastIndexOf('@');
        if (at >= 0) {
            candidate = candidate.substring(at + 1);
        }
        if (candidate.startsWith("[")) {
            // IPv6 literals are handled by URI.getHost().
            return null;
        }
        int colon = candidate.lastIndexOf(':');
        if (colon >= 0) {
            String port = candidate.substring(colon + 1);
            if (port.isEmpty()) {
                return null;
            }
            for (int i = 0; i < port.length(); i++) {
                if (!Character.isDigit(port.charAt(i))) {
                    return null;
                }
            }
            candidate = candidate.substring(0, colon);
        }
        if (candidate.isEmpty()) {
            return null;
        }
        try {
            String ascii = IDN.toASCII(candidate);
            return ascii.isEmpty() ? null : ascii;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isSyntacticallyValidHost(String host) {
        if (host.length() > 253) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        if (host.indexOf('%') >= 0 || host.indexOf('/') >= 0 || host.indexOf('\\') >= 0) {
            return false;
        }
        try {
            String ascii = IDN.toASCII(host);
            return !ascii.isEmpty() && !ascii.startsWith(".") && !ascii.endsWith("..");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c == '\n' || c == '\r' || c == '\t') {
                return true;
            }
        }
        return false;
    }
}
