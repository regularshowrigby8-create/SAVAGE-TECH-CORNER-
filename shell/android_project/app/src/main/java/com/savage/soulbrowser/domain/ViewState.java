package com.savage.soulbrowser.domain;

/** What the content area of the browser screen is currently showing. */
public enum ViewState {
    /** First load of a page that has no content yet. */
    LOADING,
    /** A web page is displayed (the WebView is visible and interactive). */
    CONTENT,
    /** A main-frame load failed. */
    ERROR,
    /** No validated network connection; nothing was requested. */
    OFFLINE,
    /** The target was refused by {@link UrlPolicy}. */
    BLOCKED,
    /** TLS validation failed; the connection was cancelled. */
    TLS_ERROR
}
