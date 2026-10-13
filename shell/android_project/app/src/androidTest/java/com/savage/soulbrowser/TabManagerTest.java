package com.savage.soulbrowser;

import android.view.View;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.savage.soulbrowser.browser.TabManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import com.savage.soulbrowser.ui.MainActivity;

/**
 * AC-008 and AC-012: the tab manager really creates, switches and destroys WebViews, and a browser
 * session can never end up without a tab.
 *
 * <p>This drives the production {@link TabManager} against a real Activity, a real container and
 * real WebViews - the same objects {@code MainActivity} uses.
 */
@RunWith(AndroidJUnit4.class)
public class TabManagerTest {

    private static final String PAGE = "https://example.com/tab";

    @Before
    public void clearLocalState() {
        TestSupport.wipeLocalData();
    }

    @After
    public void restoreRadios() {
        TestSupport.setNetworkEnabled(true);
    }

    @Test
    public void switchAndCloseKeepsAtLeastOneTab() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.on(scenario, activity -> {
                // A container the test owns: the activity's own container already holds the home
                // tab's WebView, so counting children there would measure the activity rather than
                // the manager under test.
                FrameLayout container = ownContainer(activity);
                RecordingListener listener = new RecordingListener();
                TabManager manager = new TabManager(activity, container, MainActivity.HOME_URL,
                        () -> new WebView(activity), listener);

                Assert.assertEquals("a manager starts with the home tab", 1, manager.size());
                String first = manager.activeTab().getId();

                manager.openTab(PAGE);
                String second = manager.activeTab().getId();
                Assert.assertEquals(2, manager.size());
                Assert.assertEquals("the new tab is active", PAGE, manager.activeTab().getUrl());
                Assert.assertEquals("one WebView per tab", 2, container.getChildCount());
                Assert.assertEquals("only the active WebView is visible", 1, visibleChildren(container));
                Assert.assertEquals("the host is told how many tabs are open",
                        Arrays.asList(2), listener.counts);

                manager.activate(first);
                Assert.assertEquals("switching back activates the first tab",
                        first, manager.activeTab().getId());
                Assert.assertEquals("the first tab keeps its own URL",
                        MainActivity.HOME_URL, manager.activeTab().getUrl());
                Assert.assertEquals("switching shows exactly one WebView", 1, visibleChildren(container));

                manager.updateActiveTab(PAGE, "Example title");
                Assert.assertEquals("the model follows the page the user is on",
                        PAGE, manager.activeTab().getUrl());
                Assert.assertEquals("the title is stored on the active tab",
                        "Example title", manager.activeTab().getTitle());
                Assert.assertEquals("the host is told which tab changed",
                        Arrays.asList(PAGE), listener.updated);

                boolean activeChanged = manager.closeTab(second);
                Assert.assertFalse("closing a background tab does not change the active tab",
                        activeChanged);
                Assert.assertEquals("closing one of two tabs leaves one", 1, manager.size());
                Assert.assertEquals("its WebView is destroyed", 1, container.getChildCount());
                Assert.assertFalse("the closed tab is gone from the model",
                        manager.tabList().contains(second));
                Assert.assertEquals("the surviving tab stays active", first, manager.activeTab().getId());
            });
        }
    }

    @Test
    public void closingLastTabOpensHomeTab() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.on(scenario, activity -> {
                FrameLayout container = ownContainer(activity);
                TabManager manager = new TabManager(activity, container, MainActivity.HOME_URL,
                        () -> new WebView(activity), new RecordingListener());
                String only = manager.activeTab().getId();

                manager.closeTab(only);

                Assert.assertEquals("a browser always has a tab", 1, manager.size());
                Assert.assertNotNull("the replacement tab exists", manager.activeTab());
                Assert.assertNotEquals("the closed tab is replaced", only, manager.activeTab().getId());
                Assert.assertEquals(MainActivity.HOME_URL, manager.activeTab().getUrl());
                Assert.assertEquals("a WebView is attached for the replacement tab",
                        1, container.getChildCount());
                Assert.assertNotNull("the replacement tab has a live WebView",
                        manager.activeWebView());
            });
        }
    }

    /**
     * Adds a container to the running activity and returns it, so a {@link TabManager} built by a
     * test has a container of its own and its WebView counts are not polluted by the activity's.
     */
    private static FrameLayout ownContainer(MainActivity activity) {
        FrameLayout container = new FrameLayout(activity);
        activity.addContentView(container, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return container;
    }

    private static int visibleChildren(FrameLayout container) {
        int visible = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            if (container.getChildAt(i).getVisibility() == View.VISIBLE) {
                visible++;
            }
        }
        return visible;
    }

    private static final class RecordingListener implements TabManager.Listener {
        final List<Integer> counts = new ArrayList<>();
        final List<String> updated = new ArrayList<>();

        @Override
        public void onTabSetChanged(int tabCount) {
            counts.add(tabCount);
        }

        @Override
        public void onActiveTabChanged(String url, String title) {
            // The active tab is read from the manager; nothing to record for the assertions.
        }

        @Override
        public void onTabUpdated(String id, String url, String title) {
            updated.add(url);
        }
    }
}
