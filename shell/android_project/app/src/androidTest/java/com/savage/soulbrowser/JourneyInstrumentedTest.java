package com.savage.soulbrowser;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.hasSibling;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.savage.soulbrowser.data.BookmarkRepository;
import com.savage.soulbrowser.data.HistoryRepository;
import com.savage.soulbrowser.ui.BookmarksActivity;
import com.savage.soulbrowser.ui.HistoryActivity;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import com.savage.soulbrowser.ui.MainActivity;

/**
 * AC-004 to AC-009 and AC-034: the six core journeys, driven through the real UI.
 *
 * <p>Each journey drives the real application: addresses are submitted through the omnibox (see
 * {@link TestSupport#navigateForReal}), so the WebView, its history, the tab manager, the
 * repositories and the activities are all the production ones. A CI emulator has no route to the
 * internet, so a page body cannot be relied on - the journeys assert what the browser does with an
 * address it was given, which is exactly what the acceptance criteria describe. The journey that
 * must involve connectivity - offline detection - turns the emulator radios off instead.
 */
@RunWith(AndroidJUnit4.class)
public class JourneyInstrumentedTest {

    private static final String PAGE_A = "https://example.com/a";
    private static final String PAGE_B = "https://example.com/b";

    /** Screens this test put into the browser's task, so its teardown can close them again. */
    private final List<Activity> screensOpenedByThisTest = new ArrayList<>();

    @Before
    public void clearLocalState() {
        TestSupport.wipeLocalData();
    }

    @After
    public void closeTheScreensThisTestOpenedAndRestoreRadios() {
        // A journey that fails half way through leaves its list screen in the task, on top of the
        // browser: the next journey would then be testing through that screen. The screen closes
        // itself when the row is opened, so this only ever has work to do after a failure.
        for (Activity screen : screensOpenedByThisTest) {
            TestSupport.readOnMainThread(screen, activity -> {
                if (!activity.isFinishing()) {
                    activity.finish();
                }
                return null;
            });
        }
        screensOpenedByThisTest.clear();
        TestSupport.setNetworkEnabled(true);
    }

    @Test
    public void j1BrowseThenBackThenHome() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.navigateForReal(scenario, PAGE_A);
            TestSupport.waitForSettledNavigation(scenario, PAGE_A);
            Assert.assertTrue("a loaded page counts as content",
                    TestSupport.onResult(scenario, MainActivity::hasLoadedPage));

            // Both pages have to be settled history entries: the WebView reports a URL as soon as a
            // load starts, but a navigation that is still in flight has not been committed to the
            // history, and pressing Back then cancels the pending entry instead of walking back.
            TestSupport.navigateForReal(scenario, PAGE_B);
            TestSupport.waitForSettledNavigation(scenario, PAGE_B);
            TestSupport.waitUntil("the second page to have a history entry behind it",
                    () -> canGoBack(scenario), () -> TestSupport.describeBrowser(scenario));

            TestSupport.on(scenario, activity -> activity.findViewById(R.id.btn_nav_back).performClick());
            TestSupport.waitUntil("Back to return to the first page", () -> PAGE_A.equals(url(scenario)),
                    () -> TestSupport.describeBrowser(scenario));

            TestSupport.on(scenario, activity -> activity.findViewById(R.id.btn_nav_home).performClick());
            TestSupport.waitUntil("Home to leave the visited page for the home URL",
                    () -> MainActivity.HOME_URL.equals(url(scenario)),
                    () -> TestSupport.describeBrowser(scenario));
        }
    }

    @Test
    public void j3BookmarkCurrentPageAndOpenIt() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.navigateForReal(scenario, PAGE_A);
            TestSupport.waitForSettledNavigation(scenario, PAGE_A);

            TestSupport.on(scenario,
                    activity -> activity.findViewById(R.id.btn_action_bookmark).performClick());

            BookmarkRepository bookmarks = bookmarks();
            TestSupport.waitUntil("the page to be stored as a bookmark",
                    () -> bookmarks.contains(PAGE_A));
            Assert.assertEquals("storing the current page keeps exactly one row", 1, bookmarks.count());
            TestSupport.waitUntil("the star to advertise removal", () -> TestSupport
                    .description(scenario, R.id.btn_action_bookmark)
                    .equals(TestSupport.onResult(scenario,
                            activity -> activity.getString(R.string.action_bookmark_remove))));

            // Opening the bookmark from the stored list must load it in the browser. The list is a
            // real screen and the row is opened through the row's own click listener; the browser then
            // comes back through the same intent the list sends, which is what makes the address
            // observable.
            openRow(BookmarksActivity.class, "example.com");
            assertBrowserCameBackTo(scenario, PAGE_A);
        }
    }

    @Test
    public void j3StarTwiceKeepsOneRow() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.navigateForReal(scenario, PAGE_A);
            // The star stores the address the browser is *on*. Acting while the navigation was still
            // provisional stored page A and then toggled something else - the error page Chromium
            // falls back to - so the second tap neither found nor removed page A's row.
            TestSupport.waitForSettledNavigation(scenario, PAGE_A);

            BookmarkRepository bookmarks = bookmarks();
            TestSupport.on(scenario,
                    activity -> activity.findViewById(R.id.btn_action_bookmark).performClick());
            TestSupport.waitUntil("the first tap to store one row", () -> bookmarks.count() == 1,
                    () -> TestSupport.describeBrowser(scenario));

            // The second tap toggles the same bookmark off again; two rows for one page can never
            // exist because the store has a unique key per canonical URL.
            TestSupport.on(scenario,
                    activity -> activity.findViewById(R.id.btn_action_bookmark).performClick());
            TestSupport.waitUntil("the second tap to remove the bookmark", () -> bookmarks.count() == 0,
                    () -> "rows=" + bookmarks.count() + " star="
                            + TestSupport.description(scenario, R.id.btn_action_bookmark) + " "
                            + TestSupport.describeBrowser(scenario));
        }
    }

    @Test
    public void j4HistoryRecordsVisitAndReopensIt() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            // The launch itself visits the home page, so the store legitimately holds more than the
            // visit this journey makes: what has to be true is that this address was recorded.
            TestSupport.navigateForReal(scenario, PAGE_A);
            TestSupport.waitUntil("the visit to be written to the local store", () -> {
                HistoryRepository history = history();
                return history.recent(5).stream()
                        .anyMatch(entry -> PAGE_A.equals(entry.getUrl()));
            }, () -> TestSupport.describeBrowser(scenario) + " " + historySummary());

            openRow(HistoryActivity.class, "example.com");
            assertBrowserCameBackTo(scenario, PAGE_A);
        }
    }

    @Test
    public void j5TabsOpenSwitchClose() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.navigateForReal(scenario, PAGE_A);
            TestSupport.waitForSettledNavigation(scenario, PAGE_A);
            Assert.assertEquals(1, tabCount(scenario));

            // Open a second tab from the tab switcher.
            TestSupport.on(scenario, activity -> activity.findViewById(R.id.btn_nav_tabs).performClick());
            onView(withText(R.string.tabs_title)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(R.string.tabs_new)).inRoot(isDialog()).perform(click());
            TestSupport.waitUntil("a second tab to exist", () -> tabCount(scenario) == 2,
                    () -> TestSupport.describeBrowser(scenario));

            // Switch back to the tab that shows page A.
            TestSupport.on(scenario, activity -> activity.findViewById(R.id.btn_nav_tabs).performClick());
            onView(withText(containsString("example.com"))).inRoot(isDialog()).perform(click());
            TestSupport.waitUntil("switching to restore page A", () -> PAGE_A.equals(url(scenario)),
                    () -> TestSupport.describeBrowser(scenario));
            Assert.assertEquals("switching does not drop a tab", 2, tabCount(scenario));

            // Close the other tab from the switcher; the session keeps the tab that is left.
            TestSupport.on(scenario, activity -> activity.findViewById(R.id.btn_nav_tabs).performClick());
            onView(allOf(withId(R.id.item_close),
                    hasSibling(hasDescendant(withText(containsString("savage-tech-corner.io"))))))
                    .inRoot(isDialog()).perform(click());
            TestSupport.waitUntil("the closed tab to disappear", () -> tabCount(scenario) == 1,
                    () -> TestSupport.describeBrowser(scenario));
            Assert.assertEquals("the surviving tab still shows the page we were on",
                    PAGE_A, url(scenario));
        }
    }

    @Test
    public void j6OfflineShowsRetryState() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.setNetworkEnabled(false);
            Assert.assertFalse("the emulator must really be offline", TestSupport.isOnline());

            TestSupport.on(scenario, activity -> {
                EditText omnibox = activity.findViewById(R.id.edit_omnibox);
                omnibox.setText(PAGE_A);
                omnibox.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
            });

            TestSupport.waitUntil("the offline state to be shown",
                    () -> TestSupport.visibility(scenario, R.id.state_overlay) == View.VISIBLE);
            TestSupport.on(scenario, activity -> {
                Assert.assertEquals("the offline state explains what happened",
                        activity.getString(R.string.state_offline_title),
                        TestSupport.textOf(activity, R.id.state_title));
                Assert.assertEquals("offline still points the user at the local data",
                        activity.getString(R.string.state_offline_message),
                        TestSupport.textOf(activity, R.id.state_message));
                Assert.assertEquals("offline offers Retry",
                        View.VISIBLE, TestSupport.visibilityOf(activity, R.id.state_retry));
            });

            TestSupport.setNetworkEnabled(true);
            TestSupport.on(scenario, activity -> activity.findViewById(R.id.state_retry).performClick());
            TestSupport.waitUntil("Retry to re-request the page once the network is back",
                    () -> PAGE_A.equals(url(scenario)));
        }
    }

    private static String url(ActivityScenario<MainActivity> scenario) {
        return TestSupport.onResult(scenario, activity -> TestSupport.webViewOf(activity).getUrl());
    }

    private static int tabCount(ActivityScenario<MainActivity> scenario) {
        return TestSupport.onResult(scenario, TestSupport::openTabCount);
    }

    /**
     * Waits until the browser is (back) in front, on {@code expected}.
     *
     * <p>Two facts are asserted together, because either one alone can be true for the wrong reason.
     * The address bar of the browser the list was opened from must show the address the list handed
     * back (the list reuses that instance through {@code CLEAR_TOP}), and that browser's window must
     * have the focus - a browser left behind another screen has no focus even though its address bar
     * still shows the page the user was on. The failure message adds the platform's own resumed
     * activity, so "the browser is in front" can be told apart from "something else is in front".
     */
    private static void assertBrowserCameBackTo(ActivityScenario<MainActivity> scenario,
                                                String expected) {
        TestSupport.waitUntil("the browser in front to show the opened address " + expected,
                () -> expected.equals(TestSupport.browserAddress(scenario))
                        && TestSupport.browserHasFocus(scenario),
                () -> "the address bar of the browser shows '"
                        + TestSupport.browserAddress(scenario) + "', its window has the focus: "
                        + TestSupport.browserHasFocus(scenario) + "; the platform reports as resumed: "
                        + TestSupport.platformResumedActivity()
                        + "; the test lifecycle monitor reports: "
                        + TestSupport.resumedActivityNames());
    }

    /**
     * Opens a list screen inside the browser's task and opens one of its rows, the way a user does.
     *
     * <p>{@link ActivityScenario#launch} cannot be used for this: it adds
     * {@code FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK}, which clears the task the browser is
     * in and destroys the browser itself - the very browser the row is supposed to come back to. That
     * is why these journeys used to fail with a browser that never reappeared. The instrumentation's
     * own {@code startActivitySync} adds the task flag without the clearing one, so the screen is
     * pushed on top of the browser exactly as the in-app screen is.
     *
     * <p>The row itself is opened by invoking its click listener on the row view of the real list -
     * the same listener the platform invokes when the row is tapped, and the same way the rest of this
     * suite presses the browser's buttons. Injected taps are used where the touch pipeline is what the
     * criterion is about (the system Back press in {@code BackNavigationTest}); here the criterion is
     * that opening a stored address navigates the browser, and an injected tap that lands on the
     * background browser's omnibox cannot test that - which is exactly how this journey failed.
     */
    private void openRow(Class<?> screen, String rowText) {
        Activity list = InstrumentationRegistry.getInstrumentation()
                .startActivitySync(new Intent(TestSupport.context(), screen)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        screensOpenedByThisTest.add(list);
        TestSupport.waitUntil("the " + screen.getSimpleName() + " screen to come to the front",
                () -> Boolean.TRUE.equals(
                        TestSupport.readOnMainThread(list, Activity::hasWindowFocus)),
                () -> "the screen never took the window focus; the platform reports as resumed: "
                        + TestSupport.platformResumedActivity());

        View row = TestSupport.readOnMainThread(list,
                activity -> clickableRowShowing(activity, rowText));
        Assert.assertNotNull("the " + screen.getSimpleName() + " screen shows a row for " + rowText
                + " whose click listener is attached; the platform reports as resumed: "
                + TestSupport.platformResumedActivity(), row);
        TestSupport.readOnMainThread(list, activity -> {
            row.performClick();
            return null;
        });

        // The screen opens the browser and closes itself. A screen that keeps running means the row
        // was never opened: without this the journey could only report that the browser never came
        // back, not that the row click never happened.
        TestSupport.waitUntil("the " + screen.getSimpleName() + " screen to close itself",
                () -> Boolean.TRUE.equals(TestSupport.readOnMainThread(list, Activity::isFinishing)),
                () -> "opening the row did not take the screen away; the platform reports as resumed: "
                        + TestSupport.platformResumedActivity());
    }

    /**
     * The clickable row of a list screen that shows {@code rowText}, or {@code null} when the list
     * holds no such row. The search starts at the list, so the same address in the background
     * browser's omnibox can never be mistaken for a row.
     */
    private static View clickableRowShowing(Activity screen, String rowText) {
        View list = findFirst(screen.getWindow().getDecorView(),
                view -> view instanceof RecyclerView);
        if (list == null) {
            return null;
        }
        View text = findFirst(list,
                view -> view instanceof TextView
                        && ((TextView) view).getText().toString().contains(rowText));
        if (text == null) {
            return null;
        }
        // The row is the nearest clickable ancestor of the text; the delete button next to it is a
        // sibling, so it can never be what this returns.
        View candidate = text;
        while (candidate != null && !candidate.isClickable()) {
            candidate = candidate.getParent() instanceof View ? (View) candidate.getParent() : null;
        }
        return candidate instanceof RecyclerView ? null : candidate;
    }

    /** Depth-first search for the first view the predicate accepts, or {@code null}. */
    private static View findFirst(View root, java.util.function.Predicate<View> predicate) {
        if (predicate.test(root)) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findFirst(group.getChildAt(i), predicate);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean canGoBack(ActivityScenario<MainActivity> scenario) {
        return Boolean.TRUE.equals(TestSupport.onResult(scenario,
                activity -> TestSupport.webViewOf(activity).canGoBack()));
    }

    /** What the local history actually holds, for the failure message of a timeout. */
    private static String historySummary() {
        HistoryRepository history = history();
        StringBuilder summary = new StringBuilder("history=").append(history.count()).append(" [");
        try {
            java.util.List<com.savage.soulbrowser.domain.HistoryEntry> recent = history.recent(5);
            for (int i = 0; i < recent.size(); i++) {
                summary.append(i == 0 ? "" : ", ").append(recent.get(i).getUrl());
            }
        } catch (RuntimeException unreadable) {
            summary.append("unreadable: ").append(unreadable.getClass().getSimpleName());
        }
        return summary.append(']').toString();
    }

    private static BookmarkRepository bookmarks() {
        return SavageBrowserApp.from(TestSupport.context()).bookmarkRepository();
    }

    private static HistoryRepository history() {
        return SavageBrowserApp.from(TestSupport.context()).historyRepository();
    }
}
