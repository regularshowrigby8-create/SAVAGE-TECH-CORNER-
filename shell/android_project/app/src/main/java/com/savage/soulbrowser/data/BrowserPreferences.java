package com.savage.soulbrowser.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.savage.soulbrowser.domain.SearchProvider;
import com.savage.soulbrowser.domain.UrlPolicy;

/** Persisted user settings: search provider, desktop-site mode and the last visited URL. */
public final class BrowserPreferences {

    static final String PREFERENCES_NAME = "savage_browser_prefs";
    static final String KEY_SEARCH_PROVIDER = "search_provider";
    static final String KEY_DESKTOP_SITE = "desktop_site";
    static final String KEY_LAST_URL = "last_url";

    private final SharedPreferences preferences;

    public BrowserPreferences(Context context) {
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public SearchProvider searchProvider() {
        return SearchProvider.fromId(
                preferences.getString(KEY_SEARCH_PROVIDER, SearchProvider.DEFAULT.id()),
                SearchProvider.DEFAULT);
    }

    public void setSearchProvider(SearchProvider provider) {
        preferences.edit()
                .putString(KEY_SEARCH_PROVIDER,
                        (provider == null ? SearchProvider.DEFAULT : provider).id())
                .apply();
    }

    public boolean isDesktopSite() {
        return preferences.getBoolean(KEY_DESKTOP_SITE, false);
    }

    public void setDesktopSite(boolean enabled) {
        preferences.edit().putBoolean(KEY_DESKTOP_SITE, enabled).apply();
    }

    /** Last successfully requested URL, or {@code null}. Never a non-HTTPS URL. */
    public String lastUrl() {
        String value = preferences.getString(KEY_LAST_URL, null);
        if (value == null || !UrlPolicy.isAllowed(value)) {
            return null;
        }
        return value;
    }

    public void setLastUrl(String url) {
        preferences.edit().putString(KEY_LAST_URL, UrlPolicy.isAllowed(url) ? url : null).apply();
    }
}
