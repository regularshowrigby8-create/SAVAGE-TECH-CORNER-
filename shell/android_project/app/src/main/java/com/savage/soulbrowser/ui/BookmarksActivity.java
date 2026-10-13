package com.savage.soulbrowser.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.savage.soulbrowser.R;
import com.savage.soulbrowser.SavageBrowserApp;
import com.savage.soulbrowser.data.BookmarkRepository;
import com.savage.soulbrowser.domain.Bookmark;

import java.util.List;

/** Locally stored bookmarks: list, empty state, open and delete. */
public final class BookmarksActivity extends AppCompatActivity implements BookmarksAdapter.Listener {

    private BookmarkRepository repository;
    private BookmarksAdapter adapter;
    private RecyclerView list;
    private LinearLayout emptyState;
    private TextView syncPending;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bookmarks);
        setTitle(R.string.title_bookmarks);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        repository = SavageBrowserApp.from(this).bookmarkRepository();
        list = findViewById(R.id.list_bookmarks);
        emptyState = findViewById(R.id.bookmarks_empty_state);
        syncPending = findViewById(R.id.bookmarks_sync_pending);

        adapter = new BookmarksAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /** Reloads the list and the empty state from the local store. */
    void refresh() {
        List<Bookmark> bookmarks = repository.all();
        adapter.submit(bookmarks);
        boolean empty = bookmarks.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);

        int pending = repository.syncPendingCount();
        syncPending.setVisibility(pending > 0 ? View.VISIBLE : View.GONE);
        if (pending > 0) {
            syncPending.setText(getString(R.string.bookmarks_pending_sync, pending));
        }
    }

    @Override
    public void onOpen(Bookmark bookmark) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_OPEN_URL, bookmark.getUrl());
        startActivity(intent);
        finish();
    }

    @Override
    public void onDelete(Bookmark bookmark) {
        repository.delete(bookmark.getId());
        Toast.makeText(this, R.string.bookmarks_removed, Toast.LENGTH_SHORT).show();
        refresh();
    }

    /** Exposed for instrumentation tests. */
    int visibleItemCount() {
        return adapter.getItemCount();
    }
}
