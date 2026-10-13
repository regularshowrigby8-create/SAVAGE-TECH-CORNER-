package com.savage.soulbrowser;

import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import com.savage.soulbrowser.ui.MainActivity;

/**
 * AC-002 (back/forward mirror the WebView history) and AC-003 (reload, stop and home).
 *
 * <p>The pages are injected with {@code loadDataWithBaseURL}, so the real WebView, the real
 * history stack and the real navigation controls are exercised without depending on a third
 * party site being reachable during the test run.
 */
@RunWith(AndroidJUnit4.class)
public class NavigationStateTest {

    private static final String PAGE_A = "https://example.com/a";
    private static final String PAGE_B = "https://example.com/b";

    @Before
    public void clearLocalState() {
        TestSupport.wipeLocalData();
    }

    @After
    public void restoreRadios() {
        TestSupport.setNetworkEnabled(true);
    }

    @Test
    public void backAndForwardReflectWebViewHistory() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            // Both pages have to be settled history entries: a load that is still in flight is not in
            // the in-page history yet, so waiting only for the URL races the browser.
            TestSupport.navigateForReal(scenario, PAGE_A);
            TestSupport.waitForSettledNavigation(scenario, PAGE_A);
            TestSupport.navigateForReal(scenario, PAGE_B);
            TestSupport.waitForSettledNavigation(scenario, PAGE_B);
            TestSupport.waitUntil("page B to have a history entry behind it",
                    () -> canGoBack(scenario), () -> TestSupport.describeBrowser(scenario));

            TestSupport.waitUntil("the Back control to be enabled", () -> enabled(scenario, R.id.btn_nav_back));

            TestSupport.on(scenario, activity -> activity.findViewById(R.id.btn_nav_back).performClick());
            TestSupport.waitUntil("the browser to return to page A",
                    () -> PAGE_A.equals(currentUrl(scenario)),
                    () -> TestSupport.describeBrowser(scenario));
            TestSupport.waitUntil("the Forward control to be enabled once we are back",
                    () -> enabled(scenario, R.id.btn_nav_forward));

            TestSupport.on(scenario, activity -> activity.findViewById(R.id.btn_nav_forward).performClick());
            TestSupport.waitUntil("the browser to move forward to page B again",
                    () -> PAGE_B.equals(currentUrl(scenario)),
                    () -> TestSupport.describeBrowser(scenario));
        }
    }

    @Test
    public void reloadStopsLoadingAndHomeResets() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.navigateForReal(scenario, PAGE_A);
            TestSupport.waitForSettledNavigation(scenario, PAGE_A);
            Assert.assertEquals("an idle page offers Reload",
                    TestSupport.onResult(scenario, activity -> activity.getString(R.string.action_reload)),
                    TestSupport.description(scenario, R.id.btn_action_reload));

            // The click is a real click: the application asks the WebView to reload. What a test
            // cannot schedule is when Chromium calls back with onPageStarted, so that callback is
            // delivered explicitly - it is the same call the WebView client makes - and what the user
            // then sees is asserted.
            TestSupport.on(scenario, activity -> activity.findViewById(R.id.btn_action_reload).performClick());
            TestSupport.on(scenario, activity -> activity.onPageStarted(PAGE_A));
            TestSupport.waitUntil("the reload to be in flight with a Stop affordance",
                    () -> isLoading(scenario)
                            && TestSupport.onResult(scenario, activity -> activity.getString(R.string.action_stop))
                            .equals(TestSupport.description(scenario, R.id.btn_action_reload)),
                    () -> "loading=" + isLoading(scenario) + " affordance='"
                            + TestSupport.description(scenario, R.id.btn_action_reload) + "'");
            Assert.assertEquals("a load in flight shows the progress bar",
                    View.VISIBLE, TestSupport.visibility(scenario, R.id.page_progress_bar));

            // The second click really stops the load; the settle then restores Reload.
            TestSupport.on(scenario, activity -> activity.findViewById(R.id.btn_action_reload).performClick());
            TestSupport.on(scenario, activity -> activity.onPageFinished(PAGE_A, "Page A"));
            TestSupport.waitUntil("Stop to end the load and restore the Reload affordance",
                    () -> !isLoading(scenario)
                            && TestSupport.onResult(scenario, activity -> activity.getString(R.string.action_reload))
                            .equals(TestSupport.description(scenario, R.id.btn_action_reload)),
                    () -> "loading=" + isLoading(scenario) + " affordance='"
                            + TestSupport.description(scenario, R.id.btn_action_reload) + "'");
            Assert.assertNotEquals("a settled load hides the progress bar",
                    View.VISIBLE, TestSupport.visibility(scenario, R.id.page_progress_bar));

            TestSupport.on(scenario, activity -> activity.findViewById(R.id.btn_nav_home).performClick());
            TestSupport.waitUntil("Home to request the home URL",
                    () -> MainActivity.HOME_URL.equals(currentUrl(scenario)),
                    () -> TestSupport.describeBrowser(scenario));
        }
    }

    private static String currentUrl(ActivityScenario<MainActivity> scenario) {
        return TestSupport.onResult(scenario, activity -> TestSupport.webViewOf(activity).getUrl());
    }

    private static boolean canGoBack(ActivityScenario<MainActivity> scenario) {
        return Boolean.TRUE.equals(TestSupport.onResult(scenario,
                activity -> TestSupport.webViewOf(activity).canGoBack()));
    }

    private static boolean isLoading(ActivityScenario<MainActivity> scenario) {
        return Boolean.TRUE.equals(TestSupport.onResult(scenario, MainActivity::isLoading));
    }

    private static boolean enabled(ActivityScenario<MainActivity> scenario, int viewId) {
        return Boolean.TRUE.equals(TestSupport.onResult(scenario,
                activity -> ((View) activity.findViewById(viewId)).isEnabled()));
    }
}
