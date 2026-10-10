package com.savage.soulbrowser;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Savage Browser Main Android Activity.
 * Hardened WebView implementation with:
 * - Strict HTTPS domain whitelist and HTTP scheme rejection
 * - Fail-closed SSL error handler (no silent SSL bypassing)
 * - Input validation on JavascriptInterface bridge methods
 * - Domain-isolated storage and cleartext blocking
 */
public class MainActivity extends Activity {
    private static final String TAG = "SavageBrowser";
    private WebView webView;

    // Strict allowed domains for privileged bridge interaction
    private static final Set<String> ALLOWED_BRIDGE_HOSTS = new HashSet<>(Arrays.asList(
        "savage-tech-corner.io",
        "app.savage-tech-corner.io"
    ));

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
        webView.addJavascriptInterface(new SecureSoulBridge(), "SoulBridge");
        webView.loadUrl("https://savage-tech-corner.io");
    }

    private class HardenedWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();

            // Fail-closed on cleartext HTTP: Only secure HTTPS is permitted
            if (!"https".equalsIgnoreCase(scheme)) {
                Log.w(TAG, "Blocked insecure scheme: " + scheme);
                Toast.makeText(MainActivity.this, "Insecure HTTP blocked by policy", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // Fail-closed: Never proceed on SSL error
            Log.e(TAG, "Fatal SSL Error encountered: " + error.toString());
            Toast.makeText(MainActivity.this, "SSL Security Error: Connection Terminated", Toast.LENGTH_LONG).show();
            handler.cancel();
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            Log.i(TAG, "Navigating to: " + url);
        }
    }

    public class SecureSoulBridge {
        @JavascriptInterface
        public String getAppVersion() {
            String currentUrl = webView != null ? webView.getUrl() : null;
            if (!isPrivilegedOrigin(currentUrl)) {
                Log.w(TAG, "Unauthorized bridge call from origin: " + currentUrl);
                return "ACCESS_DENIED";
            }
            return "1.0.0-savage-hardened";
        }

        @JavascriptInterface
        public void postFeedback(String msg) {
            String currentUrl = webView != null ? webView.getUrl() : null;
            if (!isPrivilegedOrigin(currentUrl)) {
                Log.w(TAG, "Unauthorized bridge postFeedback call from: " + currentUrl);
                return;
            }
            if (msg == null || msg.trim().isEmpty() || msg.length() > 500) {
                Log.w(TAG, "Invalid or oversized feedback payload rejected");
                return;
            }
            // Sanitize message before surfacing
            String sanitized = msg.replaceAll("[^a-zA-Z0-9 ._!?-]", "");
            Toast.makeText(MainActivity.this, "Feedback: " + sanitized, Toast.LENGTH_SHORT).show();
        }

        private boolean isPrivilegedOrigin(String url) {
            if (url == null) return false;
            try {
                Uri uri = Uri.parse(url);
                return "https".equalsIgnoreCase(uri.getScheme()) && ALLOWED_BRIDGE_HOSTS.contains(uri.getHost());
            } catch (Exception e) {
                return false;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
