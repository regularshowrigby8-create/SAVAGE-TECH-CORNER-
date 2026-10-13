package com.savage.soulbrowser;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.savage.soulbrowser.browser.NetworkStatus;
import com.savage.soulbrowser.data.BrowserDatabase;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.junit.Assert;
import com.savage.soulbrowser.ui.MainActivity;

/**
 * Shared helpers for the instrumented suite: launching the real activities, reaching into the real
 * view hierarchy, waiting for asynchronous WebView work and toggling the emulator network.
 *
 * <p>The helpers only observe; every assertion lives in the test that owns the acceptance
 * criterion, so a helper can never silently pass a test.
 */
public final class TestSupport {

    /** Generous upper bound: WebView work happens off the test thread on a shared CI emulator. */
    public static final long TIMEOUT_MS = 20_000L;

    /** Preferences file name of {@code BrowserPreferences} (package private there). */
    public static final String PREFERENCES_NAME = "savage_browser_prefs";

    private static final String PAGE_TEMPLATE =
            "<!DOCTYPE html><html><head><title>%s</title></head><body><h1>%s</h1></body></html>";

    private TestSupport() {
    }

    public static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** Removes the local database and preferences so a test really starts from a first launch. */
    public static void wipeLocalData() {
        Context context = context();
        BrowserDatabase.deleteDatabaseFiles(context, BrowserDatabase.DATABASE_NAME);
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    public static ActivityScenario<MainActivity> launchBrowser() {
        return ActivityScenario.launch(browserIntent(null));
    }

    /** Launches the browser exactly like an {@code ACTION_VIEW} link would. */
    public static ActivityScenario<MainActivity> launchBrowserWith(String url) {
        return ActivityScenario.launch(browserIntent(url));
    }

    private static Intent browserIntent(String url) {
        Intent intent = new Intent(context(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (url != null) {
            intent.putExtra(MainActivity.EXTRA_OPEN_URL, url);
        }
        return intent;
    }

    /** Runs {@code action} on the main thread and rethrows any failure on the test thread. */
    public static <A extends Activity> void on(ActivityScenario<A> scenario, Consumer<A> action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        scenario.onActivity(activity -> {
            try {
                action.accept(activity);
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });
        Throwable problem = failure.get();
        if (problem != null) {
            throw new AssertionError(problem.getMessage(), problem);
        }
    }

    /** Reads a view on the main thread; returns whatever the block returns. */
    public static <A extends Activity, T> T onResult(ActivityScenario<A> scenario,
                                                     java.util.function.Function<A, T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        on(scenario, activity -> result.set(action.apply(activity)));
        return result.get();
    }

    public static void waitUntil(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(100L);
        }
        Assert.fail("timed out after " + TIMEOUT_MS + " ms waiting for " + what);
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", interrupted);
        }
    }

    /** The WebView the tab manager attached to the browser container. */
    public static WebView webViewOf(MainActivity activity) {
        ViewGroup container = activity.findViewById(R.id.webview_container);
        Assert.assertNotNull("the WebView container must exist", container);
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof WebView && child.getVisibility() == View.VISIBLE) {
                return (WebView) child;
            }
        }
        Assert.fail("no visible WebView is attached to the container");
        return null;
    }

    /** Loads deterministic content into the real WebView without depending on the network. */
    public static void injectPage(MainActivity activity, String baseUrl, String heading) {
        String html = String.format(PAGE_TEMPLATE, heading, heading);
        webViewOf(activity).loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null);
    }

    public static int openTabCount(MainActivity activity) {
        Button tabs = activity.findViewById(R.id.btn_nav_tabs);
        return Integer.parseInt(tabs.getText().toString());
    }

    /**
     * The content description of a view, as a String. Views built from a string resource return a
     * String at runtime, but the declared type is CharSequence, which makes the
     * {@code assertEquals(String, Object, Object)} / {@code assertEquals(String, String, String)}
     * pair ambiguous for javac. Reading it through this helper keeps the assertion honest about
     * what it compares.
     */
    public static String descriptionOf(MainActivity activity, int viewId) {
        View view = activity.findViewById(viewId);
        Assert.assertNotNull("view " + viewId + " exists", view);
        CharSequence description = view.getContentDescription();
        return description == null ? "" : description.toString();
    }

    public static String description(ActivityScenario<MainActivity> scenario, int viewId) {
        return onResult(scenario, activity -> descriptionOf(activity, viewId));
    }

    public static String textOf(MainActivity activity, int viewId) {
        android.widget.TextView view = activity.findViewById(viewId);
        return view == null || view.getText() == null ? null : view.getText().toString();
    }

    public static int visibilityOf(MainActivity activity, int viewId) {
        View view = activity.findViewById(viewId);
        Assert.assertNotNull("view " + viewId + " must exist", view);
        return view.getVisibility();
    }

    /**
     * Runs a shell command through {@code UiAutomation}, which executes as the shell user - the
     * same mechanism the platform's own UiAutomator uses. Used only to drive the emulator network.
     */
    public static void shell(String command) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ParcelFileDescriptor descriptor =
                     instrumentation.getUiAutomation().executeShellCommand(command);
             FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor())) {
            byte[] buffer = new byte[256];
            while (stream.read(buffer) != -1) {
                // drain the command output
            }
        } catch (IOException exception) {
            throw new AssertionError("shell command failed: " + command, exception);
        }
    }

    /**
     * Reads recent logcat output through {@code UiAutomation}. This is the emulator logcat
     * evidence the acceptance criteria ask for, captured by the test that caused the log line
     * rather than by hand.
     */
    public static String logcat(String filterSpec, int lineCount) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        StringBuilder output = new StringBuilder();
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation()
                .executeShellCommand("logcat -d -t " + lineCount + " -s " + filterSpec);
             FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor())) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                output.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException exception) {
            throw new AssertionError("logcat dump failed for filter " + filterSpec, exception);
        }
        return output.toString();
    }

    public static boolean isOnline() {
        return new NetworkStatus(context()).isOnline();
    }

    /** Visibility of a view of the launched activity, read on the main thread. */
    public static int visibility(ActivityScenario<MainActivity> scenario, int viewId) {
        return onResult(scenario, activity -> visibilityOf(activity, viewId));
    }

    /**
     * Waits until an Espresso-visible view shows the expected text. Used after an activity starts
     * another activity, where the top activity is the one Espresso must look at.
     */
    public static void waitForViewText(int viewId, String expectedSubstring) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        AssertionError last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                androidx.test.espresso.Espresso.onView(
                                androidx.test.espresso.matcher.ViewMatchers.withId(viewId))
                        .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                                androidx.test.espresso.matcher.ViewMatchers.withText(
                                        org.hamcrest.CoreMatchers.containsString(expectedSubstring))));
                return;
            } catch (AssertionError | RuntimeException problem) {
                last = new AssertionError(problem.getMessage(), problem);
                sleep(250L);
            }
        }
        throw new AssertionError("view " + viewId + " never showed text containing '"
                + expectedSubstring + "'", last);
    }

    /** Turns the emulator radios on or off and waits until the app sees the new state. */
    public static void setNetworkEnabled(boolean enabled) {
        shell("svc wifi " + (enabled ? "enable" : "disable"));
        shell("svc data " + (enabled ? "enable" : "disable"));
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (isOnline() == enabled) {
                // give ConnectivityManager a moment to settle so the app path is deterministic
                sleep(500L);
                return;
            }
            sleep(250L);
        }
        Assert.fail("the emulator network did not turn " + (enabled ? "on" : "off"));
    }
}
