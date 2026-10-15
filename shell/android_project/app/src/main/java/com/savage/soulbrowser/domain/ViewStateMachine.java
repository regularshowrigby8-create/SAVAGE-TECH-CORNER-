package com.savage.soulbrowser.domain;

/**
 * Deterministic state machine for the browser content area.
 *
 * <p>Kept free of Android types so the loading/error/offline/blocked behaviour, including the
 * retry-coalescing rule, is unit-tested rather than "verified" by inspection.
 */
public final class ViewStateMachine {

    private ViewState state;
    private int progress;
    private boolean hasContent;
    private boolean retryPending;
    private boolean loading;

    public ViewStateMachine() {
        this.state = ViewState.LOADING;
        this.progress = 0;
        this.hasContent = false;
        this.retryPending = false;
        // The browser starts by loading its home page, so the first thing the user sees is a load in
        // flight.
        this.loading = true;
    }

    /**
     * A navigation (re)started.
     *
     * <p>Existing content stays on screen (the state stays {@link ViewState#CONTENT}, so the overlay
     * never covers a page the user is reading) but the load is marked as in flight, because that is
     * exactly what the thin progress bar is for.
     */
    public void onNavigationStarted() {
        retryPending = false;
        progress = 0;
        loading = true;
        state = hasContent ? ViewState.CONTENT : ViewState.LOADING;
    }

    /** The WebView reported load progress in {@code [0, 100]}. */
    public void onProgress(int reportedProgress) {
        progress = Math.max(0, Math.min(100, reportedProgress));
        if (isRefusalShown()) {
            // A failure/refusal message stays on screen until the user retries or navigates;
            // WebView still reports 100% after a failed load, which must not hide the message.
            return;
        }
        if (progress >= 100) {
            hasContent = true;
            state = ViewState.CONTENT;
            retryPending = false;
            loading = false;
            return;
        }
        loading = true;
        state = hasContent ? ViewState.CONTENT : ViewState.LOADING;
    }

    /** The page finished rendering; content is now available and no load is in flight. */
    public void onContentReady() {
        hasContent = true;
        progress = 100;
        state = ViewState.CONTENT;
        retryPending = false;
        loading = false;
    }

    public void onLoadError() {
        state = ViewState.ERROR;
        // Nothing is in flight any more: the bar must not keep animating over a message about the
        // failure that ended the load.
        loading = false;
    }

    public void onOffline() {
        state = ViewState.OFFLINE;
        // Nothing is in flight any more: the bar must not keep animating over a message about the
        // failure that ended the load.
        loading = false;
    }

    public void onBlocked() {
        state = ViewState.BLOCKED;
        // Nothing is in flight any more: the bar must not keep animating over a message about the
        // failure that ended the load.
        loading = false;
    }

    public void onTlsError() {
        state = ViewState.TLS_ERROR;
        // Nothing is in flight any more: the bar must not keep animating over a message about the
        // failure that ended the load.
        loading = false;
    }

    /** @return {@code true} while a failure or refusal message is displayed */
    public boolean isRefusalShown() {
        return state == ViewState.ERROR
                || state == ViewState.OFFLINE
                || state == ViewState.BLOCKED
                || state == ViewState.TLS_ERROR;
    }

    public ViewState state() {
        return state;
    }

    public int progress() {
        return progress;
    }

    public boolean hasContent() {
        return hasContent;
    }

    /** The overlay replaces the WebView for every state except a loaded/loading page. */
    public boolean showsOverlay() {
        return state != ViewState.CONTENT && !(state == ViewState.LOADING && hasContent);
    }

    /**
     * Retry is offered for every recoverable failure, and never while loading.
     *
     * <p>{@link ViewState#BLOCKED} is deliberately excluded: a blocked address was refused by a
     * static rule (unsupported scheme, cleartext, origin not in the allow-list), so repeating the
     * same request cannot succeed. Offering Retry there would promise the user something the
     * browser cannot deliver; the message says what happened instead.
     */
    public boolean showsRetry() {
        return state == ViewState.ERROR
                || state == ViewState.OFFLINE
                || state == ViewState.TLS_ERROR;
    }

    /**
     * The thin progress bar is shown while a load is in flight and hidden once it settles.
     *
     * <p>It is deliberately not tied to {@link ViewState#LOADING}: a navigation that starts while a
     * page is already displayed keeps that page (no overlay), and the bar is then the only sign the
     * browser is working. Keying it off the loading flag also means the bar is never shown over a
     * refusal or failure message.
     */
    public boolean showsProgressBar() {
        return loading && progress < 100;
    }

    /**
     * Coalesces repeated Retry taps: only the first tap while a state is unresolved starts work.
     *
     * @return {@code true} when the caller should perform the retry
     */
    public boolean requestRetry() {
        if (!showsRetry() || retryPending) {
            return false;
        }
        retryPending = true;
        return true;
    }

    public boolean isRetryPending() {
        return retryPending;
    }
}
