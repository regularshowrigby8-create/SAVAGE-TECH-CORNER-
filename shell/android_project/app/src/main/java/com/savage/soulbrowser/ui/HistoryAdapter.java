package com.savage.soulbrowser.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.savage.soulbrowser.R;
import com.savage.soulbrowser.domain.DisplayText;
import com.savage.soulbrowser.domain.HistoryEntry;

import java.util.ArrayList;
import java.util.List;

/** Rows of the history screen. */
public final class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder> {

    public interface Listener {
        void onOpen(HistoryEntry entry);

        void onDelete(HistoryEntry entry);
    }

    private final Listener listener;
    private List<HistoryEntry> entries = new ArrayList<>();

    public HistoryAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<HistoryEntry> newEntries) {
        this.entries = newEntries == null ? new ArrayList<>() : new ArrayList<>(newEntries);
        notifyDataSetChanged();
    }

    public int size() {
        return entries.size();
    }

    @NonNull
    @Override
    public HistoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        return new HistoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull HistoryViewHolder holder, int position) {
        holder.bind(entries.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static final class HistoryViewHolder extends RecyclerView.ViewHolder {

        private final TextView title;
        private final TextView url;
        private final TextView visits;
        private final ImageButton delete;

        HistoryViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.item_title);
            url = itemView.findViewById(R.id.item_url);
            visits = itemView.findViewById(R.id.item_visits);
            delete = itemView.findViewById(R.id.item_delete);
        }

        void bind(HistoryEntry entry, Listener listener) {
            String displayTitle = DisplayText.titleFor(entry.getTitle(), entry.getUrl());
            title.setText(displayTitle);
            url.setText(DisplayText.shorten(entry.getUrl(), 64));
            visits.setText(itemView.getContext()
                    .getString(R.string.history_visit_count, entry.getVisitCount()));
            itemView.setContentDescription(displayTitle + ", " + entry.getUrl());
            delete.setContentDescription(itemView.getContext()
                    .getString(R.string.history_delete, displayTitle));
            itemView.setOnClickListener(view -> listener.onOpen(entry));
            delete.setOnClickListener(view -> listener.onDelete(entry));
        }
    }
}
