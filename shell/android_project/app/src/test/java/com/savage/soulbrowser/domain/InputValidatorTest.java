package com.savage.soulbrowser.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** AC-042: bridge payload validation keeps Unicode, rejects injection and control characters. */
public class InputValidatorTest {

    @Test
    public void acceptsUnicode() {
        assertTrue(InputValidator.isValidFeedback("Great browser performance!"));
        assertTrue(InputValidator.isValidFeedback("Très bon navigateur avec sécurité éprouvée."));
        assertTrue(InputValidator.isValidFeedback("素晴らしいブラウザ!非常好!"));
        assertTrue(InputValidator.isValidFeedback("Ελληνικά, кириллица, العربية, עברית"));
    }

    @Test
    public void acceptsPlainPunctuation() {
        assertTrue(InputValidator.isValidFeedback("Works fine - 10 out of 10, would buy again."));
        assertTrue(InputValidator.isValidFeedback("email me at someone@example.com"));
    }

    @Test
    public void rejectsInjectionAndControlCharacters() {
        assertFalse(InputValidator.isValidFeedback("<script>alert(1)</script>"));
        assertFalse(InputValidator.isValidFeedback("'; DROP TABLE users; --"));
        assertFalse(InputValidator.isValidFeedback("value=\"1\" onload=\"x\""));
        assertFalse(InputValidator.isValidFeedback("$(rm -rf /)"));
        assertFalse(InputValidator.isValidFeedback("10/10"));
        assertFalse(InputValidator.isValidFeedback("full width ！ punctuation"));
        assertFalse(InputValidator.isValidFeedback("back\\slash"));
        assertFalse(InputValidator.isValidFeedback("pipe|redirection>file"));
        assertFalse(InputValidator.isValidFeedback("bell\u0007char"));
        assertFalse(InputValidator.isValidFeedback("newline\ninjection"));
        assertFalse(InputValidator.isValidFeedback("null\u0000byte"));
    }

    @Test
    public void rejectsEmptyAndOversizedPayloads() {
        assertFalse(InputValidator.isValidFeedback(null));
        assertFalse(InputValidator.isValidFeedback(""));
        assertFalse(InputValidator.isValidFeedback("    "));
        assertFalse(InputValidator.isValidFeedback(repeat('a', 501)));
        assertTrue(InputValidator.isValidFeedback(repeat('a', 500)));
    }

    @Test
    public void sanitizeCollapsesWhitespaceAndNeverReturnsNull() {
        assertEquals("", InputValidator.sanitize(null));
        assertEquals("a b", InputValidator.sanitize("  a \n\t b  "));
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
