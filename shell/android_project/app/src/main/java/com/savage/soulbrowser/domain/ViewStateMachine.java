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

    public ViewStateMachine() {
        this.state = ViewState.LOADING;
        this.progress = 0;
        this.hasContent = false;
        this.retryPending = false;
    }

    /** A navigation (re)started. */
    public void onNavigationStarted() {
        retryPending = false;
        progress = 0;
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
            return;
        }
        state = hasContent ? ViewState.CONTENT : ViewState.LOADING;
    }

    /** The page finished rendering; content is now available. */
    public void onContentReady() {
        hasContent = true;
        progress = 100;
        state = ViewState.CONTENT;
        retryPending = false;
    }

    public void onLoadError() {
        state = ViewState.ERROR;
    }

    public void onOffline() {
        state = ViewState.OFFLINE;
    }

    public void onBlocked() {
        state = ViewState.BLOCKED;
    }

    public void onTlsError() {
        state = ViewState.TLS_ERROR;
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

    /** The thin progress bar is shown while a page is loading and hidden once it is ready. */
    public boolean showsProgressBar() {
        return state == ViewState.LOADING && progress < 100;
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
