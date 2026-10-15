package com.savage.soulbrowser.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Gate for WebView downloads: HTTPS only, and only file names that cannot escape the download dir. */
public final class DownloadPolicy {

    private DownloadPolicy() {
    }

    public static boolean isAllowed(String url) {
        return UrlPolicy.verify(url) == UrlPolicy.Verdict.ALLOWED;
    }

    /** Derives a safe file name for the system download manager. */
    public static String fileNameFor(String url, String contentDisposition, String mimeType) {
        String candidate = null;
        if (contentDisposition != null) {
            candidate = fromContentDisposition(contentDisposition);
        }
        if (candidate == null) {
            candidate = fromPath(url);
        }
        if (candidate == null) {
            candidate = "download";
        }
        return sanitise(candidate, mimeType);
    }

    private static String fromContentDisposition(String header) {
        String lower = header.toLowerCase(Locale.US);
        int index = lower.indexOf("filename=");
        if (index < 0) {
            return null;
        }
        String value = header.substring(index + "filename=".length()).trim();
        if (value.startsWith("\"")) {
            int closing = value.indexOf('"', 1);
            value = closing > 0 ? value.substring(1, closing) : value.substring(1);
        } else {
            int semi = value.indexOf(';');
            if (semi >= 0) {
                value = value.substring(0, semi);
            }
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private static String fromPath(String url) {
        if (url == null) {
            return null;
        }
        try {
            String path = new URI(url.trim()).getPath();
            if (path == null || path.isEmpty() || path.endsWith("/")) {
                return null;
            }
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            return name.isEmpty() ? null : name;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Strips any directory part and characters that are unsafe on common file systems. */
    static String sanitise(String name, String mimeType) {
        String value = name.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replaceAll("[^A-Za-z0-9._-]", "_");
        while (value.startsWith(".")) {
            value = value.substring(1);
        }
        if (value.isEmpty()) {
            value = "download";
        }
        if (value.length() > 100) {
            value = value.substring(0, 100);
        }
        if (mimeType != null && mimeType.startsWith("text/html") && !value.contains(".")) {
            value = value + ".html";
        }
        return value;
    }
}
