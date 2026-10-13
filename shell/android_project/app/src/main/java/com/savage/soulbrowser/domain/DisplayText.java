package com.savage.soulbrowser.domain;

/** Display helpers shared by the browser, bookmarks and history screens. */
public final class DisplayText {

    private DisplayText() {
    }

    /**
     * Title shown for a page/bookmark/history row.
     *
     * <p>Never returns an empty string: an empty or blank title falls back to the host name and
     * then to the raw URL, so a row is never rendered blank.
     */
    public static String titleFor(String title, String url) {
        if (title != null && !title.trim().isEmpty()) {
            return collapse(title);
        }
        String host = UrlPolicy.hostOf(url);
        if (host != null && !host.isEmpty()) {
            return host;
        }
        return url == null ? "" : url.trim();
    }

    /** Domain part of a URL for the "security badge" and secondary row text. */
    public static String hostFor(String url) {
        String host = UrlPolicy.hostOf(url);
        return host == null ? "" : host;
    }

    /** Middle-ellipsised URL that keeps the scheme and the host readable in narrow rows. */
    public static String shorten(String url, int maxLength) {
        if (url == null) {
            return "";
        }
        String value = url.trim();
        if (maxLength <= 4 || value.length() <= maxLength) {
            return value;
        }
        int keep = maxLength - 1;
        int head = keep / 2;
        int tail = keep - head;
        return value.substring(0, head) + "…" + value.substring(value.length() - tail);
    }

    private static String collapse(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }
}
