package com.savage.soulbrowser;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.savage.soulbrowser.browser.NetworkStatus;
import com.savage.soulbrowser.data.BrowserDatabase;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    /**
     * Upper bound for every wait. WebView work happens off the test thread, and a hosted emulator
     * that is also booting the system WebView provider can take tens of seconds for the first load
     * of a test run; a bound that is too tight turns slow infrastructure into a reported
     * application defect.
     */
    public static final long TIMEOUT_MS = 45_000L;

    /** Preferences file name of {@code BrowserPreferences} (package private there). */
    public static final String PREFERENCES_NAME = "savage_browser_prefs";

    private static final String PAGE_TEMPLATE =
            "<!DOCTYPE html><html><head><title>%s</title></head><body><h1>%s</h1></body></html>";

    private TestSupport() {
    }

    public static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /**
     * Removes the local store and the preferences so a test really starts from a first launch.
     *
     * <p>The application's cached connection is dropped first, and only then are the files removed:
     * deleting a database file under a live {@link android.database.sqlite.SQLiteOpenHelper} leaves
     * that helper writing into an unlinked inode, so the next test would see a mix of stale rows and
     * rows that never reach the disk. The file is left absent, which is the state a first launch
     * really has and the precondition the corruption-recovery tests assert.
     */
    public static void wipeLocalData() {
        Context context = context();
        SavageBrowserApp.from(context).reopenLocalStore();
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
        waitUntil(what, condition, () -> "no observation was supplied");
    }

    /**
     * Waits for {@code condition}, and on timeout reports what was actually observed. A timeout
     * that only says "the page never loaded" cannot be acted on; one that also prints the URL, the
     * load flag and the view state can.
     */
    public static void waitUntil(String what, BooleanSupplier condition, Supplier<String> observed) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(100L);
        }
        Assert.fail("timed out after " + TIMEOUT_MS + " ms waiting for " + what
                + "; observed: " + observed.get());
    }

    /**
     * One-line description of what the browser is showing, for timeout messages: every WebView URL
     * in the container, the load flag, the view state and the tab count the dock displays. Reading
     * it never throws, because it is only ever used while reporting a failure.
     */
    public static String describeBrowser(ActivityScenario<MainActivity> scenario) {
        try {
            return onResult(scenario, activity -> {
                StringBuilder urls = new StringBuilder();
                ViewGroup container = activity.findViewById(R.id.webview_container);
                for (int i = 0; container != null && i < container.getChildCount(); i++) {
                    View child = container.getChildAt(i);
                    if (child instanceof WebView) {
                        urls.append(urls.length() == 0 ? "" : ", ").append(((WebView) child).getUrl());
                    }
                }
                View dock = activity.findViewById(R.id.btn_nav_tabs);
                return "urls=[" + urls + "] loading=" + activity.isLoading()
                        + " state=" + activity.currentViewState()
                        + " loaded=" + activity.hasLoadedPage()
                        + " tabs=" + (dock instanceof android.widget.TextView
                                ? ((android.widget.TextView) dock).getText() : "?");
            });
        } catch (RuntimeException | AssertionError problem) {
            return "browser state unavailable (" + problem.getClass().getSimpleName() + ")";
        }
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

    /**
     * Loads deterministic content into the real WebView without depending on the network.
     *
     * <p>The page is published at {@code baseUrl} in two ways, because a data load with a null
     * history URL is recorded as {@code about:blank}: the base URL resolves the page's own origin,
     * and the history URL becomes the history entry. Chromium still reports the load itself as a
     * {@code data:} URL, so this helper is for tests that need real rendered content (titles, the
     * back/forward stack) rather than for tests that need the application to know an address -
     * those navigate through the omnibox with {@link #navigateForReal}.
     */
    public static void injectPage(MainActivity activity, String baseUrl, String heading) {
        String html = String.format(PAGE_TEMPLATE, heading, heading);
        webViewOf(activity).loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", baseUrl);
    }

    /** The tab count the dock shows. The dock control is a {@link android.widget.TextView}. */
    /**
     * Navigates the way a user does: the address is typed into the omnibox and submitted, so the
     * application's own resolution, policy, tab bookkeeping and history recording all run.
     *
     * <p>A CI emulator has no route to the internet, so the page itself reports a load failure -
     * but the navigation is real: the WebView is asked for that URL, the application knows it, and
     * the URL is what the WebView reports afterwards. Tests that need the *content* of a page use
     * {@link #injectPage}, and tests that need the application to know an address use this.
     */
    public static void navigateForReal(MainActivity activity, String url) {
        android.widget.EditText omnibox = activity.findViewById(R.id.edit_omnibox);
        omnibox.setText(url);
        omnibox.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
    }

    public static void navigateForReal(ActivityScenario<MainActivity> scenario, String url) {
        on(scenario, activity -> navigateForReal(activity, url));
    }

    /** The title of the document the active WebView is showing, or {@code null}. */
    public static String pageTitle(MainActivity activity) {
        return webViewOf(activity).getTitle();
    }

    public static int openTabCount(MainActivity activity) {
        android.widget.TextView tabs = activity.findViewById(R.id.btn_nav_tabs);
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
     * Waits until a real navigation has <em>settled</em>: the WebView reports the address and no load
     * is in flight.
     *
     * <p>The WebView reports the URL as soon as a load starts, but a load that is still in flight has
     * not been committed to the in-page history yet - cancelling it (by starting the next navigation,
     * or by pressing Back) discards the entry. Tests that then rely on the history, or that press
     * Back, must wait for this and not only for the URL, or they race the browser instead of testing
     * it.
     */
    public static void waitForSettledNavigation(ActivityScenario<MainActivity> scenario, String url) {
        waitUntil("the navigation to " + url + " to settle",
                () -> url.equals(onResult(scenario, activity -> webViewOf(activity).getUrl()))
                        && !Boolean.TRUE.equals(onResult(scenario, MainActivity::isLoading)),
                () -> describeBrowser(scenario));
    }

    /**
     * The address the currently <em>resumed</em> browser shows, or {@code null} when no browser is
     * resumed.
     *
     * <p>Used after another activity (a list screen) has taken the screen: the platform may destroy a
     * backgrounded activity and recreate it from the intent that started it. What the user sees - a
     * browser on that address - is the same either way, so the assertion is made against whatever
     * browser activity is in front instead of against one particular instance.
     */
    public static String resumedBrowserAddress() {
        AtomicReference<String> address = new AtomicReference<>();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity activity :
                        androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                                .getActivitiesInStage(
                                        androidx.test.runner.lifecycle.Stage.RESUMED)) {
                    if (activity instanceof MainActivity) {
                        View omnibox = activity.findViewById(R.id.edit_omnibox);
                        address.set(omnibox instanceof android.widget.TextView
                                ? String.valueOf(((android.widget.TextView) omnibox).getText())
                                : null);
                        return;
                    }
                }
            });
        } catch (RuntimeException | AssertionError unavailable) {
            return null;
        }
        return address.get();
    }

    /**
     * The text a view currently shows, or a short description of why it could not be read. Used only
     * while reporting a failed wait: a timeout that does not say what the view actually said cannot
     * be acted on, and "never showed X" is not enough to tell an empty view from an ambiguous match.
     */
    private static String observedText(int viewId) {
        AtomicReference<String> observed = new AtomicReference<>("(no view found)");
        // ViewAction has two abstract methods, so it cannot be written as a lambda.
        androidx.test.espresso.ViewAction readText = new androidx.test.espresso.ViewAction() {
            @Override
            public org.hamcrest.Matcher<View> getConstraints() {
                return org.hamcrest.Matchers.any(View.class);
            }

            @Override
            public String getDescription() {
                return "read the text of view " + viewId;
            }

            @Override
            public void perform(androidx.test.espresso.UiController controller, View view) {
                observed.set(view instanceof android.widget.TextView
                        ? String.valueOf(((android.widget.TextView) view).getText())
                        : view.getClass().getSimpleName());
            }
        };
        try {
            androidx.test.espresso.Espresso.onView(
                            androidx.test.espresso.matcher.ViewMatchers.withId(viewId))
                    .perform(readText);
        } catch (RuntimeException | AssertionError unreadable) {
            observed.set("(unreadable: " + unreadable.getClass().getSimpleName() + ")");
        }
        return observed.get();
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
                + expectedSubstring + "'; observed " + observedText(viewId), last);
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
