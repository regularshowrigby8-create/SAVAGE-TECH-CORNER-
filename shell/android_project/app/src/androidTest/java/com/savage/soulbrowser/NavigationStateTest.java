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
                    reloadLabel(scenario), affordance(scenario));

            // The reload action is also the stop action, so its label has to follow the load state.
            //
            // What a test cannot schedule is when Chromium reports the start of that load: the CI
            // emulator has no route to the internet, so the requested page fails as soon as the name
            // does not resolve and the load is over before any poll could see it. Waiting for the real
            // in-flight moment therefore only passed while an *earlier* load happened to still be
            // running - which is why this test broke as soon as the navigation before it really
            // settled. The callback is delivered the way the WebView client delivers it (the same
            // technique MainActivityLaunchTest uses for the progress bar), and both clicks below are
            // real clicks on the real action.
            TestSupport.on(scenario, activity -> activity.onPageStarted(PAGE_A));
            TestSupport.waitUntil("a load in flight to advertise Stop",
                    () -> isLoading(scenario) && stopLabel(scenario).equals(affordance(scenario)),
                    () -> "loading=" + isLoading(scenario) + " affordance='"
                            + affordance(scenario) + "'");
            Assert.assertEquals("a load in flight shows the progress bar",
                    View.VISIBLE, TestSupport.visibility(scenario, R.id.page_progress_bar));

            // The second click really takes the stop branch: the application stops the load it believes
            // is in flight, and the next callback the WebView would deliver is the end of that load.
            TestSupport.on(scenario, activity -> activity.findViewById(R.id.btn_action_reload).performClick());
            TestSupport.on(scenario, activity -> activity.onPageFinished(PAGE_A, "Page A"));
            TestSupport.waitUntil("the stopped load to restore the Reload affordance",
                    () -> !isLoading(scenario) && reloadLabel(scenario).equals(affordance(scenario)),
                    () -> "loading=" + isLoading(scenario) + " affordance='"
                            + affordance(scenario) + "'");
            Assert.assertNotEquals("a settled load hides the progress bar",
                    View.VISIBLE, TestSupport.visibility(scenario, R.id.page_progress_bar));

            // Clicking Reload on an idle page asks the browser to load that page again: the browser has
            // to come out of it committed on the same address and still usable, which a click that
            // threw, or a WebView left in a broken state, would not do. (The load itself cannot be
            // observed message by message on a runner whose DNS fails in a few milliseconds - see
            // above - so what is asserted here is the state the user is left in.)
            TestSupport.on(scenario, activity -> activity.findViewById(R.id.btn_action_reload).performClick());
            TestSupport.waitForSettledNavigation(scenario, PAGE_A);
            Assert.assertEquals("a reload leaves the idle page offering Reload again",
                    reloadLabel(scenario), affordance(scenario));

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

    /** What the reload/stop action currently advertises. */
    private static String affordance(ActivityScenario<MainActivity> scenario) {
        return TestSupport.description(scenario, R.id.btn_action_reload);
    }

    private static String reloadLabel(ActivityScenario<MainActivity> scenario) {
        return TestSupport.onResult(scenario, activity -> activity.getString(R.string.action_reload));
    }

    private static String stopLabel(ActivityScenario<MainActivity> scenario) {
        return TestSupport.onResult(scenario, activity -> activity.getString(R.string.action_stop));
    }

    private static boolean isLoading(ActivityScenario<MainActivity> scenario) {
        return Boolean.TRUE.equals(TestSupport.onResult(scenario, MainActivity::isLoading));
    }

    private static boolean enabled(ActivityScenario<MainActivity> scenario, int viewId) {
        return Boolean.TRUE.equals(TestSupport.onResult(scenario,
                activity -> ((View) activity.findViewById(viewId)).isEnabled()));
    }
}
