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
        String description = error == null || error.getDescription() == null
                ? "unknown error"
                : error.getDescription().toString();
        host.onMainFrameLoadFailed(url, description);
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        host.onPageStarted(url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        host.onPageFinished(url, view.getTitle());
    }
}
