package com.savage.soulbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.savage.soulbrowser.data.HistoryRepository;
import com.savage.soulbrowser.ui.HistoryActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * AC-017 and AC-018: an empty history screen explains itself, and a recorded visit is listed with
 * the number of visits.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryActivityTest {

    private static final String URL = "https://example.com/visited";

    @Before
    public void clearLocalState() {
        TestSupport.wipeLocalData();
    }

    @Test
    public void emptyStateIsShown() {
        try (ActivityScenario<HistoryActivity> scenario = launchHistory()) {
            TestSupport.on(scenario, activity -> {
                assertEquals("the empty explanation is visible",
                        View.VISIBLE, activity.findViewById(R.id.history_empty_state).getVisibility());
                assertEquals("no empty list is shown",
                        View.GONE, activity.findViewById(R.id.list_history).getVisibility());
                assertEquals(activity.getString(R.string.history_empty_title),
                        ((TextView) activity.findViewById(R.id.history_empty_title)).getText().toString());
                assertEquals(activity.getString(R.string.history_empty_message),
                        ((TextView) activity.findViewById(R.id.history_empty_message)).getText().toString());
            });
        }
    }

    @Test
    public void recordedVisitIsListed() {
        HistoryRepository repository = SavageBrowserApp.from(TestSupport.context())
                .historyRepository();
        assertNotNull(repository);
        repository.recordVisit(URL, "Visited page", System.currentTimeMillis());

        try (ActivityScenario<HistoryActivity> scenario = launchHistory()) {
            TestSupport.on(scenario, activity -> {
                RecyclerView list = activity.findViewById(R.id.list_history);
                assertEquals("the recorded visit is listed", 1, list.getAdapter().getItemCount());
                assertEquals("the empty explanation is hidden",
                        View.GONE, activity.findViewById(R.id.history_empty_state).getVisibility());
            });
        }
    }

    private static ActivityScenario<HistoryActivity> launchHistory() {
        return ActivityScenario.launch(new Intent(TestSupport.context(), HistoryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
}
