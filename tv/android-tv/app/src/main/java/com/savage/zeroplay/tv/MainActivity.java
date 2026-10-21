package com.savage.zeroplay.tv;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Locale;

/**
 * Dependency-light Android TV shell for ZeroPlay.
 *
 * The complete catalogue, playback, discovery, downloads and settings experience
 * remains the tested ZeroPlay web app. This native layer only supplies the TV
 * contract: Leanback launch, remote/media keys, fullscreen WebView media,
 * predictable Back behaviour, device profile and an offline screen.
 */
public final class MainActivity extends Activity {
    private static final String APP_HOST = "zeroplay-rd.vercel.app";
    private static final String APP_ALIAS_HOST = "zeroplay-rd-savage-coo.vercel.app";
    private static final String APP_URL = "https://" + APP_HOST + "/?androidtv=1&shell=" + BuildConfig.VERSION_NAME + "#/home";
    private static final String OFFLINE_URL = "file:///android_asset/offline.html";
    private static final int FILE_CHOOSER_REQUEST = 4107;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private WebView webView;
    private View loadingOverlay;
    private TvCursorView tvCursorView;
    private boolean tvCursorMode;
    private float tvCursorX;
    private float tvCursorY;
    private String lastBlockedScamHost = "";
    private long lastBlockedScamAt;
    private long lastBlockedToastAt;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> fileChooserCallback;
    private String profileJson = "{}";
    private boolean offlineShown;
    private boolean backEvaluationPending;
    private boolean firstPageLoaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        applyImmersiveMode();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(Color.BLACK);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setVerticalScrollBarEnabled(false);
        webView.setScrollbarFadingEnabled(true);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        root.addView(webView);

        tvCursorView = new TvCursorView(this);
        tvCursorView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        tvCursorView.setVisibility(View.GONE);
        root.addView(tvCursorView);

        loadingOverlay = buildLoadingOverlay();
        root.addView(loadingOverlay);

        configureWebView();
        profileJson = buildProfileJson();
        webView.addJavascriptInterface(new ZeroPlayTvBridge(this), "ZeroPlayAndroidTV");
        webView.setWebViewClient(new ZeroPlayWebViewClient());
        webView.setWebChromeClient(new ZeroPlayChromeClient());
        webView.setDownloadListener(new ZeroPlayDownloadListener());

        boolean restored = false;
        if (savedInstanceState != null) {
            restored = webView.restoreState(savedInstanceState) != null;
        }
        if (!restored) {
            loadStartPage();
        }
        webView.requestFocus();
    }

    private View buildLoadingOverlay() {
        LinearLayout layout = new LinearLayout(this);
        layout.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.rgb(5, 6, 7));
        layout.setPadding(48, 48, 48, 48);

        TextView mark = new TextView(this);
        mark.setText("▶");
        mark.setTextColor(Color.WHITE);
        mark.setTextSize(34f);
        mark.setGravity(Gravity.CENTER);
        mark.setBackgroundColor(Color.rgb(124, 58, 237));
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(92, 92);
        markParams.bottomMargin = 28;
        mark.setLayoutParams(markParams);
        layout.addView(mark);

        TextView title = new TextView(this);
        title.setText("ZeroPlay TV");
        title.setTextColor(Color.WHITE);
        title.setTextSize(34f);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        ProgressBar progress = new ProgressBar(this);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = 28;
        progress.setLayoutParams(progressParams);
        layout.addView(progress);
        layout.setFocusable(false);
        return layout;
    }

    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);
        settings.setTextZoom(100);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setAllowFileAccess(true); // bundled offline.html only; URL handling below blocks other files.
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setSavePassword(false);
        settings.setSaveFormData(false);
        settings.setGeolocationEnabled(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
        String defaultAgent = settings.getUserAgentString();
        settings.setUserAgentString(defaultAgent + " ZeroPlayTV/" + BuildConfig.VERSION_NAME + " AndroidTV Leanback");
        webView.setInitialScale(100);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
    }

    private String buildProfileJson() {
        JSONObject profile = new JSONObject();
        try {
            profile.put("appVersion", BuildConfig.VERSION_NAME);
            profile.put("packageName", getPackageName());
            profile.put("manufacturer", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
            profile.put("model", Build.MODEL == null ? "" : Build.MODEL);
            profile.put("device", Build.DEVICE == null ? "" : Build.DEVICE);
            profile.put("androidRelease", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
            profile.put("sdkInt", Build.VERSION.SDK_INT);

            JSONArray abis = new JSONArray();
            String primaryAbi = "";
            if (Build.SUPPORTED_ABIS != null) {
                for (String abi : Build.SUPPORTED_ABIS) {
                    if (abi == null || abi.isEmpty()) continue;
                    if (primaryAbi.isEmpty()) primaryAbi = abi;
                    abis.put(abi);
                }
            }
            profile.put("supportedAbis", abis);
            profile.put("primaryAbi", primaryAbi);

            ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(memory);
                profile.put("totalMemoryMb", Math.round(memory.totalMem / 1048576d));
                profile.put("availableMemoryMb", Math.round(memory.availMem / 1048576d));
                profile.put("lowRamDevice", activityManager.isLowRamDevice());
            }

            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            float refreshRate = 0f;
            if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                windowManager.getDefaultDisplay().getRealMetrics(metrics);
                refreshRate = windowManager.getDefaultDisplay().getRefreshRate();
            } else {
                metrics = getResources().getDisplayMetrics();
            }
            profile.put("displayWidth", metrics.widthPixels);
            profile.put("displayHeight", metrics.heightPixels);
            profile.put("refreshRate", Math.round(refreshRate));
            if (webView != null) profile.put("userAgent", webView.getSettings().getUserAgentString());
        } catch (Exception ignored) {
            // The web app still works with query/UA detection if a vendor build rejects a field.
        }
        return profile.toString();
    }

    private boolean hasInternetConnection() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return true;
            Network network = manager.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            return caps == null || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception ignored) {
            return true;
        }
    }

    private void loadStartPage() {
        if (hasInternetConnection()) {
            offlineShown = false;
            webView.loadUrl(APP_URL);
        } else {
            showOfflinePage();
        }
    }

    private void showOfflinePage() {
        if (offlineShown && isOfflinePage(webView.getUrl())) return;
        offlineShown = true;
        hideLoadingOverlay();
        webView.loadUrl(OFFLINE_URL);
    }

    private boolean isOfflinePage(String url) {
        return url != null && url.startsWith(OFFLINE_URL);
    }

    private boolean isAppUrl(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            return APP_HOST.equalsIgnoreCase(uri.getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void showLoadingOverlay() {
        if (loadingOverlay != null && !firstPageLoaded) loadingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideLoadingOverlay() {
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
    }

    private void applyImmersiveMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Exception ignored) {
            // Some TV vendor builds restrict one of the immersive calls.
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    private void evaluate(String script) {
        if (webView == null) return;
        webView.evaluateJavascript(script, null);
    }

    private void setTvCursorMode(boolean enabled) {
        runOnUiThread(() -> {
            tvCursorMode = enabled;
            if (tvCursorView == null) return;
            if (enabled) {
                int width = root != null ? root.getWidth() : 0;
                int height = root != null ? root.getHeight() : 0;
                if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
                if (height <= 0) height = getResources().getDisplayMetrics().heightPixels;
                tvCursorX = width / 2f;
                tvCursorY = height / 2f;
                tvCursorView.setPosition(tvCursorX, tvCursorY);
                tvCursorView.setVisibility(View.VISIBLE);
                tvCursorView.bringToFront();
                if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) {
                    loadingOverlay.bringToFront();
                }
            } else {
                tvCursorView.setVisibility(View.GONE);
            }
        });
    }

    private void moveTvCursor(float dx, float dy) {
        if (tvCursorView == null) return;
        int width = root != null ? root.getWidth() : 0;
        int height = root != null ? root.getHeight() : 0;
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
        if (height <= 0) height = getResources().getDisplayMetrics().heightPixels;
        tvCursorX = Math.max(8f, Math.min(Math.max(8f, width - 8f), tvCursorX + dx));
        tvCursorY = Math.max(8f, Math.min(Math.max(8f, height - 8f), tvCursorY + dy));
        tvCursorView.setPosition(tvCursorX, tvCursorY);
    }

    private void tapTvCursor() {
        if (webView == null) return;
        final float x = tvCursorX;
        final float y = tvCursorY;
        final long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        try { webView.dispatchTouchEvent(down); } finally { down.recycle(); }
        mainHandler.postDelayed(() -> {
            if (webView == null) return;
            MotionEvent up = MotionEvent.obtain(downTime, downTime + 65, MotionEvent.ACTION_UP, x, y, 0);
            try { webView.dispatchTouchEvent(up); } finally { up.recycle(); }
        }, 65);
    }

    private boolean isTvCursorKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE;
    }

    private boolean isTrustedAppHost(String host) {
        if (host == null) return false;
        String clean = host.toLowerCase(Locale.US);
        return APP_HOST.equals(clean) || APP_ALIAS_HOST.equals(clean);
    }

    private boolean isScamUrl(Uri uri) {
        if (uri == null) return false;
        String value = ((uri.getHost() == null ? "" : uri.getHost()) + "/"
                + (uri.getEncodedPath() == null ? "" : uri.getEncodedPath()) + "?"
                + (uri.getEncodedQuery() == null ? "" : uri.getEncodedQuery()))
                .toLowerCase(Locale.US);
        return value.contains("press-continue")
                || value.contains("continue-to-watch")
                || value.contains("scan-qr")
                || value.contains("qr-code-to-confirm")
                || value.contains("confirm-you-are-not-a-robot")
                || value.contains("prove-you-are-not-a-robot")
                || value.contains("not-a-robot")
                || value.contains("not_a_robot")
                || value.contains("o11qnji32yff.com");
    }

    private void notifyBlockedScam(Uri uri) {
        if (uri == null) return;
        String rawHost = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
        String safeHost = rawHost.replaceAll("[^a-z0-9.-]", "");
        mainHandler.post(() -> {
            long now = SystemClock.uptimeMillis();
            if (safeHost.equals(lastBlockedScamHost) && now - lastBlockedScamAt < 4000) return;
            lastBlockedScamHost = safeHost;
            lastBlockedScamAt = now;
            Toast.makeText(MainActivity.this, "Blocked a QR/robot-check pop-up", Toast.LENGTH_SHORT).show();
            evaluate("window.dispatchEvent(new CustomEvent('zeroplay-blocked-scam',{detail:{host:'" + safeHost + "'}}));");
        });
    }

    private void notifyBlockedExternalRedirect() {
        mainHandler.post(() -> {
            long now = SystemClock.uptimeMillis();
            if (now - lastBlockedToastAt < 4000) return;
            lastBlockedToastAt = now;
            Toast.makeText(MainActivity.this, "Blocked an external pop-up/redirect", Toast.LENGTH_SHORT).show();
        });
    }

    private void handleNativeBack() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (backEvaluationPending || webView == null) return;
        backEvaluationPending = true;
        webView.evaluateJavascript(
                "(function(){try{return typeof handleZeroPlayTvBack==='function'&&handleZeroPlayTvBack()===true;}catch(e){return false;}})()",
                value -> mainHandler.post(() -> {
                    backEvaluationPending = false;
                    if ("true".equals(value)) return;
                    finishNativeBack();
                }));
        mainHandler.postDelayed(() -> {
            if (backEvaluationPending) {
                backEvaluationPending = false;
                finishNativeBack();
            }
        }, 900);
    }

    private void finishNativeBack() {
        if (webView != null && !isOfflinePage(webView.getUrl()) && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    private String mediaKeyName(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                return "MediaPlayPause";
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return "MediaPlay";
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return "MediaPause";
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return "MediaFastForward";
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return "MediaRewind";
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return "MediaTrackNext";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return "MediaTrackPrevious";
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return "MediaStop";
            case KeyEvent.KEYCODE_CHANNEL_UP:
                return "ChannelUp";
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                return "ChannelDown";
            default:
                return null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                handleNativeBack();
            }
            return true;
        }

        if (tvCursorMode) {
            if (action == KeyEvent.ACTION_DOWN) {
                float density = getResources().getDisplayMetrics().density;
                float step = (34f + Math.min(132f, event.getRepeatCount() * 11f)) * density;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        moveTvCursor(-step, 0f);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        moveTvCursor(step, 0f);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        moveTvCursor(0f, -step);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        moveTvCursor(0f, step);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    case KeyEvent.KEYCODE_MEDIA_PLAY:
                    case KeyEvent.KEYCODE_MEDIA_PAUSE:
                        if (event.getRepeatCount() == 0) tapTvCursor();
                        return true;
                    default:
                        break;
                }
            }
            if (isTvCursorKey(keyCode)) return true;
        }

        if (action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_GUIDE) {
                evaluate("(function(){try{return typeof handleZeroPlayTvMenu==='function'&&handleZeroPlayTvMenu();}catch(e){return false;}})()");
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_SEARCH) {
                evaluate("(function(){try{return typeof handleZeroPlayTvSearch==='function'&&handleZeroPlayTvSearch();}catch(e){return false;}})()");
                return true;
            }
            String mediaKey = mediaKeyName(keyCode);
            if (mediaKey != null) {
                evaluate("(function(){try{return typeof handleZeroPlayTvMediaKey==='function'&&handleZeroPlayTvMediaKey('" + mediaKey + "');}catch(e){return false;}})()");
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        handleNativeBack();
    }

    private void showCustomView(View view, WebChromeClient.CustomViewCallback callback) {
        if (tvCursorMode) {
            setTvCursorMode(false);
            evaluate("(function(){try{if(typeof setEmbedFocusMode==='function')setEmbedFocusMode(false);}catch(e){}})()");
        }
        if (customView != null) {
            if (customViewCallback != null) customViewCallback.onCustomViewHidden();
            root.removeView(customView);
        }
        customView = view;
        customViewCallback = callback;
        customView.setBackgroundColor(Color.BLACK);
        root.addView(customView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setVisibility(View.INVISIBLE);
        hideLoadingOverlay();
        applyImmersiveMode();
        customView.requestFocus();
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        webView.setVisibility(View.VISIBLE);
        WebChromeClient.CustomViewCallback callback = customViewCallback;
        customViewCallback = null;
        if (callback != null) callback.onCustomViewHidden();
        applyImmersiveMode();
        webView.requestFocus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST) return;
        ValueCallback<Uri[]> callback = fileChooserCallback;
        fileChooserCallback = null;
        if (callback == null) return;
        Uri[] result = null;
        if (resultCode == Activity.RESULT_OK) {
            result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        }
        callback.onReceiveValue(result);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (webView != null && (webView.getUrl() == null || isOfflinePage(webView.getUrl()))) {
            loadStartPage();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        if (webView != null) {
            webView.onResume();
            webView.requestFocus();
        }
    }

    @Override
    protected void onPause() {
        setTvCursorMode(false);
        if (webView != null) {
            evaluate("(function(){try{if(typeof stopCardPreview==='function')stopCardPreview();document.querySelectorAll('.hero-trailer.on').forEach(function(el){if(typeof stopBackdropPreview==='function')stopBackdropPreview(el);});}catch(e){}})()");
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (webView == null) return;
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            evaluate("(function(){try{return typeof handleZeroPlayTvLowMemory==='function'&&handleZeroPlayTvLowMemory();}catch(e){return false;}})()");
        }
        if (level >= TRIM_MEMORY_COMPLETE) {
            webView.clearCache(false);
        }
    }

    @Override
    protected void onDestroy() {
        if (customView != null) hideCustomView();
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        if (webView != null) {
            webView.stopLoading();
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private final class ZeroPlayWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request == null ? null : request.getUrl(), request == null || request.isForMainFrame());
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request == null ? null : request.getUrl();
            if (isScamUrl(uri)) {
                notifyBlockedScam(uri);
                return new WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                        Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url == null ? null : Uri.parse(url));
        }

        private boolean handleUrl(Uri uri) {
            return handleUrl(uri, true);
        }

        private boolean handleUrl(Uri uri, boolean mainFrame) {
            if (uri == null) return true;
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
            if ("http".equals(scheme) || "https".equals(scheme)) {
                if (isScamUrl(uri)) {
                    notifyBlockedScam(uri);
                    return true;
                }
                // Provider pages live in iframes. The top-level document must
                // never be hijacked by an embed ad/pop-under navigation.
                if (mainFrame && !isTrustedAppHost(uri.getHost())) {
                    notifyBlockedExternalRedirect();
                    return true;
                }
                return false;
            }
            if ("about".equals(scheme) || "blob".equals(scheme) || "data".equals(scheme)) {
                return false;
            }
            if ("file".equals(scheme) && OFFLINE_URL.equals(uri.toString())) return false;
            if ("intent".equals(scheme) || "market".equals(scheme) || "tel".equals(scheme) || "mailto".equals(scheme)) {
                Toast.makeText(MainActivity.this, "External apps are not opened from ZeroPlay TV", Toast.LENGTH_SHORT).show();
                return true;
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            if (isAppUrl(url)) offlineShown = false;
            showLoadingOverlay();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            firstPageLoaded = true;
            hideLoadingOverlay();
            if (isAppUrl(url)) offlineShown = false;
            view.evaluateJavascript(
                    "window.__zeroplayAndroidTvShell={version:'" + BuildConfig.VERSION_NAME + "',abi:'armeabi-v7a'};"
                            + "window.dispatchEvent(new Event('zeroplay-android-tv-ready'));",
                    null);
            view.requestFocus();
            if (isOfflinePage(url)) {
                view.evaluateJavascript("setTimeout(function(){var r=document.getElementById('retry');if(r)r.focus();},60);", null);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request != null && request.isForMainFrame() && request.getUrl() != null
                    && isAppUrl(request.getUrl().toString())) {
                showOfflinePage();
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            super.onReceivedError(view, errorCode, description, failingUrl);
            if (isAppUrl(failingUrl)) showOfflinePage();
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            super.onReceivedHttpError(view, request, errorResponse);
            if (request != null && request.isForMainFrame() && errorResponse != null
                    && errorResponse.getStatusCode() >= 500
                    && request.getUrl() != null && isAppUrl(request.getUrl().toString())) {
                showOfflinePage();
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
        }
    }

    private final class ZeroPlayChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            if (newProgress >= 100) {
                mainHandler.postDelayed(MainActivity.this::hideLoadingOverlay, 250);
            } else {
                showLoadingOverlay();
            }
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            showCustomView(view, callback);
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = callback;
            try {
                Intent intent = params != null ? params.createIntent() : new Intent(Intent.ACTION_OPEN_DOCUMENT);
                if (params == null) {
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }
                startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                return true;
            } catch (RuntimeException ignored) {
                fileChooserCallback = null;
                callback.onReceiveValue(null);
                Toast.makeText(MainActivity.this, "No file picker is available on this TV", Toast.LENGTH_SHORT).show();
                return false;
            }
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }
    }

    private final class ZeroPlayDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
            if (url == null || url.startsWith("blob:") || url.startsWith("data:")) {
                Toast.makeText(MainActivity.this, "Downloads stay inside ZeroPlay on TV. Open Downloads to play the finished file.", Toast.LENGTH_LONG).show();
                return;
            }
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                Toast.makeText(MainActivity.this, "That download type is not supported on TV", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
                DownloadManager.Request request = new DownloadManager.Request(uri);
                request.setTitle(filename);
                request.setDescription("ZeroPlay TV download");
                request.setMimeType(mimeType);
                if (userAgent != null && !userAgent.isEmpty()) request.addRequestHeader("User-Agent", userAgent);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null && !cookies.isEmpty()) request.addRequestHeader("Cookie", cookies);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "ZeroPlay/" + filename);
                DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) throw new IllegalStateException("Download service unavailable");
                manager.enqueue(request);
                Toast.makeText(MainActivity.this, "Download started: " + filename, Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(MainActivity.this, "Download could not start on this TV", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Small, read-only bridge. It intentionally exposes no exit or file-system powers. */
    private final class TvCursorView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float cursorX;
        private float cursorY;

        TvCursorView(Context context) {
            super(context);
            setFocusable(false);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(Color.WHITE);
            shadow.setStyle(Paint.Style.STROKE);
            shadow.setColor(Color.argb(190, 0, 0, 0));
            dot.setStyle(Paint.Style.FILL);
            dot.setColor(Color.WHITE);
        }

        void setPosition(float x, float y) {
            cursorX = x;
            cursorY = y;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float x = cursorX > 0 ? cursorX : getWidth() / 2f;
            float y = cursorY > 0 ? cursorY : getHeight() / 2f;
            float radius = 27f * density;
            shadow.setStrokeWidth(9f * density);
            ring.setStrokeWidth(4f * density);
            canvas.drawCircle(x, y, radius, shadow);
            canvas.drawCircle(x, y, radius, ring);
            canvas.drawLine(x - radius - 9f * density, y, x - radius + 7f * density, y, ring);
            canvas.drawLine(x + radius - 7f * density, y, x + radius + 9f * density, y, ring);
            canvas.drawLine(x, y - radius - 9f * density, x, y - radius + 7f * density, ring);
            canvas.drawLine(x, y + radius - 7f * density, x, y + radius + 9f * density, ring);
            canvas.drawCircle(x, y, 4f * density, dot);
        }
    }

    public static final class ZeroPlayTvBridge {
        private final WeakReference<MainActivity> activityRef;

        ZeroPlayTvBridge(MainActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        @JavascriptInterface
        public boolean isAndroidTV() {
            return true;
        }

        @JavascriptInterface
        public String getProfileJson() {
            MainActivity activity = activityRef.get();
            return activity == null ? "{}" : activity.profileJson;
        }

        @JavascriptInterface
        public void setKeepScreenOn(boolean keepOn) {
            MainActivity activity = activityRef.get();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (keepOn) {
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        @JavascriptInterface
        public boolean hasTvCursor() {
            return true;
        }

        @JavascriptInterface
        public void setTvCursorMode(boolean enabled) {
            MainActivity activity = activityRef.get();
            if (activity != null) activity.setTvCursorMode(enabled);
        }

        @JavascriptInterface
        public void showToast(String message) {
            MainActivity activity = activityRef.get();
            if (activity == null || message == null) return;
            String clean = message.trim();
            if (clean.isEmpty()) return;
            if (clean.length() > 120) clean = clean.substring(0, 120);
            String text = clean;
            activity.runOnUiThread(() -> Toast.makeText(activity, text, Toast.LENGTH_SHORT).show());
        }
    }
}
