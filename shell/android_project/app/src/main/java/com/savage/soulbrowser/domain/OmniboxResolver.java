package com.savage.soulbrowser.domain;

import java.util.Locale;

/**
 * Turns arbitrary user input into a navigation decision.
 *
 * <p>Rules (deterministic, unit-tested):
 * <ul>
 *   <li>blank input → {@link OmniboxResolution.Kind#EMPTY}</li>
 *   <li>an explicit {@code https://} URL → navigate</li>
 *   <li>an explicit {@code http://} or dangerous scheme → blocked</li>
 *   <li>a dotted host, host:port or host/path → navigate over HTTPS</li>
 *   <li>anything else (including sentences with spaces) → search</li>
 * </ul>
 */
public final class OmniboxResolver {

    static final int MAX_INPUT_LENGTH = 2048;

    private OmniboxResolver() {
    }

    public static OmniboxResolution resolve(String rawInput, SearchProvider provider) {
        SearchProvider searchProvider = provider == null ? SearchProvider.DEFAULT : provider;
        if (rawInput == null) {
            return OmniboxResolution.empty();
        }
        String input = rawInput.trim();
        if (input.isEmpty()) {
            return OmniboxResolution.empty();
        }
        if (input.length() > MAX_INPUT_LENGTH) {
            return OmniboxResolution.blocked(truncate(input), UrlPolicy.Verdict.MALFORMED);
        }

        if (hasExplicitAuthority(input)) {
            UrlPolicy.Verdict verdict = UrlPolicy.verify(input);
            return verdict == UrlPolicy.Verdict.ALLOWED
                    ? OmniboxResolution.navigate(input)
                    : OmniboxResolution.blocked(input, verdict);
        }

        String candidateScheme = candidateScheme(input);
        if (candidateScheme != null) {
            UrlPolicy.Verdict verdict = UrlPolicy.verify(input);
            if (verdict == UrlPolicy.Verdict.ALLOWED) {
                return OmniboxResolution.navigate(input);
            }
            return OmniboxResolution.blocked(input, verdict);
        }

        if (looksLikeHost(input)) {
            String upgraded = "https://" + input;
            UrlPolicy.Verdict verdict = UrlPolicy.verify(upgraded);
            if (verdict == UrlPolicy.Verdict.ALLOWED) {
                return OmniboxResolution.navigate(upgraded);
            }
            return OmniboxResolution.blocked(input, verdict);
        }

        return OmniboxResolution.search(searchProvider.buildSearchUrl(input), input);
    }

    /** {@code example.com/x} becomes {@code https://example.com/x} — never {@code http://}. */
    private static boolean hasExplicitAuthority(String input) {
        return input.contains("://");
    }

    /**
     * @return the scheme of an input such as {@code javascript:alert(1)} (scheme without
     *         {@code //}), or {@code null} when the input has no scheme at all.
     */
    private static String candidateScheme(String input) {
        int colon = input.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        int slash = input.indexOf('/');
        int question = input.indexOf('?');
        int limit = input.length();
        if (slash >= 0) {
            limit = Math.min(limit, slash);
        }
        if (question >= 0) {
            limit = Math.min(limit, question);
        }
        if (colon > limit) {
            return null;
        }
        String scheme = input.substring(0, colon);
        if (scheme.indexOf('.') >= 0) {
            // A dotted token in front of ":" is a host and port ("example.com:8443/path"),
            // not a scheme; no browser scheme contains a dot.
            return null;
        }
        if (colon + 1 < input.length() && Character.isDigit(input.charAt(colon + 1))) {
            // "localhost:8080/x" is host and port, not a scheme with a numeric body.
            return null;
        }
        for (int i = 0; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            boolean legal = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-'));
            if (!legal) {
                return null;
            }
        }
        return scheme.toLowerCase(Locale.US);
    }

    private static boolean looksLikeHost(String input) {
        if (input.isEmpty() || input.length() > UrlPolicy.MAX_URL_LENGTH) {
            return false;
        }
        for (int i = 0; i < input.length(); i++) {
            if (Character.isWhitespace(input.charAt(i))) {
                return false;
            }
        }
        String hostPart = input;
        int slash = input.indexOf('/');
        if (slash >= 0) {
            hostPart = input.substring(0, slash);
        }
        int question = hostPart.indexOf('?');
        if (question >= 0) {
            hostPart = hostPart.substring(0, question);
        }
        int hash = hostPart.indexOf('#');
        if (hash >= 0) {
            hostPart = hostPart.substring(0, hash);
        }
        int colon = hostPart.lastIndexOf(':');
        if (colon > 0) {
            String port = hostPart.substring(colon + 1);
            if (!port.isEmpty() && isDigits(port)) {
                hostPart = hostPart.substring(0, colon);
            }
        }
        if (hostPart.isEmpty()) {
            return false;
        }
        if (hostPart.equalsIgnoreCase("localhost")) {
            return true;
        }
        if (hostPart.startsWith("[") || hostPart.endsWith("]")) {
            return hostPart.length() > 2;
        }
        if (hostPart.indexOf('.') <= 0) {
            return false;
        }
        String lastLabel = hostPart.substring(hostPart.lastIndexOf('.') + 1);
        return lastLabel.length() >= 2 && !lastLabel.contains("..");
    }

    private static boolean isDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String truncate(String input) {
        return input.substring(0, Math.min(input.length(), 64)) + "…";
    }
}
