package com.savage.soulbrowser;

import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Savage Browser Main Android Activity.
 * Hardened WebView implementation with:
 * - Strict HTTPS domain whitelist and HTTP scheme rejection
 * - Fail-closed SSL error handler (no silent SSL bypassing)
 * - Modern OnBackPressedDispatcher callback handling
 * - Static SecureSoulBridge avoiding Activity leaks with WeakReference
 * - Unicode-preserving feedback validation (\p{L}\p{N})
 * - Domain-isolated storage and cleartext blocking
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "SavageBrowser";
    private WebView webView;

    // Strict allowed domains for browser navigation & bridge interaction
    public static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList(
        "savage-tech-corner.io",
        "app.savage-tech-corner.io"
    ));
    public static final Set<String> ALLOWED_BRIDGE_HOSTS = ALLOWED_HOSTS;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        // Restrict JavaScript and enable security invariants
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString("SavageBrowser/1.0 (Linux; Android 14; Mobile; SecurityHardened)");

        webView.setWebViewClient(new HardenedWebViewClient());
        webView.addJavascriptInterface(new SecureSoulBridge(this, webView), "SoulBridge");
        webView.loadUrl("https://savage-tech-corner.io");

        // Modern back-press handling replacing deprecated Activity.onBackPressed()
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    public static class HardenedWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (request == null || request.getUrl() == null) {
                return true;
            }
            Uri uri = request.getUrl();
            return !isNavigationPermitted(uri);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url == null) {
                return true;
            }
            try {
                Uri uri = Uri.parse(url);
                return !isNavigationPermitted(uri);
            } catch (Exception e) {
                return true;
            }
        }

        public static boolean isNavigationPermitted(Uri uri) {
            if (uri == null) return false;
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String userInfo = uri.getUserInfo();
            int port = uri.getPort();

            // Fallback for JVM unit tests where android.net.Uri may return null for default methods
            if (scheme == null || host == null) {
                try {
                    java.net.URI jUri = new java.net.URI(uri.toString());
                    scheme = jUri.getScheme();
                    host = jUri.getHost();
                    userInfo = jUri.getUserInfo();
                    port = jUri.getPort();
                } catch (Exception e) {
                    return false;
                }
            }

            if (!"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            if (host == null) {
                return false;
            }
            // Prohibit credentials in URLs
            if (userInfo != null) {
                return false;
            }
            // Enforce default HTTPS port or standard 443
            if (port != -1 && port != 443) {
                return false;
            }
            return ALLOWED_HOSTS.contains(host.toLowerCase());
        }

        public static boolean shouldBlockScheme(String scheme) {
            // Fail-closed on cleartext HTTP & custom non-HTTPS protocols: Only secure HTTPS is permitted
            return !"https".equalsIgnoreCase(scheme);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // Fail-closed: Never proceed on SSL error
            Log.e(TAG, "Fatal SSL Error encountered: " + error.toString());
            if (view != null && view.getContext() != null) {
                Toast.makeText(view.getContext(), "SSL Security Error: Connection Terminated", Toast.LENGTH_LONG).show();
            }
            if (handler != null) {
                handler.cancel();
            }
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            Log.i(TAG, "Navigating to: " + url);
        }
    }

    /**
     * Static bridge implementation avoiding Activity memory leaks and enforcing safe Unicode input.
     */
    public static class SecureSoulBridge {
        private static final Pattern SAFE_FEEDBACK_PATTERN = Pattern.compile("^[\\p{L}\\p{N}\\s._!?,@#&()-]{1,500}$", Pattern.UNICODE_CHARACTER_CLASS);

        private final WeakReference<MainActivity> activityRef;
        private final WeakReference<WebView> webViewRef;

        public SecureSoulBridge(MainActivity activity, WebView webView) {
            this.activityRef = new WeakReference<>(activity);
            this.webViewRef = new WeakReference<>(webView);
        }

        @JavascriptInterface
        public String getAppVersion() {
            WebView webView = webViewRef.get();
            String currentUrl = webView != null ? webView.getUrl() : null;
            if (!isPrivilegedOrigin(currentUrl)) {
                Log.w(TAG, "Unauthorized bridge call from origin: " + currentUrl);
                return "ACCESS_DENIED";
            }
            return "1.0.0-savage-hardened";
        }

        @JavascriptInterface
        public void postFeedback(String msg) {
            WebView webView = webViewRef.get();
            String currentUrl = webView != null ? webView.getUrl() : null;
            if (!isPrivilegedOrigin(currentUrl)) {
                Log.w(TAG, "Unauthorized bridge postFeedback call from: " + currentUrl);
                return;
            }
            if (msg == null || msg.trim().isEmpty() || msg.length() > 500) {
                Log.w(TAG, "Invalid or oversized feedback payload rejected");
                return;
            }
            // Whitelist-validate allowing international Unicode scripts without character stripping
            String trimmed = msg.trim();
            if (!SAFE_FEEDBACK_PATTERN.matcher(trimmed).matches()) {
                Log.w(TAG, "Feedback rejected due to illegal characters or pattern violation");
                return;
            }
            MainActivity activity = activityRef.get();
            if (activity != null) {
                Toast.makeText(activity, "Feedback: " + trimmed, Toast.LENGTH_SHORT).show();
            }
        }

        public static boolean isPrivilegedOrigin(String url) {
            if (url == null) return false;
            try {
                // If android.net.Uri is not mocked in host JVM unit test, fall back to java.net.URI
                String scheme = null;
                String host = null;
                try {
                    Uri uri = Uri.parse(url);
                    scheme = uri.getScheme();
                    host = uri.getHost();
                } catch (Exception uriEx) {
                    // Fallback to java.net.URI
                }

                if (scheme == null || host == null) {
                    java.net.URI jUri = new java.net.URI(url);
                    scheme = jUri.getScheme();
                    host = jUri.getHost();
                }

                return "https".equalsIgnoreCase(scheme) && ALLOWED_BRIDGE_HOSTS.contains(host != null ? host.toLowerCase() : "");
            } catch (Exception e) {
                return false;
            }
        }

        public static boolean isInputValid(String input) {
            return input != null && SAFE_FEEDBACK_PATTERN.matcher(input.trim()).matches();
        }
    }
}
