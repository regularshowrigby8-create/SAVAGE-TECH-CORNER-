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
import com.savage.soulbrowser.domain.TabList;

import java.util.ArrayList;
import java.util.List;

/** Rows of the tab switcher: title, URL, active marker and a close button. */
public final class TabsAdapter extends RecyclerView.Adapter<TabsAdapter.TabViewHolder> {

    public interface Listener {
        void onTabSelected(String tabId);

        void onTabClosed(String tabId);
    }

    private final Listener listener;
    private List<TabList.Tab> tabs = new ArrayList<>();
    private int activeIndex = -1;

    public TabsAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<TabList.Tab> newTabs, int newActiveIndex) {
        this.tabs = newTabs == null ? new ArrayList<>() : new ArrayList<>(newTabs);
        this.activeIndex = newActiveIndex;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TabViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_tab, parent, false);
        return new TabViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TabViewHolder holder, int position) {
        TabList.Tab tab = tabs.get(position);
        holder.bind(tab, position == activeIndex, listener);
    }

    @Override
    public int getItemCount() {
        return tabs.size();
    }

    static final class TabViewHolder extends RecyclerView.ViewHolder {

        private final TextView title;
        private final TextView url;
        private final ImageButton close;

        TabViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.item_title);
            url = itemView.findViewById(R.id.item_url);
            close = itemView.findViewById(R.id.item_close);
        }

        void bind(TabList.Tab tab, boolean active, Listener listener) {
            String displayTitle = DisplayText.titleFor(tab.getTitle(), tab.getUrl());
            title.setText(displayTitle);
            url.setText(DisplayText.shorten(tab.getUrl(), 64));
            itemView.setSelected(active);
            itemView.setContentDescription(itemView.getContext().getString(
                    active ? R.string.tabs_active : R.string.tabs_title) + ": " + displayTitle);
            close.setContentDescription(itemView.getContext().getString(R.string.tabs_close, displayTitle));
            itemView.setOnClickListener(view -> listener.onTabSelected(tab.getId()));
            close.setOnClickListener(view -> listener.onTabClosed(tab.getId()));
        }
    }
}
