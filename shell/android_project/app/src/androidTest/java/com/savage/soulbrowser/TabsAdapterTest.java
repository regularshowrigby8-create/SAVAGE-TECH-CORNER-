package com.savage.soulbrowser;

import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.savage.soulbrowser.domain.DisplayText;
import com.savage.soulbrowser.domain.TabList;
import com.savage.soulbrowser.ui.TabsAdapter;

import java.util.Arrays;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import com.savage.soulbrowser.ui.MainActivity;

/** AC-011: a tab row shows the page title and the address, and marks the active tab. */
@RunWith(AndroidJUnit4.class)
public class TabsAdapterTest {

    private static final String URL = "https://example.com/some/rather/long/path/to/a/page";

    @Before
    public void clearLocalState() {
        TestSupport.wipeLocalData();
    }

    @Test
    public void bindsTitleAndUrl() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.on(scenario, activity -> {
                TabsAdapter adapter = new TabsAdapter(new TabsAdapter.Listener() {
                    @Override
                    public void onTabSelected(String tabId) {
                        // selection is not part of this criterion
                    }

                    @Override
                    public void onTabClosed(String tabId) {
                        // closing is not part of this criterion
                    }
                });
                adapter.submit(Arrays.asList(
                        new TabList.Tab("tab-1", URL, "Example title")), 0);
                Assert.assertEquals(1, adapter.getItemCount());

                // A RecyclerView bound to the adapter creates and binds the real row view.
                RecyclerView recyclerView = new RecyclerView(activity);
                recyclerView.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(activity));
                recyclerView.setAdapter(adapter);
                recyclerView.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY));
                recyclerView.layout(0, 0, 1080, 1920);
                android.view.View row = recyclerView.getChildAt(0);
                Assert.assertNotNull("the adapter inflated a row", row);

                TextView title = row.findViewById(R.id.item_title);
                TextView url = row.findViewById(R.id.item_url);
                Assert.assertEquals("Example title", title.getText().toString());
                Assert.assertEquals("a long address is shortened for the row",
                        DisplayText.shorten(URL, 64), url.getText().toString());
                Assert.assertTrue("the row marks the open tab", row.isSelected());
                Assert.assertNotNull("the row describes itself for a screen reader",
                        row.getContentDescription());
                Assert.assertNotNull("the close button names the tab it closes",
                        row.findViewById(R.id.item_close).getContentDescription());
            });
        }
    }
}
