package com.savage.soulbrowser.ui;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.savage.soulbrowser.R;
import com.savage.soulbrowser.domain.TabList;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tab switcher: shows every open tab with the active one marked, allows switching, closing and
 * opening a new tab. The dialog re-reads the tab list through the supplied getter, so it always
 * reflects the live model instead of a snapshot taken when the dialog was built.
 */
public final class TabSwitcherDialog {

    private final AlertDialog dialog;
    private final TabsAdapter adapter;
    private final Supplier<TabList> tabSupplier;

    public TabSwitcherDialog(Context context,
                             Supplier<TabList> tabSupplier,
                             Consumer<String> onSelect,
                             Consumer<String> onClose,
                             Runnable onNewTab) {
        this.tabSupplier = tabSupplier;
        this.adapter = new TabsAdapter(new TabsAdapter.Listener() {
            @Override
            public void onTabSelected(String tabId) {
                onSelect.accept(tabId);
                dismissDialog();
            }

            @Override
            public void onTabClosed(String tabId) {
                onClose.accept(tabId);
                refresh();
            }
        });

        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        list.setContentDescription(context.getString(R.string.tabs_title));

        Button newTab = new Button(context);
        newTab.setText(R.string.tabs_new);
        newTab.setContentDescription(context.getString(R.string.cd_new_tab));
        newTab.setOnClickListener(view -> {
            onNewTab.run();
            dismissDialog();
        });

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(list);
        container.addView(newTab);

        dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.tabs_title)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        refresh();
    }

    public void show() {
        refresh();
        dialog.show();
    }

    public AlertDialog dialog() {
        return dialog;
    }

    /**
     * Dismisses the dialog. Called through a method (not the field) because the listeners are
     * created inside the constructor, while {@link #dialog} is still a blank final.
     */
    private void dismissDialog() {
        dialog.dismiss();
    }

    private void refresh() {
        TabList tabList = tabSupplier.get();
        if (tabList == null) {
            return;
        }
        List<TabList.Tab> tabs = tabList.tabs();
        adapter.submit(tabs, tabList.activeIndex());
    }
}
