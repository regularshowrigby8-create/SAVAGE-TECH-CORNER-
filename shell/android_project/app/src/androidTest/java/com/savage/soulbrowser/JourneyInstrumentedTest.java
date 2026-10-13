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

import android.content.Intent;
import android.view.View;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.savage.soulbrowser.data.BookmarkRepository;
import com.savage.soulbrowser.data.HistoryRepository;
import com.savage.soulbrowser.ui.BookmarksActivity;
import com.savage.soulbrowser.ui.HistoryActivity;

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

    @Before
    public void clearLocalState() {
        TestSupport.wipeLocalData();
    }

    @After
    public void restoreRadios() {
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
            // real activity and its row is really clicked; the browser then comes back through the
            // same intent the list sends, which is what makes the address observable.
            try (ActivityScenario<BookmarksActivity> list = ActivityScenario.launch(
                    new Intent(TestSupport.context(), BookmarksActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                onView(withId(R.id.list_bookmarks)).check(matches(isDisplayed()));
                onView(withText(containsString("example.com"))).perform(click());
            }
            assertBrowserCameBackTo(PAGE_A);
        }
    }

    @Test
    public void j3StarTwiceKeepsOneRow() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.navigateForReal(scenario, PAGE_A);
            TestSupport.waitUntil("the page to load", () -> PAGE_A.equals(url(scenario)),
                    () -> TestSupport.describeBrowser(scenario));

            BookmarkRepository bookmarks = bookmarks();
            TestSupport.on(scenario,
                    activity -> activity.findViewById(R.id.btn_action_bookmark).performClick());
            TestSupport.waitUntil("the first tap to store one row", () -> bookmarks.count() == 1);

            // The second tap toggles the same bookmark off again; two rows for one page can never
            // exist because the store has a unique key per canonical URL.
            TestSupport.on(scenario,
                    activity -> activity.findViewById(R.id.btn_action_bookmark).performClick());
            TestSupport.waitUntil("the second tap to remove the bookmark", () -> bookmarks.count() == 0);
        }
    }

    @Test
    public void j4HistoryRecordsVisitAndReopensIt() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            // The launch itself visits the home page, so the store legitimately holds more than the
            // visit this journey makes: what has to be true is that this address was recorded. The
            // scenario stays open through the assertion below because the list sends the browser back
            // to the same task, and a closed scenario would destroy the activity that receives it.
            TestSupport.navigateForReal(scenario, PAGE_A);
            TestSupport.waitUntil("the visit to be written to the local store", () -> {
                HistoryRepository history = history();
                return history.recent(5).stream()
                        .anyMatch(entry -> PAGE_A.equals(entry.getUrl()));
            }, () -> TestSupport.describeBrowser(scenario) + " " + historySummary());

            try (ActivityScenario<HistoryActivity> list = ActivityScenario.launch(
                    new Intent(TestSupport.context(), HistoryActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                onView(withId(R.id.list_history)).check(matches(isDisplayed()));
                onView(withText(containsString("example.com"))).perform(click());
            }
            assertBrowserCameBackTo(PAGE_A);
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
     * Waits until the browser is (back) on {@code expected}.
     *
     * <p>The list activities send {@code ACTION_VIEW}-style intents at the browser, so the browser
     * comes back through {@code onNewIntent} and the address it was given is what must end up in the
     * address bar. The same task is what makes this observable: the list's {@code finish()} leaves a
     * destroyed activity behind if it started a new task of its own.
     */
    private static void assertBrowserCameBackTo(String expected) {
        TestSupport.waitUntil("the browser in front to show the opened address " + expected,
                () -> expected.equals(TestSupport.resumedBrowserAddress()),
                () -> "the address bar of the browser in front shows '"
                        + TestSupport.resumedBrowserAddress() + "'");
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
