package com.savage.soulbrowser.browser;

import android.graphics.Bitmap;
import android.net.http.SslError;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.savage.soulbrowser.domain.UrlPolicy;

/** Hardened WebView client: HTTPS-only navigation, fail-closed TLS, explicit failure reporting. */
public final class SavageWebViewClient extends WebViewClient {

    /** Callbacks into the browser screen that owns the WebView. */
    public interface Host {
        void onNavigationBlocked(String url, UrlPolicy.Verdict verdict);

        void onMainFrameLoadFailed(String url, String description);

        void onTlsError(String url, int primaryError);

        void onPageStarted(String url);

        void onPageFinished(String url, String title);
    }

    private final Host host;

    public SavageWebViewClient(Host host) {
        this.host = host;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request.getUrl() == null ? null : request.getUrl().toString();
        UrlPolicy.Verdict verdict = UrlPolicy.verify(url);
        if (verdict == UrlPolicy.Verdict.ALLOWED) {
            return false;
        }
        host.onNavigationBlocked(url, verdict);
        return true;
    }

    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        String url = error == null ? null : error.getUrl();
        int primaryError = error == null ? -1 : error.getPrimaryError();
        SslDecision.Action action = SslDecision.decide(url, primaryError);
        if (action == SslDecision.Action.CANCEL && handler != null) {
            handler.cancel();
        }
        host.onTlsError(url, primaryError);
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        if (request == null || !request.isForMainFrame()) {
            return;
        }
        String url = request.getUrl() == null ? null : request.getUrl().toString();
        if (!stillCurrent(view, url)) {
            return;
        }
        String description = error == null || error.getDescription() == null
                ? "unknown error"
                : error.getDescription().toString();
        host.onMainFrameLoadFailed(url, description);
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        // Deliberately not guarded: the framework documents that the current page may not have
        // changed yet although a load has begun, so at this instant the WebView's own address can
        // still name the previous page. Dropping this callback would leave a real load invisible to
        // the toolbar; it carries no address into the tab model, so a superseded one only sets the
        // load flag, which the callbacks below then correct.
        host.onPageStarted(url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        if (stillCurrent(view, url)) {
            host.onPageFinished(url, view.getTitle());
        }
    }

    /**
     * {@code true} while {@code url} is still the address this WebView is on, so a page the browser
     * has already left cannot rewrite the tab the user is looking at.
     *
     * <p>Chromium reports the end of a load <em>after</em> the browser has been told to open
     * something else - the home page's load reports its failure right after the user typed another
     * address - and acting on that callback set the active tab's address, title, history entry and
     * failure state back to the old page. Across an Activity recreation that is visible as the page
     * the user was on being replaced by the page before it. The WebView's own current address is the
     * only authority for "is this still the page we are on": it names a load as soon as it starts,
     * which is what makes a superseded callback distinguishable from the current one. A redirect is
     * unaffected, because Chromium reports the finished load with the address it ended up on, which is
     * the address the WebView reports too.
     *
     * <p>Used by the two callbacks that are applied to the page model, where the WebView's address is
     * safe to compare with: by the time a load finishes - or a failure is reported - the WebView has
     * either committed that address (a success, and Chromium's own error page for a failure) or is
     * already reporting it as the load in flight. The two callbacks this deliberately does not guard
     * are {@code onPageStarted} (the framework documents that the current page may not have changed
     * yet although a load has begun, so its address can still name the previous page) and
     * {@code onReceivedSslError} (a refused connection aborts the load, so the WebView's address
     * legitimately stays the previous page - and hiding a refusal is the one thing this browser must
     * never do).
     */
    private static boolean stillCurrent(WebView view, String url) {
        if (view == null || url == null) {
            // Nothing to compare against: the first load has no address yet, and a callback without
            // one cannot be attributed to another page.
            return true;
        }
        String current = view.getUrl();
        return current == null || url.equals(current);
    }
}
