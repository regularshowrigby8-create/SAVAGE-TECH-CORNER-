package com.savage.soulbrowser.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Downloads are HTTPS-only and cannot write outside the download directory. */
public class DownloadPolicyTest {

    @Test
    public void httpsOnly() {
        assertTrue(DownloadPolicy.isAllowed("https://example.com/file.pdf"));
        assertFalse(DownloadPolicy.isAllowed("http://example.com/file.pdf"));
        assertFalse(DownloadPolicy.isAllowed("file:///etc/passwd"));
        assertFalse(DownloadPolicy.isAllowed(null));
    }

    @Test
    public void fileNameFromPath() {
        assertEquals("report.pdf", DownloadPolicy.fileNameFor("https://example.com/a/report.pdf", null, "application/pdf"));
        assertEquals("download", DownloadPolicy.fileNameFor("https://example.com/", null, null));
    }

    @Test
    public void fileNameFromContentDisposition() {
        assertEquals("invoice.pdf", DownloadPolicy.fileNameFor("https://example.com/x",
                "attachment; filename=\"invoice.pdf\"", "application/pdf"));
        assertEquals("plain.txt", DownloadPolicy.fileNameFor("https://example.com/x",
                "attachment; filename=plain.txt", "text/plain"));
    }

    @Test
    public void fileNameCannotEscapeTheDownloadDirectory() {
        assertEquals("evil.sh", DownloadPolicy.fileNameFor("https://example.com/x",
                "attachment; filename=\"../../evil.sh\"", null));
        assertEquals("passwd", DownloadPolicy.fileNameFor("https://example.com/x",
                "attachment; filename=\"/etc/passwd\"", null));
        assertEquals("download", DownloadPolicy.fileNameFor("https://example.com/x",
                "attachment; filename=\"..\"", null));
    }

    @Test
    public void unsafeCharactersAreReplaced() {
        assertEquals("a_b_c.txt", DownloadPolicy.sanitise("a:b*c.txt", "text/plain"));
        assertEquals("_shell.php", DownloadPolicy.sanitise(";shell.php", "text/plain"));
    }
}
