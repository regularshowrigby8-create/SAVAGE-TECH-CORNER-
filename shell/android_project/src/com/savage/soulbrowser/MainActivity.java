package com.savage.soulbrowser;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString("SoulBrowser/1.0 (Linux; Android 14; Mobile)");

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new SoulBridge(), "SoulBridge");
        webView.loadUrl("https://savage-tech-corner.io");
    }

    public class SoulBridge {
        @JavascriptInterface
        public String getAppVersion() {
            return "1.0.0-soul";
        }

        @JavascriptInterface
        public void postFeedback(String msg) {
            Toast.makeText(MainActivity.this, "Feedback: " + msg, Toast.LENGTH_SHORT).show();
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
