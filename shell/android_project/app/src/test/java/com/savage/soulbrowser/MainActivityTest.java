package com.savage.soulbrowser;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests verifying security invariants of the Savage Browser Android Shell:
 * - Cleartext HTTP rejection and non-HTTPS scheme blocking
 * - Privileged origin validation
 * - Unicode-preserving whitelist validation
 */
public class MainActivityTest {

    @Test
    public void testSchemeWhitelistBlocksHttp() {
        assertTrue("HTTP scheme must be blocked",
            MainActivity.HardenedWebViewClient.shouldBlockScheme("http"));
        assertTrue("FTP scheme must be blocked",
            MainActivity.HardenedWebViewClient.shouldBlockScheme("ftp"));
        assertTrue("File scheme must be blocked",
            MainActivity.HardenedWebViewClient.shouldBlockScheme("file"));
        assertTrue("JavaScript scheme must be blocked",
            MainActivity.HardenedWebViewClient.shouldBlockScheme("javascript"));
    }

    @Test
    public void testSchemeWhitelistAllowsHttps() {
        assertFalse("HTTPS scheme must not be blocked",
            MainActivity.HardenedWebViewClient.shouldBlockScheme("https"));
        assertFalse("HTTPS case-insensitive must not be blocked",
            MainActivity.HardenedWebViewClient.shouldBlockScheme("HTTPS"));
    }

    @Test
    public void testPrivilegedOriginValidation() {
        assertTrue("Official host over HTTPS must be privileged",
            MainActivity.SecureSoulBridge.isPrivilegedOrigin("https://savage-tech-corner.io/dashboard"));
        assertTrue("App subdomain over HTTPS must be privileged",
            MainActivity.SecureSoulBridge.isPrivilegedOrigin("https://app.savage-tech-corner.io/settings"));
        
        assertFalse("Cleartext HTTP on allowed host must be rejected",
            MainActivity.SecureSoulBridge.isPrivilegedOrigin("http://savage-tech-corner.io/dashboard"));
        assertFalse("Untrusted host over HTTPS must be rejected",
            MainActivity.SecureSoulBridge.isPrivilegedOrigin("https://evil-attacker.com"));
        assertFalse("Null URL must be rejected",
            MainActivity.SecureSoulBridge.isPrivilegedOrigin(null));
        assertFalse("Malformed URL must be rejected",
            MainActivity.SecureSoulBridge.isPrivilegedOrigin("not-a-valid-url"));
    }

    @Test
    public void testUnicodeInputValidation() {
        assertTrue("Standard English feedback must be valid",
            MainActivity.SecureSoulBridge.isInputValid("Great browser performance!"));
        assertTrue("Accented Unicode characters must be preserved",
            MainActivity.SecureSoulBridge.isInputValid("Très bon navigateur avec sécurité éprouvée."));
        assertTrue("Chinese/Japanese Unicode scripts must be preserved",
            MainActivity.SecureSoulBridge.isInputValid("素晴らしいブラウザ！非常好！"));
        
        assertFalse("Dangerous script injection payloads must be rejected",
            MainActivity.SecureSoulBridge.isInputValid("<script>alert(1)</script>"));
        assertFalse("SQL injection characters must be rejected",
            MainActivity.SecureSoulBridge.isInputValid("'; DROP TABLE users; --"));
        assertFalse("Null input must be rejected",
            MainActivity.SecureSoulBridge.isInputValid(null));
        assertFalse("Empty input must be rejected",
            MainActivity.SecureSoulBridge.isInputValid("   "));
    }
}
