package com.savage.soulbrowser;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import android.os.SystemClock;
import android.view.KeyEvent;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import com.savage.soulbrowser.ui.MainActivity;

/**
 * AC-030 and AC-031: the system Back gesture walks back through the page history while there is
 * history, and leaves the app once the root page is reached.
 */
@RunWith(AndroidJUnit4.class)
public class BackNavigationTest {

    private static final String PAGE_A = "https://example.com/back/a";
    private static final String PAGE_B = "https://example.com/back/b";

    @Before
    public void clearLocalState() {
        TestSupport.wipeLocalData();
    }

    @After
    public void restoreRadios() {
        TestSupport.setNetworkEnabled(true);
    }

    @Test
    public void systemBackGoesBackInWebView() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.on(scenario, activity -> TestSupport.injectPage(activity, PAGE_A, "Page A"));
            TestSupport.waitUntil("page A to load", () -> PAGE_A.equals(url(scenario)));
            TestSupport.on(scenario, activity -> TestSupport.injectPage(activity, PAGE_B, "Page B"));
            TestSupport.waitUntil("page B to load", () -> PAGE_B.equals(url(scenario)));

            pressBack();
            TestSupport.waitUntil("system Back to return to page A", () -> PAGE_A.equals(url(scenario)));
            Assert.assertFalse("the browser activity is still running",
                    TestSupport.onResult(scenario, activity -> activity.isFinishing()));
        }
    }

    @Test
    public void systemBackFinishesAtRoot() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.on(scenario, activity -> TestSupport.injectPage(activity, PAGE_A, "Page A"));
            TestSupport.waitUntil("page A to load", () -> PAGE_A.equals(url(scenario)));
            Assert.assertFalse("page A is the root of this session",
                    TestSupport.onResult(scenario,
                            activity -> TestSupport.webViewOf(activity).canGoBack()));

            pressBack();

            TestSupport.waitUntil("the browser activity to finish at its root page",
                    () -> TestSupport.onResult(scenario, activity -> activity.isFinishing()));
        }
    }

    /** Sends a real system back press through the instrumentation, not a synthetic view click. */
    private static void pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        SystemClock.sleep(250L);
    }

    private static String url(ActivityScenario<MainActivity> scenario) {
        return TestSupport.onResult(scenario, activity -> {
            if (activity.isFinishing()) {
                return null;
            }
            return TestSupport.webViewOf(activity).getUrl();
        });
    }
}
