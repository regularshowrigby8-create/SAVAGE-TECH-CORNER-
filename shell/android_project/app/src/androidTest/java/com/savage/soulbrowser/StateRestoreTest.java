package com.savage.soulbrowser;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

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
 * AC-010, AC-026 and AC-027: a configuration change or an Activity recreation must not lose the
 * page the user is on, and the tab set must come back with it.
 */
@RunWith(AndroidJUnit4.class)
public class StateRestoreTest {

    private static final String PAGE = "https://example.com/rotation";

    @Before
    public void clearLocalState() {
        TestSupport.wipeLocalData();
    }

    @After
    public void restoreRadios() {
        TestSupport.setNetworkEnabled(true);
    }

    @Test
    public void currentUrlSurvivesRecreation() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.on(scenario, activity -> TestSupport.injectPage(activity, PAGE, "Rotation"));
            TestSupport.waitUntil("the page to load", () -> PAGE.equals(url(scenario)),
                    () -> TestSupport.describeBrowser(scenario));

            scenario.recreate();

            TestSupport.waitUntil("the restored browser to be back on the same page",
                    () -> PAGE.equals(url(scenario)),
                    () -> TestSupport.describeBrowser(scenario));
            Assert.assertEquals("one tab is restored", 1,
                    TestSupport.onResult(scenario, TestSupport::openTabCount).intValue());
        }
    }

    @Test
    public void tabsSurviveRecreation() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.on(scenario, activity -> TestSupport.injectPage(activity, PAGE, "Rotation"));
            TestSupport.waitUntil("the first tab to load", () -> PAGE.equals(url(scenario)),
                    () -> TestSupport.describeBrowser(scenario));

            // Open a second tab through the same dialog the user would use.
            TestSupport.on(scenario, activity -> activity.findViewById(R.id.btn_nav_tabs).performClick());
            onView(withText(R.string.tabs_new)).inRoot(isDialog()).perform(click());
            TestSupport.waitUntil("a second tab to exist",
                    () -> TestSupport.onResult(scenario, TestSupport::openTabCount) == 2,
                    () -> TestSupport.describeBrowser(scenario));

            scenario.recreate();

            TestSupport.waitUntil("the restored session to still have both tabs",
                    () -> TestSupport.onResult(scenario, TestSupport::openTabCount).intValue() == 2,
                    () -> TestSupport.describeBrowser(scenario));
            TestSupport.waitUntil("the restored session to show the page we were on",
                    () -> url(scenario).startsWith("https://"),
                    () -> TestSupport.describeBrowser(scenario));
        }
    }

    @Test
    public void rotationKeepsThePageVisible() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.on(scenario, activity -> TestSupport.injectPage(activity, PAGE, "Rotation"));
            TestSupport.waitUntil("the page to load", () -> PAGE.equals(url(scenario)),
                    () -> TestSupport.describeBrowser(scenario));

            Integer before = TestSupport.onResult(scenario,
                    activity -> System.identityHashCode(TestSupport.webViewOf(activity)));

            TestSupport.on(scenario, activity -> activity.setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            TestSupport.sleep(1000L);

            Assert.assertEquals("landscape keeps the same page", PAGE, url(scenario));
            Assert.assertEquals("the manifest handles the rotation, so no WebView is recreated",
                    before, TestSupport.onResult(scenario,
                            activity -> System.identityHashCode(TestSupport.webViewOf(activity))));
            Assert.assertEquals("landscape keeps browser chrome on screen",
                    View.VISIBLE, TestSupport.visibility(scenario, R.id.edit_omnibox));

            TestSupport.on(scenario, activity -> activity.setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            TestSupport.sleep(1000L);
            Assert.assertEquals("portrait keeps the same page", PAGE, url(scenario));
        }
    }

    private static String url(ActivityScenario<MainActivity> scenario) {
        return TestSupport.onResult(scenario, activity -> TestSupport.webViewOf(activity).getUrl());
    }
}
