package com.savage.soulbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.savage.soulbrowser.data.BookmarkRepository;
import com.savage.soulbrowser.ui.BookmarksActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * AC-016 and AC-018: an empty bookmarks screen explains itself instead of showing a blank list,
 * and a stored bookmark is listed with its title and address.
 */
@RunWith(AndroidJUnit4.class)
public class BookmarksActivityTest {

    private static final String URL = "https://example.com/bookmarked";

    @Before
    public void clearLocalState() {
        TestSupport.wipeLocalData();
    }

    @Test
    public void emptyStateIsShown() {
        try (ActivityScenario<BookmarksActivity> scenario = launchBookmarks()) {
            TestSupport.on(scenario, activity -> {
                assertEquals("the empty explanation is visible",
                        View.VISIBLE, activity.findViewById(R.id.bookmarks_empty_state).getVisibility());
                assertEquals("no empty list is shown",
                        View.GONE, activity.findViewById(R.id.list_bookmarks).getVisibility());
                assertEquals(activity.getString(R.string.bookmarks_empty_title),
                        ((TextView) activity.findViewById(R.id.bookmarks_empty_title))
                                .getText().toString());
                assertEquals(activity.getString(R.string.bookmarks_empty_message),
                        ((TextView) activity.findViewById(R.id.bookmarks_empty_message))
                                .getText().toString());
            });
        }
    }

    @Test
    public void storedBookmarkIsListed() {
        BookmarkRepository repository = SavageBrowserApp.from(TestSupport.context())
                .bookmarkRepository();
        assertNotNull(repository);
        repository.add(URL, "Example", System.currentTimeMillis());

        try (ActivityScenario<BookmarksActivity> scenario = launchBookmarks()) {
            TestSupport.on(scenario, activity -> {
                RecyclerView list = activity.findViewById(R.id.list_bookmarks);
                assertEquals("the stored bookmark is listed", 1, list.getAdapter().getItemCount());
                assertEquals("the empty explanation is hidden",
                        View.GONE, activity.findViewById(R.id.bookmarks_empty_state).getVisibility());
            });
        }
    }

    private static ActivityScenario<BookmarksActivity> launchBookmarks() {
        return ActivityScenario.launch(new Intent(TestSupport.context(), BookmarksActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
}
