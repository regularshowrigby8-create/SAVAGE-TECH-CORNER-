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
            // Both pages have to be *settled* history entries before Back is pressed. A navigation
            // that is still in flight has not been committed to the in-page history, and Back is also
            // the gesture that leaves the app when there is nothing behind the current page - so
            // pressing it too early finished the activity instead of returning to page A.
            TestSupport.navigateForReal(scenario, PAGE_A);
            TestSupport.waitForSettledNavigation(scenario, PAGE_A);
            TestSupport.navigateForReal(scenario, PAGE_B);
            TestSupport.waitForSettledNavigation(scenario, PAGE_B);
            TestSupport.waitUntil("page B to have a history entry behind it",
                    () -> canGoBack(scenario), () -> TestSupport.describeBrowser(scenario));

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
            // "Back left the app" means the activity is finishing, or is already gone - the platform
            // destroys it as soon as there is nothing left to show, and a wait that only accepted
            // `isFinishing()` would then miss the very behaviour it is checking for.
            TestSupport.waitUntil("the browser activity to finish at its root page",
                    () -> isGone(scenario)
                            || Boolean.TRUE.equals(observation(scenario, android.app.Activity::isFinishing)),
                    () -> {
                        Boolean finishing = observation(scenario, android.app.Activity::isFinishing);
                        return finishing == null
                                ? "browser state unavailable (the activity is already gone)"
                                : "finishing=" + finishing;
                    });
        }
    }

    /**
     * Sends a real system back press through the instrumentation, not a synthetic view click.
     *
     * <p>Key dispatch is capped first. {@code sendKeyDownUpSync} waits for the key to be delivered
     * through the system's key-dispatch timeout, and on a loaded emulator that wait can outlive the
     * browser: the activity is already gone when the timeout expires, so the call throws and hides
     * the behaviour the test is about. A short cap returns as soon as the key has been handled by the
     * application instead of outliving it.
     */
    private static void pressBack() {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand("settings put global key_dispatch_timeout_millis 1000");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        SystemClock.sleep(250L);
    }

    /** {@code true} once the browser activity no longer exists. */
    private static boolean isGone(ActivityScenario<MainActivity> scenario) {
        return scenario.getState() == ActivityScenario.State.DESTROYED;
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
