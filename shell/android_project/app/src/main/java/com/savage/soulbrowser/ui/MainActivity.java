package com.savage.soulbrowser.ui;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.savage.soulbrowser.BuildConfig;
import com.savage.soulbrowser.R;
import com.savage.soulbrowser.SavageBrowserApp;
import com.savage.soulbrowser.browser.NetworkStatus;
import com.savage.soulbrowser.browser.SavageWebChromeClient;
import com.savage.soulbrowser.browser.SavageWebViewClient;
import com.savage.soulbrowser.browser.SecureSoulBridge;
import com.savage.soulbrowser.browser.TabManager;
import com.savage.soulbrowser.data.BookmarkRepository;
import com.savage.soulbrowser.data.BrowserPreferences;
import com.savage.soulbrowser.data.HistoryRepository;
import com.savage.soulbrowser.domain.DisplayText;
import com.savage.soulbrowser.domain.DownloadPolicy;
import com.savage.soulbrowser.domain.OmniboxResolution;
import com.savage.soulbrowser.domain.OmniboxResolver;
import com.savage.soulbrowser.domain.PermissionPolicy;
import com.savage.soulbrowser.domain.SearchProvider;
import com.savage.soulbrowser.domain.TabList;
import com.savage.soulbrowser.domain.UrlPolicy;
import com.savage.soulbrowser.domain.ViewState;
import com.savage.soulbrowser.domain.ViewStateMachine;

import java.util.Set;

/**
 * The browser screen: hardened WebView tabs, omnibox, view-state overlay and the navigation dock.
 *
 * <p>All browser rules that can be expressed without Android types live in {@code domain} and are
 * unit-tested on the JVM; this class only wires them to the view hierarchy.
 */
public final class MainActivity extends AppCompatActivity implements
        SavageWebViewClient.Host,
        SavageWebChromeClient.Host,
        TabManager.Listener,
        SecureSoulBridge.ToastHost {

    /** Home page of the product (see {@code docs/ARCHITECTURE.md}). */
    public static final String HOME_URL = "https://savage-tech-corner.io/";

    /** Intent extra used by the bookmarks/history screens to open a URL in the browser. */
    public static final String EXTRA_OPEN_URL = "com.savage.soulbrowser.extra.OPEN_URL";

    private static final String TAG = "SavageBrowser";
    private static final String STATE_OMNIBOX = "savage.state.omnibox";
    private static final String STATE_LAST_TARGET = "savage.state.lastTarget";

    private static final String MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";

    private final ViewStateMachine viewState = new ViewStateMachine();

    private FrameLayout webViewContainer;
    private LinearLayout stateOverlay;
    private ProgressBar stateProgress;
    private ImageView stateIcon;
    private TextView stateTitle;
    private TextView stateMessage;
    private Button stateRetry;
    private ProgressBar pageProgress;
    private EditText omnibox;
    private ImageView securityBadge;
    private ImageButton bookmarkAction;
    private ImageButton reloadAction;
    private ImageButton navBack;
    private ImageButton navForward;
    private TextView navTabs;

    private TabManager tabManager;
    private NetworkStatus networkStatus;
    private BrowserPreferences preferences;
    private HistoryRepository historyRepository;
    private BookmarkRepository bookmarkRepository;

    @Nullable
    private String lastTarget;
    @Nullable
    private String lastWebPermissionDenial;
    private boolean pageLoaded;
    private boolean loading;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SavageBrowserApp app = SavageBrowserApp.from(this);
        preferences = app.preferences();
        historyRepository = app.historyRepository();
        bookmarkRepository = app.bookmarkRepository();
        networkStatus = new NetworkStatus(this);

        bindViews();
        installListeners();

        tabManager = new TabManager(this, webViewContainer, HOME_URL, this::createConfiguredWebView, this);
        restoreOrStart(savedInstanceState);
    }

    private void bindViews() {
        webViewContainer = findViewById(R.id.webview_container);
        stateOverlay = findViewById(R.id.state_overlay);
        stateProgress = findViewById(R.id.state_progress);
        stateIcon = findViewById(R.id.state_icon);
        stateTitle = findViewById(R.id.state_title);
        stateMessage = findViewById(R.id.state_message);
        stateRetry = findViewById(R.id.state_retry);
        pageProgress = findViewById(R.id.page_progress_bar);
        omnibox = findViewById(R.id.edit_omnibox);
        securityBadge = findViewById(R.id.img_security_badge);
        bookmarkAction = findViewById(R.id.btn_action_bookmark);
        reloadAction = findViewById(R.id.btn_action_reload);
        navBack = findViewById(R.id.btn_nav_back);
        navForward = findViewById(R.id.btn_nav_forward);
        navTabs = findViewById(R.id.btn_nav_tabs);
    }

    private void installListeners() {
        omnibox.setOnEditorActionListener((view, actionId, event) -> {
            boolean isSubmit = actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN);
            if (isSubmit) {
                hideKeyboard();
                submitOmnibox();
                return true;
            }
            return false;
        });

        bookmarkAction.setOnClickListener(view -> toggleBookmark());
        reloadAction.setOnClickListener(view -> onReloadOrStop());
        navBack.setOnClickListener(view -> goBackInHistory());
        navForward.setOnClickListener(view -> goForwardInHistory());
        findViewById(R.id.btn_nav_home).setOnClickListener(view -> navigateTo(HOME_URL, false));
        navTabs.setOnClickListener(view -> new TabSwitcherDialog(this, tabManager::tabList, this::onTabSelected,
                this::onTabClosed, this::onNewTabRequested).show());
        findViewById(R.id.btn_nav_menu).setOnClickListener(view -> showMenu());
        stateRetry.setOnClickListener(view -> onRetryRequested());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView webView = tabManager.activeWebView();
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    private void restoreOrStart(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            tabManager.restoreState(savedInstanceState);
            String omniboxText = savedInstanceState.getString(STATE_OMNIBOX);
            if (omniboxText != null) {
                omnibox.setText(omniboxText);
            }
            lastTarget = savedInstanceState.getString(STATE_LAST_TARGET);
        }
        // The tab model already has its home tab; make sure it has a WebView instead of opening a
        // second, empty tab (which is what a first launch used to show: "1" in the dock next to a
        // blank page, and two entries in the tab switcher).
        tabManager.ensureActiveWebView();
        if (savedInstanceState == null) {
            String requested = requestedUrlFromIntent(getIntent());
            navigateTo(requested == null ? HOME_URL : requested, false);
        } else {
            TabList.Tab active = tabManager.activeTab();
            if (active != null) {
                onActiveTabChanged(active.getUrl(), active.getTitle());
            }
        }
        // The dock is rendered from the model on every start, so the count the user sees can never
        // be a stale or empty label from the layout.
        onTabSetChanged(tabManager.size());
        renderState();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String requested = requestedUrlFromIntent(intent);
        if (requested != null) {
            navigateTo(requested, false);
        }
    }

    @Nullable
    private String requestedUrlFromIntent(@Nullable Intent intent) {
        if (intent == null) {
            return null;
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return intent.getData().toString();
        }
        return intent.getStringExtra(EXTRA_OPEN_URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        tabManager.saveState(outState);
        outState.putString(STATE_OMNIBOX, omnibox.getText().toString());
        outState.putString(STATE_LAST_TARGET, lastTarget);
    }

    @Override
    protected void onDestroy() {
        if (tabManager != null) {
            tabManager.destroy();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- WebView wiring

    /**
     * Hardens a fresh WebView.
     *
     * <p>{@code setAllowFileAccessFromFileURLs}, {@code setAllowUniversalAccessFromFileURLs} and
     * {@code setDatabaseEnabled} are deprecated because API 30+ ignores them, but the app runs on
     * API 26-29 as well, where they still take effect, so they stay as defence in depth.
     */
    @SuppressWarnings("deprecation")
    private WebView createConfiguredWebView() {
        WebView webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setGeolocationEnabled(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setUserAgentString(userAgent());

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new SavageWebViewClient(this));
        webView.setWebChromeClient(new SavageWebChromeClient(this));
        webView.addJavascriptInterface(
                new SecureSoulBridge(webView, this, BuildConfig.VERSION_NAME),
                SecureSoulBridge.JS_INTERFACE_NAME);
        webView.setDownloadListener(this::onDownloadRequested);
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.canvas));
        return webView;
    }

    private String userAgent() {
        String suffix = " SavageBrowser/" + BuildConfig.VERSION_NAME;
        return (preferences.isDesktopSite() ? DESKTOP_USER_AGENT : MOBILE_USER_AGENT) + suffix;
    }

    // ---------------------------------------------------------------- navigation

    private void submitOmnibox() {
        OmniboxResolution resolution = OmniboxResolver.resolve(omnibox.getText().toString(), preferences.searchProvider());
        applyResolution(resolution);
    }

    private void applyResolution(OmniboxResolution resolution) {
        switch (resolution.getKind()) {
            case NAVIGATE:
            case SEARCH:
                navigateTo(resolution.getUrl(), false);
                break;
            case BLOCKED:
                lastTarget = null;
                viewState.onBlocked();
                renderState();
                String host = UrlPolicy.hostOf(resolution.getRejectedInput());
                Log.w(TAG, "Navigation refused (" + resolution.getVerdict() + ") host="
                        + (host == null ? "none" : host));
                break;
            case EMPTY:
            default:
                hideKeyboard();
                break;
        }
    }

    private void navigateTo(String url, boolean newTab) {
        if (!UrlPolicy.isAllowed(url)) {
            lastTarget = null;
            viewState.onBlocked();
            renderState();
            return;
        }
        if (!networkStatus.isOnline()) {
            lastTarget = url;
            viewState.onOffline();
            renderState();
            return;
        }
        lastTarget = url;
        viewState.onNavigationStarted();
        WebView webView = newTab ? tabManager.openTab(url) : tabManager.activeWebView();
        if (webView == null) {
            webView = tabManager.openTab(url);
        } else if (!newTab) {
            webView.loadUrl(url);
        }
        preferences.setLastUrl(url);
        // The address bar must name the address the browser was told to open. Waiting for the page
        // to report its own URL is not enough: a page that never loads, or a browser screen opened
        // straight from a bookmark, link or the launcher, showed an empty address bar - and
        // onActiveTabChanged/onTabUpdated deliberately do not overwrite a focused omnibox (the user
        // may be typing), so nothing else filled it in. An explicit navigation target is
        // authoritative; the page's self-reported URL is not.
        tabManager.updateActiveTab(url, null);
        omnibox.setText(url);
        renderState();
        updateNavigationControls();
    }

    private void onReloadOrStop() {
        WebView webView = tabManager.activeWebView();
        if (webView == null) {
            return;
        }
        if (loading) {
            webView.stopLoading();
            loading = false;
            updateNavigationControls();
            renderState();
        } else {
            loading = true;
            webView.reload();
            updateNavigationControls();
        }
    }

    private void onRetryRequested() {
        if (!viewState.requestRetry()) {
            return;
        }
        String target = lastTarget != null ? lastTarget : HOME_URL;
        navigateTo(target, false);
    }

    private void goBackInHistory() {
        WebView webView = tabManager.activeWebView();
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        }
    }

    private void goForwardInHistory() {
        WebView webView = tabManager.activeWebView();
        if (webView != null && webView.canGoForward()) {
            webView.goForward();
        }
    }

    // ---------------------------------------------------------------- bookmarks

    private void toggleBookmark() {
        WebView webView = tabManager.activeWebView();
        String url = webView == null ? null : webView.getUrl();
        if (!UrlPolicy.isAllowed(url)) {
            showToast(getString(R.string.state_blocked_title));
            return;
        }
        if (bookmarkRepository.contains(url)) {
            boolean removed = bookmarkRepository.remove(url);
            if (removed) {
                showToast(getString(R.string.bookmarks_removed));
            }
        } else {
            String title = DisplayText.titleFor(webView.getTitle(), url);
            BookmarkRepository.AddResult result =
                    bookmarkRepository.add(url, title, System.currentTimeMillis());
            if (result == BookmarkRepository.AddResult.ALREADY_PRESENT) {
                showToast(getString(R.string.bookmarks_already_saved));
            } else if (result == BookmarkRepository.AddResult.ADDED) {
                showToast(getString(R.string.bookmarks_added));
            }
        }
        updateBookmarkAction();
    }

    private void updateBookmarkAction() {
        WebView webView = tabManager.activeWebView();
        String url = webView == null ? null : webView.getUrl();
        boolean saved = UrlPolicy.isAllowed(url) && bookmarkRepository.contains(url);
        bookmarkAction.setImageResource(saved ? R.drawable.ic_star_filled : R.drawable.ic_star_outline);
        bookmarkAction.setContentDescription(getString(
                saved ? R.string.action_bookmark_remove : R.string.action_bookmark_add));
    }

    // ---------------------------------------------------------------- menu

    private void showMenu() {
        SearchProvider current = preferences.searchProvider();
        SearchProvider next = current.next();
        String[] items = new String[]{
                getString(R.string.menu_bookmarks),
                getString(R.string.menu_history),
                getString(R.string.menu_search_provider, next.displayName()),
                getString(R.string.menu_desktop_site),
                getString(R.string.menu_clear_history),
                getString(R.string.menu_about)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_menu)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            startActivity(new Intent(this, BookmarksActivity.class));
                            break;
                        case 1:
                            startActivity(new Intent(this, HistoryActivity.class));
                            break;
                        case 2:
                            preferences.setSearchProvider(next);
                            showToast(getString(R.string.menu_search_provider, next.displayName()));
                            break;
                        case 3:
                            boolean enableDesktop = !preferences.isDesktopSite();
                            preferences.setDesktopSite(enableDesktop);
                            tabManager.applyToAllWebViews(view -> view.getSettings().setUserAgentString(userAgent()));
                            showToast(getString(enableDesktop
                                    ? R.string.menu_desktop_site_on
                                    : R.string.menu_desktop_site_off));
                            break;
                        case 4:
                            confirmClearHistory();
                            break;
                        case 5:
                        default:
                            showAbout();
                            break;
                    }
                })
                .show();
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.history_clear_confirm_title)
                .setMessage(R.string.history_clear_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.history_clear_all, (dialog, which) -> {
                    historyRepository.clear();
                    showToast(getString(R.string.history_cleared));
                })
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_about)
                .setMessage(getString(R.string.menu_about_message,
                        BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ---------------------------------------------------------------- tabs

    private void onTabSelected(String tabId) {
        tabManager.activate(tabId);
    }

    private void onTabClosed(String tabId) {
        tabManager.closeTab(tabId);
    }

    private void onNewTabRequested() {
        navigateTo(HOME_URL, true);
    }

    @Override
    public void onTabSetChanged(int tabCount) {
        navTabs.setText(String.valueOf(tabCount));
        navTabs.setContentDescription(getString(R.string.tabs_label, tabCount));
        updateNavigationControls();
    }

    @Override
    public void onActiveTabChanged(String url, String title) {
        if (url != null && !omnibox.hasFocus()) {
            omnibox.setText(url);
        }
        updateSecurityBadge(url);
        updateBookmarkAction();
        updateNavigationControls();
    }

    @Override
    public void onTabUpdated(String id, String url, String title) {
        TabList.Tab active = tabManager.activeTab();
        if (active != null && active.getId().equals(id) && url != null && !omnibox.hasFocus()) {
            omnibox.setText(url);
        }
    }

    // ---------------------------------------------------------------- view state

    private void renderState() {
        ViewState state = viewState.state();
        boolean overlay = viewState.showsOverlay();
        stateOverlay.setVisibility(overlay ? View.VISIBLE : View.GONE);
        pageProgress.setVisibility(viewState.showsProgressBar() ? View.VISIBLE : View.GONE);
        pageProgress.setProgress(viewState.progress());
        if (!overlay) {
            return;
        }
        switch (state) {
            case LOADING:
                stateProgress.setVisibility(View.VISIBLE);
                stateIcon.setVisibility(View.GONE);
                stateTitle.setText(R.string.state_loading);
                stateMessage.setVisibility(View.GONE);
                break;
            case OFFLINE:
                showStateIcon(R.drawable.ic_offline);
                stateTitle.setText(R.string.state_offline_title);
                stateMessage.setText(R.string.state_offline_message);
                stateMessage.setVisibility(View.VISIBLE);
                break;
            case ERROR:
                showStateIcon(R.drawable.ic_warning);
                stateTitle.setText(R.string.state_error_title);
                stateMessage.setText(R.string.state_error_message);
                stateMessage.setVisibility(View.VISIBLE);
                break;
            case BLOCKED:
                showStateIcon(R.drawable.ic_warning);
                stateTitle.setText(R.string.state_blocked_title);
                stateMessage.setText(R.string.state_blocked_message);
                stateMessage.setVisibility(View.VISIBLE);
                break;
            case TLS_ERROR:
                showStateIcon(R.drawable.ic_warning);
                stateTitle.setText(R.string.state_ssl_title);
                stateMessage.setText(R.string.state_ssl_message);
                stateMessage.setVisibility(View.VISIBLE);
                break;
            case CONTENT:
            default:
                break;
        }
        stateRetry.setVisibility(viewState.showsRetry() ? View.VISIBLE : View.GONE);
    }

    private void showStateIcon(int drawableRes) {
        stateProgress.setVisibility(View.GONE);
        stateIcon.setImageResource(drawableRes);
        stateIcon.setVisibility(View.VISIBLE);
    }

    private void updateSecurityBadge(@Nullable String url) {
        boolean secure = UrlPolicy.isAllowed(url);
        securityBadge.setImageResource(secure ? R.drawable.ic_lock : R.drawable.ic_warning);
        securityBadge.setContentDescription(getString(
                secure ? R.string.security_secure : R.string.security_insecure));
    }

    private void updateNavigationControls() {
        WebView webView = tabManager.activeWebView();
        navBack.setEnabled(webView != null && webView.canGoBack());
        navForward.setEnabled(webView != null && webView.canGoForward());
        reloadAction.setImageResource(loading ? R.drawable.ic_close : R.drawable.ic_refresh);
        reloadAction.setContentDescription(getString(loading ? R.string.action_stop : R.string.action_reload));
    }

    // ---------------------------------------------------------------- client callbacks

    @Override
    public void onNavigationBlocked(String url, UrlPolicy.Verdict verdict) {
        lastTarget = null;
        viewState.onBlocked();
        renderState();
        Log.w(TAG, "Blocked in-page navigation (" + verdict + ")"); // host is deliberately not logged
    }

    @Override
    public void onMainFrameLoadFailed(String url, String description) {
        lastTarget = url;
        loading = false;
        if (!networkStatus.isOnline()) {
            viewState.onOffline();
        } else {
            viewState.onLoadError();
        }
        updateNavigationControls();
        renderState();
        Log.w(TAG, "Main frame load failed: " + description);
    }

    @Override
    public void onTlsError(String url, int primaryError) {
        lastTarget = url;
        loading = false;
        viewState.onTlsError();
        updateNavigationControls();
        renderState();
        Log.w(TAG, "TLS refused: " + com.savage.soulbrowser.browser.SslDecision.describe(primaryError));
    }

    @Override
    public void onPageStarted(String url) {
        loading = true;
        viewState.onNavigationStarted();
        updateSecurityBadge(url);
        // The reload action is also the stop action, so it has to advertise Stop as soon as a load
        // is in flight - which is exactly when the WebView says the load started.
        updateNavigationControls();
        renderState();
    }

    @Override
    public void onPageFinished(String url, @Nullable String title) {
        viewState.onContentReady();
        pageLoaded = true;
        loading = false;
        if (UrlPolicy.isAllowed(url)) {
            String displayTitle = DisplayText.titleFor(title, url);
            historyRepository.recordVisit(url, displayTitle, System.currentTimeMillis());
            preferences.setLastUrl(url);
            tabManager.updateActiveTab(url, displayTitle);
        }
        updateSecurityBadge(url);
        updateBookmarkAction();
        updateNavigationControls();
        renderState();
    }

    @Override
    public void onProgress(int progress) {
        viewState.onProgress(progress);
        renderState();
    }

    @Override
    public void onTitleReceived(String title) {
        TabList.Tab active = tabManager.activeTab();
        if (active != null) {
            tabManager.updateActiveTab(active.getUrl(), DisplayText.titleFor(title, active.getUrl()));
        }
    }

    @Override
    public void onWebPermissionDenied(String origin, Set<String> resources) {
        String host = String.valueOf(UrlPolicy.hostOf(origin));
        String reason = PermissionPolicy.denialReason(origin, resources);
        lastWebPermissionDenial = host + " (" + resources + "): " + reason;
        Log.w(TAG, "Web permission denied for host=" + host
                + " resources=" + resources + " reason=" + reason);
        showToast(getString(R.string.permission_denied));
    }

    /**
     * Description of the most recent WebView permission denial ("host (resources): reason"), or
     * {@code null} when nothing has been denied yet. The browser never grants a web permission, and
     * this record is what the instrumented tests read to prove that a denial happened and why.
     */
    @Nullable
    public String lastWebPermissionDenial() {
        return lastWebPermissionDenial;
    }

    @Override
    public WebView onCreateTabWebView() {
        return tabManager.openTab(null);
    }

    // ---------------------------------------------------------------- downloads

    private void onDownloadRequested(String url, String userAgent, String contentDisposition,
                                     String mimeType, long contentLength) {
        if (!DownloadPolicy.isAllowed(url)) {
            showToast(getString(R.string.download_requires_https));
            return;
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            if (mimeType != null) {
                request.setMimeType(mimeType);
            }
            if (userAgent != null) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    DownloadPolicy.fileNameFor(url, contentDisposition, mimeType));
            DownloadManager downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloadManager == null) {
                showToast(getString(R.string.download_failed));
                return;
            }
            downloadManager.enqueue(request);
            showToast(getString(R.string.download_started));
        } catch (RuntimeException e) {
            Log.w(TAG, "Download could not be enqueued: " + e.getClass().getSimpleName());
            showToast(getString(R.string.download_failed));
        }
    }

    // ---------------------------------------------------------------- helpers

    @Override
    public void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void hideKeyboard() {
        InputMethodManager manager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null && omnibox != null) {
            manager.hideSoftInputFromWindow(omnibox.getWindowToken(), 0);
        }
    }

    /** Exposed for instrumentation tests: whether a page has finished loading in this session. */
    public boolean hasLoadedPage() {
        return pageLoaded;
    }

    /** Exposed for instrumentation tests: whether a load is currently in flight. */
    public boolean isLoading() {
        return loading;
    }

    /** Exposed for instrumentation tests: the current view state. */
    public ViewState currentViewState() {
        return viewState.state();
    }
}
