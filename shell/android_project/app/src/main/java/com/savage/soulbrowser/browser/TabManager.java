package com.savage.soulbrowser.browser;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;

import com.savage.soulbrowser.domain.TabList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the {@link WebView} of every open tab and keeps them in sync with the pure {@link TabList}
 * model. One WebView per tab gives each tab its own scroll position, HTTP history and form state;
 * only the active WebView is visible, which keeps memory bounded.
 */
public final class TabManager {

    /** Creates and configures a WebView for a new tab. */
    public interface WebViewFactory {
        WebView create();
    }

    /** Notified whenever the tab set or the active tab changes. */
    public interface Listener {
        void onTabSetChanged(int tabCount);

        void onActiveTabChanged(String url, String title);

        void onTabUpdated(String id, String url, String title);
    }

    private static final String KEY_URLS = "savage.tabs.urls";
    private static final String KEY_TITLES = "savage.tabs.titles";
    private static final String KEY_ACTIVE = "savage.tabs.active";
    private static final String KEY_WEBVIEW_PREFIX = "savage.tabs.webview.";

    private final Activity activity;
    private final FrameLayout container;
    private final String homeUrl;
    private final WebViewFactory factory;
    private final Listener listener;
    private final Map<String, WebView> webViews = new LinkedHashMap<>();
    private TabList tabList;

    public TabManager(Activity activity, FrameLayout container, String homeUrl,
                      WebViewFactory factory, Listener listener) {
        this.activity = activity;
        this.container = container;
        this.homeUrl = homeUrl;
        this.factory = factory;
        this.listener = listener;
        this.tabList = TabList.withHomeTab(homeUrl);
    }

    public TabList tabList() {
        return tabList;
    }

    public int size() {
        return tabList.size();
    }

    public TabList.Tab activeTab() {
        return tabList.active();
    }

    public WebView activeWebView() {
        TabList.Tab active = tabList.active();
        return active == null ? null : webViews.get(active.getId());
    }

    /**
     * Creates a tab, attaches its WebView and activates it.
     *
     * @return the new WebView, or {@code null} when the tab limit is reached
     */
    public WebView openTab(String url) {
        String id = tabList.openTab(url);
        if (id == null) {
            return null;
        }
        WebView webView = attach(id);
        listener.onTabSetChanged(tabList.size());
        notifyActiveTab();
        return webView;
    }

    public void activate(String id) {
        if (!tabList.contains(id)) {
            return;
        }
        tabList.activate(id);
        showOnlyActive();
        notifyActiveTab();
    }

    /**
     * Closes a tab and destroys its WebView. Closing the last tab makes the model create a fresh
     * home tab, which is materialised here as well.
     *
     * @return {@code true} when the active tab changed
     */
    public boolean closeTab(String id) {
        boolean activeChanged = tabList.close(id);
        WebView removed = webViews.remove(id);
        if (removed != null) {
            container.removeView(removed);
            removed.stopLoading();
            removed.destroy();
        }
        TabList.Tab active = tabList.active();
        if (active != null && !webViews.containsKey(active.getId())) {
            attach(active.getId());
        }
        showOnlyActive();
        listener.onTabSetChanged(tabList.size());
        if (activeChanged) {
            notifyActiveTab();
        }
        return activeChanged;
    }

    public void updateActiveTab(String url, String title) {
        TabList.Tab active = tabList.active();
        if (active == null) {
            return;
        }
        tabList.update(active.getId(), url, title);
        listener.onTabUpdated(active.getId(), url, title);
    }

    /** Saves the tab set and each WebView's private state so a process restart can resume. */
    public void saveState(Bundle outState) {
        List<TabList.Tab> tabs = tabList.tabs();
        String[] urls = new String[tabs.size()];
        String[] titles = new String[tabs.size()];
        for (int i = 0; i < tabs.size(); i++) {
            TabList.Tab tab = tabs.get(i);
            urls[i] = tab.getUrl();
            titles[i] = tab.getTitle();
            WebView webView = webViews.get(tab.getId());
            Bundle webState = new Bundle();
            if (webView != null) {
                webView.saveState(webState);
            }
            outState.putBundle(KEY_WEBVIEW_PREFIX + i, webState);
        }
        outState.putStringArray(KEY_URLS, urls);
        outState.putStringArray(KEY_TITLES, titles);
        outState.putInt(KEY_ACTIVE, tabList.activeIndex());
    }

    /**
     * Restores tabs from {@link #saveState(Bundle)}. A missing or empty bundle leaves the home tab
     * created by the constructor untouched, so restore can never produce an empty browser.
     */
    public void restoreState(Bundle savedState) {
        if (savedState == null) {
            return;
        }
        String[] urls = savedState.getStringArray(KEY_URLS);
        if (urls == null || urls.length == 0) {
            return;
        }
        String[] titles = savedState.getStringArray(KEY_TITLES);
        List<TabList.Tab> restored = new ArrayList<>();
        for (int i = 0; i < urls.length; i++) {
            String url = urls[i] == null || urls[i].isEmpty() ? homeUrl : urls[i];
            restored.add(new TabList.Tab("restored-" + i, url,
                    titles != null && i < titles.length ? titles[i] : null));
        }
        tabList = TabList.restore(homeUrl, restored, savedState.getInt(KEY_ACTIVE, 0));

        List<TabList.Tab> tabs = tabList.tabs();
        for (int i = 0; i < tabs.size(); i++) {
            TabList.Tab tab = tabs.get(i);
            WebView webView = attach(tab.getId());
            Bundle webState = savedState.getBundle(KEY_WEBVIEW_PREFIX + i);
            if (webState != null && !webState.isEmpty() && webView != null) {
                webView.restoreState(webState);
            } else if (webView != null) {
                webView.loadUrl(tab.getUrl());
            }
        }
        showOnlyActive();
        notifyActiveTab();
        listener.onTabSetChanged(tabList.size());
    }

    /** Applies a settings change (for example the user-agent) to every open tab. */
    public void applyToAllWebViews(java.util.function.Consumer<WebView> action) {
        if (action == null) {
            return;
        }
        for (WebView webView : webViews.values()) {
            action.accept(webView);
        }
    }

    /** Destroys every WebView; call from {@code Activity#onDestroy}. */
    public void destroy() {
        for (WebView webView : webViews.values()) {
            container.removeView(webView);
            webView.stopLoading();
            webView.destroy();
        }
        webViews.clear();
    }

    public Activity activity() {
        return activity;
    }

    private WebView attach(String id) {
        WebView webView = factory.create();
        if (webView == null) {
            return null;
        }
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webViews.put(id, webView);
        container.addView(webView);
        showOnlyActive();
        return webView;
    }

    private void showOnlyActive() {
        TabList.Tab active = tabList.active();
        String activeId = active == null ? null : active.getId();
        for (Map.Entry<String, WebView> entry : webViews.entrySet()) {
            boolean visible = entry.getKey().equals(activeId);
            entry.getValue().setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void notifyActiveTab() {
        TabList.Tab active = tabList.active();
        if (active != null) {
            listener.onActiveTabChanged(active.getUrl(), active.getTitle());
        }
    }
}
