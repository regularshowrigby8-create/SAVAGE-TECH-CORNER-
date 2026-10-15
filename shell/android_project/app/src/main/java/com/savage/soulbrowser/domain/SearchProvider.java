package com.savage.soulbrowser.domain;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Search engines the omnibox can use. All of them are contacted over HTTPS only. */
public enum SearchProvider {

    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q="),
    GOOGLE("google", "Google", "https://www.google.com/search?q="),
    BING("bing", "Bing", "https://www.bing.com/search?q=");

    /** Privacy-preserving default of the product. */
    public static final SearchProvider DEFAULT = DUCKDUCKGO;

    private final String id;
    private final String displayName;
    private final String queryPrefix;

    SearchProvider(String id, String displayName, String queryPrefix) {
        this.id = id;
        this.displayName = displayName;
        this.queryPrefix = queryPrefix;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String buildSearchUrl(String query) {
        return queryPrefix + encode(query == null ? "" : query.trim());
    }

    /** Never returns {@code null}: unknown or corrupt stored values fall back to {@link #DEFAULT}. */
    public static SearchProvider fromId(String id, SearchProvider fallback) {
        if (id != null) {
            String normalised = id.trim().toLowerCase(Locale.US);
            for (SearchProvider provider : values()) {
                if (provider.id.equals(normalised)) {
                    return provider;
                }
            }
        }
        return fallback;
    }

    /** The next provider in the ring, used by the menu cycle action. */
    public SearchProvider next() {
        SearchProvider[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is guaranteed by the JVM specification; this cannot happen.
            throw new IllegalStateException("UTF-8 unavailable", e);
        }
    }
}
