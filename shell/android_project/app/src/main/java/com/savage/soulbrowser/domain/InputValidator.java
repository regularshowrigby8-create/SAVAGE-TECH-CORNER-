package com.savage.soulbrowser.domain;

/**
 * Validation for text that crosses the JavaScript-bridge boundary.
 *
 * <p>Implemented as an explicit allow-list scan over Unicode code points: every accepted
 * character must be a Unicode letter, a Unicode digit, a space, or one of
 * {@code . _ ! ? , @ # & ( ) ' - :}. Everything else — including every HTML/SQL/script
 * metacharacter and every control character — is rejected.
 */
public final class InputValidator {

    public static final int MAX_FEEDBACK_LENGTH = 500;

    private static final String ALLOWED_PUNCTUATION = "._!?,@#&()'-:";

    private InputValidator() {
    }

    public static boolean isValidFeedback(String message) {
        if (message == null) {
            return false;
        }
        String trimmed = message.trim();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > MAX_FEEDBACK_LENGTH) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); ) {
            int codePoint = trimmed.codePointAt(i);
            if (!isAllowedCodePoint(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    /** Collapses runs of whitespace and trims; never returns {@code null}. */
    public static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        return message.trim().replaceAll("\\s+", " ");
    }

    private static boolean isAllowedCodePoint(int codePoint) {
        if (codePoint == ' ') {
            return true;
        }
        if (Character.isLetterOrDigit(codePoint)) {
            return true;
        }
        return codePoint < 128 && ALLOWED_PUNCTUATION.indexOf(codePoint) >= 0;
    }
}
