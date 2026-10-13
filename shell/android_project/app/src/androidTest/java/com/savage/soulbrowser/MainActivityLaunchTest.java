package com.savage.soulbrowser;

import android.view.View;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import com.savage.soulbrowser.ui.MainActivity;

/**
 * AC-001, AC-003 (home), AC-005 (search), AC-019 (progress), AC-022 (cleartext refused on an
 * incoming link) and AC-029 (a refusal is visible). Every assertion is made against the real
 * activity and its real views.
 */
@RunWith(AndroidJUnit4.class)
public class MainActivityLaunchTest {

    @Before
    public void clearLocalState() {
        TestSupport.wipeLocalData();
    }

    @After
    public void restoreRadios() {
        TestSupport.setNetworkEnabled(true);
    }

    @Test
    public void firstLaunchShowsBrowserChrome() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.on(scenario, activity -> {
                Assert.assertNotNull("omnibox", activity.findViewById(R.id.edit_omnibox));
                Assert.assertNotNull("bookmark action", activity.findViewById(R.id.btn_action_bookmark));
                Assert.assertNotNull("reload action", activity.findViewById(R.id.btn_action_reload));
                Assert.assertNotNull("back", activity.findViewById(R.id.btn_nav_back));
                Assert.assertNotNull("forward", activity.findViewById(R.id.btn_nav_forward));
                Assert.assertNotNull("home", activity.findViewById(R.id.btn_nav_home));
                Assert.assertNotNull("tabs", activity.findViewById(R.id.btn_nav_tabs));
                Assert.assertNotNull("menu", activity.findViewById(R.id.btn_nav_menu));
                Assert.assertNotNull("security badge", activity.findViewById(R.id.img_security_badge));

                // A brand new browser session starts with exactly one home tab, and the dock says so.
                Assert.assertEquals(1, TestSupport.openTabCount(activity));

                // Nothing is loading and nothing is refused on a clean launch.
                Assert.assertFalse(activity.isLoading());
                Assert.assertNotEquals(View.VISIBLE,
                        TestSupport.visibilityOf(activity, R.id.state_overlay));
                Assert.assertNotEquals(View.VISIBLE,
                        TestSupport.visibilityOf(activity, R.id.state_retry));
            });
        }
    }

    @Test
    public void homeLoadsHomeUrl() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.waitUntil("the home tab to request the home URL",
                    () -> MainActivity.HOME_URL.equals(
                            TestSupport.onResult(scenario, activity ->
                                    TestSupport.webViewOf(activity).getUrl())));
        }
    }

    @Test
    public void submittingQueryLoadsSearchUrl() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.on(scenario, activity -> {
                EditText omnibox = activity.findViewById(R.id.edit_omnibox);
                omnibox.setText("savage browser test");
                // The omnibox submits through the IME action, which is what the user presses.
                omnibox.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
            });
            TestSupport.waitUntil("the search URL to be requested",
                    () -> {
                        String url = TestSupport.onResult(scenario, activity ->
                                TestSupport.webViewOf(activity).getUrl());
                        return url != null && url.contains("duckduckgo.com")
                                && url.contains("savage");
                    });
        }
    }

    @Test
    public void blockedSchemeIntentShowsErrorState() {
        try (ActivityScenario<MainActivity> scenario =
                     TestSupport.launchBrowserWith("javascript:alert(1)")) {
            TestSupport.on(scenario, activity -> {
                Assert.assertEquals(View.VISIBLE,
                        TestSupport.visibilityOf(activity, R.id.state_overlay));
                Assert.assertEquals(activity.getString(R.string.state_blocked_title),
                        TestSupport.textOf(activity, R.id.state_title));
                Assert.assertEquals(activity.getString(R.string.state_blocked_message),
                        TestSupport.textOf(activity, R.id.state_message));
                // A refusal must not offer a retry: the address can never succeed.
                Assert.assertNotEquals(View.VISIBLE,
                        TestSupport.visibilityOf(activity, R.id.state_retry));
            });
        }
    }

    @Test
    public void httpIntentIsBlocked() {
        try (ActivityScenario<MainActivity> scenario =
                     TestSupport.launchBrowserWith("http://example.com/")) {
            TestSupport.on(scenario, activity -> {
                Assert.assertEquals(View.VISIBLE,
                        TestSupport.visibilityOf(activity, R.id.state_overlay));
                Assert.assertEquals(activity.getString(R.string.state_blocked_title),
                        TestSupport.textOf(activity, R.id.state_title));
            });
        }
    }

    @Test
    public void progressIndicatorExists() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            // The launch itself requests the home page, so wait for that request to settle before
            // deciding what "idle" looks like - otherwise the assertion races the first load.
            TestSupport.waitUntil("the first load to settle so the progress bar is idle", () ->
                            !TestSupport.onResult(scenario, MainActivity::isLoading)
                                    && TestSupport.visibility(scenario, R.id.page_progress_bar)
                                    != View.VISIBLE,
                    () -> TestSupport.describeBrowser(scenario));

            TestSupport.on(scenario, activity -> {
                ProgressBar progress = activity.findViewById(R.id.page_progress_bar);
                Assert.assertNotNull("the progress bar must exist", progress);
                Assert.assertEquals(100, progress.getMax());
                // Idle: the bar is not shown.
                Assert.assertNotEquals(View.VISIBLE,
                        TestSupport.visibilityOf(activity, R.id.page_progress_bar));
            });

            // The chrome client reports progress through this callback; driving it proves the
            // bar reflects loading and disappears again on completion.
            TestSupport.on(scenario, activity -> activity.onProgress(40));
            TestSupport.waitUntil("the progress bar to appear", () -> {
                ProgressBar progress = TestSupport.onResult(scenario,
                        activity -> activity.findViewById(R.id.page_progress_bar));
                return progress.getVisibility() == View.VISIBLE && progress.getProgress() == 40;
            });

            TestSupport.on(scenario, activity -> activity.onProgress(100));
            TestSupport.waitUntil("the progress bar to disappear", () -> {
                ProgressBar progress = TestSupport.onResult(scenario,
                        activity -> activity.findViewById(R.id.page_progress_bar));
                return progress.getVisibility() != View.VISIBLE;
            });
        }
    }

    @Test
    public void refusedAddressKeepsTheBrowserUsable() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.on(scenario, activity -> {
                EditText omnibox = activity.findViewById(R.id.edit_omnibox);
                omnibox.setText("file:///etc/passwd");
                omnibox.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
            });
            TestSupport.on(scenario, activity -> {
                Assert.assertEquals(activity.getString(R.string.state_blocked_title),
                        TestSupport.textOf(activity, R.id.state_title));
                Assert.assertEquals("the session must still have its tab",
                        1, TestSupport.openTabCount(activity));
                WebView webView = TestSupport.webViewOf(activity);
                Assert.assertNotEquals("a blocked address must never reach the WebView",
                        "file:///etc/passwd", webView.getUrl());
            });
        }
    }
}
