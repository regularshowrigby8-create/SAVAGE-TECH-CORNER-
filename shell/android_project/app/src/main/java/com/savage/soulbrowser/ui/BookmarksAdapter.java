package com.savage.soulbrowser.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.savage.soulbrowser.R;
import com.savage.soulbrowser.domain.Bookmark;
import com.savage.soulbrowser.domain.DisplayText;

import java.util.ArrayList;
import java.util.List;

/** Rows of the bookmarks screen. */
public final class BookmarksAdapter extends RecyclerView.Adapter<BookmarksAdapter.BookmarkViewHolder> {

    public interface Listener {
        void onOpen(Bookmark bookmark);

        void onDelete(Bookmark bookmark);
    }

    private final Listener listener;
    private List<Bookmark> bookmarks = new ArrayList<>();

    public BookmarksAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Bookmark> newBookmarks) {
        this.bookmarks = newBookmarks == null ? new ArrayList<>() : new ArrayList<>(newBookmarks);
        notifyDataSetChanged();
    }

    public int size() {
        return bookmarks.size();
    }

    @NonNull
    @Override
    public BookmarkViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bookmark, parent, false);
        return new BookmarkViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BookmarkViewHolder holder, int position) {
        holder.bind(bookmarks.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return bookmarks.size();
    }

    static final class BookmarkViewHolder extends RecyclerView.ViewHolder {

        private final TextView title;
        private final TextView url;
        private final ImageButton delete;

        BookmarkViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.item_title);
            url = itemView.findViewById(R.id.item_url);
            delete = itemView.findViewById(R.id.item_delete);
        }

        void bind(Bookmark bookmark, Listener listener) {
            String displayTitle = DisplayText.titleFor(bookmark.getTitle(), bookmark.getUrl());
            title.setText(displayTitle);
            url.setText(DisplayText.shorten(bookmark.getUrl(), 64));
            itemView.setContentDescription(displayTitle + ", " + bookmark.getUrl());
            delete.setContentDescription(itemView.getContext()
                    .getString(R.string.bookmarks_delete, displayTitle));
            itemView.setOnClickListener(view -> listener.onOpen(bookmark));
            delete.setOnClickListener(view -> listener.onDelete(bookmark));
        }
    }
}
