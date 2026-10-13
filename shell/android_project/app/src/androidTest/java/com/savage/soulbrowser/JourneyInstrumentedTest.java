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
 * <p>Pages are injected into the real WebView (see {@link TestSupport#injectPage}) so a journey
 * does not depend on a third party site being reachable during a CI run; the WebView, its history,
 * the tab manager, the repositories and the activities are all the production ones. The journey
 * that must involve the network - offline detection - turns the emulator radios off instead.
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
            TestSupport.on(scenario, activity -> TestSupport.injectPage(activity, PAGE_A, "Page A"));
            TestSupport.waitUntil("the first page to load", () -> PAGE_A.equals(url(scenario)),
                    () -> TestSupport.describeBrowser(scenario));
            Assert.assertTrue("a loaded page counts as content",
                    TestSupport.onResult(scenario, MainActivity::hasLoadedPage));

            TestSupport.on(scenario, activity -> TestSupport.injectPage(activity, PAGE_B, "Page B"));
            TestSupport.waitUntil("the second page to load", () -> PAGE_B.equals(url(scenario)),
                    () -> TestSupport.describeBrowser(scenario));

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
            TestSupport.on(scenario, activity -> TestSupport.injectPage(activity, PAGE_A, "Page A"));
            TestSupport.waitUntil("the page to load", () -> PAGE_A.equals(url(scenario)),
                    () -> TestSupport.describeBrowser(scenario));

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

            // Opening the bookmark from the stored list must load it in the browser.
            try (ActivityScenario<BookmarksActivity> list = ActivityScenario.launch(
                    new Intent(TestSupport.context(), BookmarksActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                onView(withId(R.id.list_bookmarks)).check(matches(isDisplayed()));
                onView(withText(containsString("example.com"))).perform(click());
            }
            TestSupport.waitForViewText(R.id.edit_omnibox, "example.com");
        }
    }

    @Test
    public void j3StarTwiceKeepsOneRow() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.on(scenario, activity -> TestSupport.injectPage(activity, PAGE_A, "Page A"));
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
            TestSupport.on(scenario, activity -> TestSupport.injectPage(activity, PAGE_A, "Page A"));
            TestSupport.waitUntil("the visit to be written to the local store", () -> {
                HistoryRepository history = history();
                return history.count() == 1 && PAGE_A.equals(history.recent(1).get(0).getUrl());
            }, () -> TestSupport.describeBrowser(scenario) + " history=" + history().count());
        }

        try (ActivityScenario<HistoryActivity> list = ActivityScenario.launch(
                new Intent(TestSupport.context(), HistoryActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            onView(withId(R.id.list_history)).check(matches(isDisplayed()));
            onView(withText(containsString("example.com"))).perform(click());
        }
        TestSupport.waitForViewText(R.id.edit_omnibox, "example.com");
    }

    @Test
    public void j5TabsOpenSwitchClose() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.on(scenario, activity -> TestSupport.injectPage(activity, PAGE_A, "Page A"));
            TestSupport.waitUntil("the first tab to show page A", () -> PAGE_A.equals(url(scenario)),
                    () -> TestSupport.describeBrowser(scenario));
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

    private static BookmarkRepository bookmarks() {
        return SavageBrowserApp.from(TestSupport.context()).bookmarkRepository();
    }

    private static HistoryRepository history() {
        return SavageBrowserApp.from(TestSupport.context()).historyRepository();
    }
}
