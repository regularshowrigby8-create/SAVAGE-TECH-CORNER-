package com.savage.soulbrowser;

import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Savage Browser Main Android Activity (v2.0.4 Modern UI).
 * Hardened native Android browser implementation with:
 * - Ultra-robust, universal layout inflation (zero OEM-dependent drawable crashes)
 * - Safe null-check view binding preventing startup NullPointerExceptions
 * - Strict HTTPS domain whitelist and HTTP scheme rejection
 * - Fail-closed SSL error handler (no silent SSL bypassing)
 * - Dynamic electric progress bar tracking web page loading
 * - Modern OnBackPressedDispatcher callback handling
 * - Static SecureSoulBridge avoiding Activity leaks with WeakReference
 * - Unicode-preserving feedback validation (\p{L}\p{N})
 * - Domain-isolated storage and cleartext blocking
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "SavageBrowser";
    private WebView webView;
    private ProgressBar progressBar;
    private EditText editOmnibox;
    private View btnActionRefresh;
    private View btnNavBack;
    private View btnNavForward;
    private View btnNavHome;
    private View btnNavTabs;
    private View btnNavMenu;
    private View btnActionAi;

    // Strict allowed domains for browser navigation & bridge interaction
    public static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList(
        "savage-tech-corner.io",
        "app.savage-tech-corner.io"
    ));
    public static final Set<String> ALLOWED_BRIDGE_HOSTS = ALLOWED_HOSTS;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            setContentView(R.layout.activity_main);
            webView = findViewById(R.id.main_webview);
            progressBar = findViewById(R.id.page_progress_bar);
            editOmnibox = findViewById(R.id.edit_omnibox);
            btnActionRefresh = findViewById(R.id.btn_action_refresh);
            btnNavBack = findViewById(R.id.btn_nav_back);
            btnNavForward = findViewById(R.id.btn_nav_forward);
            btnNavHome = findViewById(R.id.btn_nav_home);
            btnNavTabs = findViewById(R.id.btn_nav_tabs);
            btnNavMenu = findViewById(R.id.btn_nav_menu);
            btnActionAi = findViewById(R.id.btn_action_ai);
        } catch (Throwable t) {
            Log.e(TAG, "Layout inflation failed: " + t.getMessage(), t);
        }

        // Fallback programmatic webview container if XML layout fails
        if (webView == null) {
            Log.w(TAG, "Falling back to safe programmatic WebView container");
            webView = new WebView(this);
            setContentView(webView);
        }

        try {
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setAllowFileAccessFromFileURLs(false);
            settings.setAllowUniversalAccessFromFileURLs(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setUserAgentString("SavageBrowser/2.0.4 (Linux; Android 14; Mobile; SecurityHardened)");
        } catch (Throwable t) {
            Log.e(TAG, "WebSettings configuration error: " + t.getMessage(), t);
        }

        webView.setWebViewClient(new HardenedWebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar != null) {
                    if (newProgress < 100) {
                        progressBar.setVisibility(View.VISIBLE);
                        progressBar.setProgress(newProgress);
                    } else {
                        progressBar.setVisibility(View.GONE);
                    }
                }
            }
        });

        webView.addJavascriptInterface(new SecureSoulBridge(this, webView), "SoulBridge");
        webView.loadUrl("https://savage-tech-corner.io");

        // Omnibox submit handling
        if (editOmnibox != null) {
            editOmnibox.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    String input = editOmnibox.getText().toString().trim();
                    if (!input.isEmpty()) {
                        navigateToUrl(input);
                    }
                    return true;
                }
                return false;
            });
        }

        // Action & Navigation listeners
        if (btnActionRefresh != null) {
            btnActionRefresh.setOnClickListener(v -> {
                if (webView != null) webView.reload();
            });
        }
        if (btnNavBack != null) {
            btnNavBack.setOnClickListener(v -> {
                if (webView != null && webView.canGoBack()) webView.goBack();
            });
        }
        if (btnNavForward != null) {
            btnNavForward.setOnClickListener(v -> {
                if (webView != null && webView.canGoForward()) webView.goForward();
            });
        }
        if (btnNavHome != null) {
            btnNavHome.setOnClickListener(v -> {
                if (webView != null) webView.loadUrl("https://savage-tech-corner.io");
            });
        }
        if (btnNavTabs != null) {
            btnNavTabs.setOnClickListener(v -> {
                Toast.makeText(MainActivity.this, "Tabs: 1 active tab", Toast.LENGTH_SHORT).show();
            });
        }
        if (btnNavMenu != null) {
            btnNavMenu.setOnClickListener(v -> {
                Toast.makeText(MainActivity.this, "Savage Browser v2.0.4 (Hardened Modern UI)", Toast.LENGTH_SHORT).show();
            });
        }
        if (btnActionAi != null) {
            btnActionAi.setOnClickListener(v -> {
                Toast.makeText(MainActivity.this, "Savage AI Assistant Ready", Toast.LENGTH_SHORT).show();
            });
        }

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

    private void navigateToUrl(String query) {
        String targetUrl = query;
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            targetUrl = "https://" + targetUrl;
        }
        try {
            Uri uri = Uri.parse(targetUrl);
            if (HardenedWebViewClient.isNavigationPermitted(uri)) {
                webView.loadUrl(targetUrl);
            } else {
                Toast.makeText(this, "Security Policy: Host restricted to Savage Tech Corner network", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Invalid URL format", Toast.LENGTH_SHORT).show();
        }
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
            return !"https".equalsIgnoreCase(scheme);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
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
            return "2.0.4-savage-hardened";
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
                String scheme = null;
                String host = null;
                try {
                    Uri uri = Uri.parse(url);
                    scheme = uri.getScheme();
                    host = uri.getHost();
                } catch (Exception uriEx) {
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
