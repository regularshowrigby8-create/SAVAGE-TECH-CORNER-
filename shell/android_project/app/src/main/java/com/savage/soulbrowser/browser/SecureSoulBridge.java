package com.savage.soulbrowser.browser;

import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.savage.soulbrowser.domain.InputValidator;
import com.savage.soulbrowser.domain.UrlPolicy;
import com.savage.soulbrowser.domain.TrustedOrigins;

import java.lang.ref.WeakReference;



/**
 * Privileged JavaScript bridge, exposed to the WebView as {@code window.SoulBridge}.
 *
 * <p>Two independent gates protect every call:
 * <ol>
 *   <li>the current page must be an exact privileged origin ({@link TrustedOrigins});</li>
 *   <li>every payload is validated by {@link InputValidator} before it is used.</li>
 * </ol>
 * References to the Activity and WebView are weak, so the bridge cannot leak them.
 */
public final class SecureSoulBridge {

    public static final String JS_INTERFACE_NAME = "SoulBridge";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    private static final String TAG = "SavageBrowser";

    private WeakReference<WebView> webViewRef;
    private final WeakReference<ToastHost> toastHostRef;
    private final PageUrlProvider pageUrlProvider;
    private final String appVersion;

    /** Minimal surface needed from the hosting Activity. */
    public interface ToastHost {
        void showToast(String message);
    }

    /** Supplies the URL of the page that is currently calling the bridge. */
    public interface PageUrlProvider {
        String currentUrl();
    }

    public SecureSoulBridge(WebView webView, ToastHost toastHost, String appVersion) {
        this(webView == null ? null : new WebViewUrlProvider(webView), toastHost, appVersion);
        this.webViewRef = new WeakReference<>(webView);
    }

    /** Reads the page URL from the live WebView without keeping it strongly reachable. */
    private static final class WebViewUrlProvider implements PageUrlProvider {
        private WeakReference<WebView> webViewRef;

        WebViewUrlProvider(WebView webView) {
            this.webViewRef = new WeakReference<>(webView);
        }

        @Override
        public String currentUrl() {
            WebView webView = webViewRef.get();
            return webView == null ? null : webView.getUrl();
        }
    }

    /**
     * Injection point used by the instrumented suite: the origin gate is then exercised against a
     * deterministic page URL instead of whatever the WebView happens to have loaded. Production
     * code always goes through the WebView constructor above.
     */
    public SecureSoulBridge(PageUrlProvider pageUrlProvider, ToastHost toastHost, String appVersion) {
        this.webViewRef = new WeakReference<>(null);
        this.toastHostRef = new WeakReference<>(toastHost);
        this.pageUrlProvider = pageUrlProvider;
        this.appVersion = appVersion;
    }

    @JavascriptInterface
    public String getAppVersion() {
        if (!isCurrentPageTrusted()) {
            logDenial("getAppVersion");
            return ACCESS_DENIED;
        }
        return appVersion;
    }

    @JavascriptInterface
    public boolean postFeedback(String message) {
        if (!isCurrentPageTrusted()) {
            logDenial("postFeedback");
            return false;
        }
        if (!InputValidator.isValidFeedback(message)) {
            Log.w(TAG, "Bridge payload rejected by input validation");
            return false;
        }
        ToastHost host = toastHostRef.get();
        if (host != null) {
            host.showToast(InputValidator.sanitize(message));
        }
        return true;
    }

    private boolean isCurrentPageTrusted() {
        String url = pageUrlProvider == null ? null : pageUrlProvider.currentUrl();
        return TrustedOrigins.isPrivileged(url);
    }

    private void logDenial(String method) {
        WebView webView = webViewRef.get();
        String url = webView == null ? null : webView.getUrl();
        String host = url == null ? "none" : String.valueOf(UrlPolicy.hostOf(url));
        Log.w(TAG, "Bridge call denied: " + method + " from host=" + host);
    }

}
