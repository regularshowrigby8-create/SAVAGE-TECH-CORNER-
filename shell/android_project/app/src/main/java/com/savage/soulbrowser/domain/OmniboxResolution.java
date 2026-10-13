package com.savage.soulbrowser.domain;

/** Outcome of resolving omnibox (or intent) input into something the browser can act on. */
public final class OmniboxResolution {

    public enum Kind {
        /** Load {@link #getUrl()} in the current tab. */
        NAVIGATE,
        /** Run a search on the configured provider. */
        SEARCH,
        /** Refuse and show the blocked-navigation state. */
        BLOCKED,
        /** Nothing to do. */
        EMPTY
    }

    private final Kind kind;
    private final String url;
    private final String query;
    private final UrlPolicy.Verdict verdict;
    private final String rejectedInput;

    private OmniboxResolution(Kind kind, String url, String query, UrlPolicy.Verdict verdict,
            String rejectedInput) {
        this.kind = kind;
        this.url = url;
        this.query = query;
        this.verdict = verdict;
        this.rejectedInput = rejectedInput;
    }

    public static OmniboxResolution navigate(String url) {
        return new OmniboxResolution(Kind.NAVIGATE, url, null, UrlPolicy.Verdict.ALLOWED, null);
    }

    public static OmniboxResolution search(String url, String query) {
        return new OmniboxResolution(Kind.SEARCH, url, query, UrlPolicy.Verdict.ALLOWED, null);
    }

    /**
     * A refused navigation. The refused text is kept in {@link #getRejectedInput()} only: a
     * blocked resolution never exposes a URL, so no caller can load it by accident.
     */
    public static OmniboxResolution blocked(String input, UrlPolicy.Verdict verdict) {
        return new OmniboxResolution(Kind.BLOCKED, null, null, verdict, input);
    }

    public static OmniboxResolution empty() {
        return new OmniboxResolution(Kind.EMPTY, null, null, UrlPolicy.Verdict.EMPTY, null);
    }

    public Kind getKind() {
        return kind;
    }

    /** Target URL for {@link Kind#NAVIGATE} and {@link Kind#SEARCH}; {@code null} otherwise. */
    public String getUrl() {
        return url;
    }

    /** Text the user typed that was refused, for {@link Kind#BLOCKED}; {@code null} otherwise. */
    public String getRejectedInput() {
        return rejectedInput;
    }

    /** The search term for {@link Kind#SEARCH}. */
    public String getQuery() {
        return query;
    }

    /** Why the input was refused, for {@link Kind#BLOCKED}. */
    public UrlPolicy.Verdict getVerdict() {
        return verdict;
    }

    public boolean isLoadable() {
        return (kind == Kind.NAVIGATE || kind == Kind.SEARCH) && url != null;
    }
}
