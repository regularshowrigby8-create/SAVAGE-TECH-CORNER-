package com.savage.soulbrowser.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.savage.soulbrowser.R;
import com.savage.soulbrowser.SavageBrowserApp;
import com.savage.soulbrowser.data.HistoryRepository;
import com.savage.soulbrowser.domain.HistoryEntry;

import java.util.List;

/** Locally stored visit history: list, empty state, open, delete one and delete all. */
public final class HistoryActivity extends AppCompatActivity implements HistoryAdapter.Listener {

    private HistoryRepository repository;
    private HistoryAdapter adapter;
    private RecyclerView list;
    private LinearLayout emptyState;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        setTitle(R.string.title_history);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        repository = SavageBrowserApp.from(this).historyRepository();
        list = findViewById(R.id.list_history);
        emptyState = findViewById(R.id.history_empty_state);

        adapter = new HistoryAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.history_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_clear_history) {
            confirmClearAll();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /** Reloads the list and the empty state from the local store. */
    void refresh() {
        List<HistoryEntry> entries = repository.recent(200);
        adapter.submit(entries);
        boolean empty = entries.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.history_clear_confirm_title)
                .setMessage(R.string.history_clear_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.history_clear_all, (dialog, which) -> {
                    repository.clear();
                    Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .show();
    }

    @Override
    public void onOpen(HistoryEntry entry) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_OPEN_URL, entry.getUrl());
        startActivity(intent);
        finish();
    }

    @Override
    public void onDelete(HistoryEntry entry) {
        repository.delete(entry.getId());
        refresh();
    }

    /** Exposed for instrumentation tests. */
    int visibleItemCount() {
        return adapter.getItemCount();
    }
}
