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
            TestSupport.navigateForReal(scenario, PAGE_A);
            TestSupport.waitUntil("page A to load", () -> PAGE_A.equals(url(scenario)),
                    () -> TestSupport.describeBrowser(scenario));
            // Page B must be the committed history entry before Back is pressed. Back is also the
            // gesture that leaves the app when there is nothing behind the current page, so pressing
            // it while the load is still in flight finished the activity and made the rest of the
            // test observe a destroyed activity instead of the browser.
            TestSupport.navigateForReal(scenario, PAGE_B);
            TestSupport.waitUntil("page B to become the current entry with history behind it",
                    () -> PAGE_B.equals(url(scenario)) && canGoBack(scenario),
                    () -> TestSupport.describeBrowser(scenario));

            pressBack();
            TestSupport.waitUntil("system Back to return to page A", () -> PAGE_A.equals(url(scenario)),
                    () -> TestSupport.describeBrowser(scenario));
            Assert.assertFalse("the browser activity is still running",
                    TestSupport.onResult(scenario, activity -> activity.isFinishing()));
        }
    }

    @Test
    public void systemBackFinishesAtRoot() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            // The launch requests the home page, and that request is the first entry of this
            // session's history: there is nothing behind it, so Back belongs to the system and the
            // activity must finish instead of doing nothing.
            TestSupport.waitUntil("the first page to settle",
                    () -> !TestSupport.onResult(scenario, MainActivity::isLoading),
                    () -> TestSupport.describeBrowser(scenario));
            Assert.assertFalse("the home page is the root of this session",
                    TestSupport.onResult(scenario,
                            activity -> TestSupport.webViewOf(activity).canGoBack()));

            pressBack();

            TestSupport.waitUntil("the browser activity to finish at its root page",
                    () -> TestSupport.onResult(scenario, activity -> activity.isFinishing()),
                    () -> TestSupport.describeBrowser(scenario));
        }
    }

    /** Sends a real system back press through the instrumentation, not a synthetic view click. */
    private static void pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        SystemClock.sleep(250L);
    }

    private static boolean canGoBack(ActivityScenario<MainActivity> scenario) {
        return Boolean.TRUE.equals(observation(scenario,
                activity -> TestSupport.webViewOf(activity).canGoBack()));
    }

    /**
     * Reads a value from the browser, or {@code null} once the activity is gone.
     *
     * <p>A Back press can legitimately finish the activity; that is the behaviour the root-page test
     * asserts. Reporting the resulting destroyed-activity exception as a crash would hide the
     * assertion the test was making, so the observation becomes a plain failed wait instead.
     */
    private static <T> T observation(ActivityScenario<MainActivity> scenario,
                                     java.util.function.Function<MainActivity, T> read) {
        try {
            return TestSupport.onResult(scenario, read);
        } catch (RuntimeException | AssertionError gone) {
            return null;
        }
    }

    private static String url(ActivityScenario<MainActivity> scenario) {
        return observation(scenario, activity -> {
            if (activity.isFinishing()) {
                return null;
            }
            return TestSupport.webViewOf(activity).getUrl();
        });
    }
}
