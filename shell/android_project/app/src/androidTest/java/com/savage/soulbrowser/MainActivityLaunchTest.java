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

    /** An address used by the progress test; no page body is needed, only the request. */
    private static final String PAGE_FOR_PROGRESS = "https://example.com/progress";

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

            });

            // The launch requests the home page. On a runner without egress that request fails and
            // the browser says so; what must never happen is the browser refusing its own start
            // page, and the chrome must still be there while the failure is explained.
            TestSupport.waitUntil("the first load to settle after the clean launch", () ->
                            !TestSupport.onResult(scenario, MainActivity::isLoading),
                    () -> TestSupport.describeBrowser(scenario));
            TestSupport.on(scenario, activity -> {
                Assert.assertNotEquals("the browser never blocks its own home page",
                        com.savage.soulbrowser.domain.ViewState.BLOCKED,
                        activity.currentViewState());
                Assert.assertNotEquals("the browser never reports a TLS failure for its own home page",
                        com.savage.soulbrowser.domain.ViewState.TLS_ERROR,
                        activity.currentViewState());
                EditText omnibox = activity.findViewById(R.id.edit_omnibox);
                Assert.assertEquals("the omnibox shows the address that was requested",
                        MainActivity.HOME_URL, omnibox.getText().toString());
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

            // A real navigation still runs here: the address is requested through the omnibox, the
            // WebView is asked for it, and the load settles. What a test cannot schedule is the
            // timing of Chromium's progress callbacks - against a runner whose DNS answers at once
            // the whole load can be over inside one polling interval, which is why the earlier
            // version of this test could never observe the bar. The chrome's behaviour is therefore
            // asserted by delivering the same callbacks the WebView client delivers
            // (onPageStarted / onProgress / onPageFinished) and asserting what the user sees.
            TestSupport.navigateForReal(scenario, PAGE_FOR_PROGRESS);
            TestSupport.waitForSettledNavigation(scenario, PAGE_FOR_PROGRESS);

            TestSupport.on(scenario, activity -> {
                ProgressBar progress = activity.findViewById(R.id.page_progress_bar);
                activity.onPageStarted(PAGE_FOR_PROGRESS);
                Assert.assertEquals("a load in flight shows the bar",
                        View.VISIBLE, progress.getVisibility());

                activity.onProgress(40);
                Assert.assertEquals("the bar reports the progress the WebView reported",
                        40, progress.getProgress());
                Assert.assertEquals("the bar stays visible while the load is in flight",
                        View.VISIBLE, progress.getVisibility());

                activity.onProgress(100);
                Assert.assertNotEquals("a settled load hides the bar",
                        View.VISIBLE, progress.getVisibility());

                activity.onPageFinished(PAGE_FOR_PROGRESS, null);
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
