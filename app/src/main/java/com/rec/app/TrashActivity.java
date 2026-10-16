package com.rec.app;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;

public final class TrashActivity extends AppCompatActivity {

    private RecordingAdapter adapter;
    private RecyclerView rv;
    private View empty;
    private TextView hint;
    private View normalBar;
    private View selBar;
    private LinearLayout selActions;
    private TextView selTitle;
    private TextView selAllTag;
    private TextView btnSelect;
    private TextView btnDeleteAll;
    private boolean inSelection;
    private OnBackPressedCallback backCallback;

    private int c(int attr) { return Ui.attrColor(this, attr); }
    private int dp(int v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                exitSelection();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(c(R.attr.cBg));
        root.setFitsSystemWindows(true);

        normalBar = buildNormalBar();
        selBar = buildSelectionBar();
        selBar.setVisibility(View.GONE);
        root.addView(normalBar);
        root.addView(selBar);

        root.addView(Ui.largeTitle(this, R.string.trash_title));

        hint = new TextView(this);
        hint.setText(R.string.trash_hint);
        hint.setTextSize(14);
        hint.setTextColor(c(R.attr.cTextSecondary));
        hint.setPadding(dp(22), 0, dp(22), dp(10));
        root.addView(hint);

        FrameLayout body = new FrameLayout(this);
        rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setClipToPadding(false);
        rv.setPadding(dp(16), dp(2), dp(16), dp(24));
        adapter = new RecordingAdapter(this, true, new RecordingAdapter.Listener() {
            @Override public void onOpen(Recording r) { showMenu(r); }
            @Override public void onSelectionChanged(int count, int total) { onSelection(count, total); }
        });
        rv.setAdapter(adapter);
        body.addView(rv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(32), 0, dp(32), dp(64));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_trash);
        icon.setImageTintList(ColorStateList.valueOf(c(R.attr.cTextHint)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView text = new TextView(this);
        text.setText(R.string.trash_empty);
        text.setTextColor(c(R.attr.cTextSecondary));
        text.setTextSize(17);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(12);
        box.addView(text, tlp);
        empty = box;
        body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        selActions = buildSelectionActions();
        selActions.setVisibility(View.GONE);
        root.addView(selActions);
        setContentView(root);
    }

    private View buildNormalBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(8), dp(16), dp(4));
        ImageButton back = Ui.iconButton(this, R.drawable.ic_back, R.string.action_back);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(spacer);

        btnSelect = Ui.tag(this, getString(R.string.select));
        btnSelect.setOnClickListener(v -> {
            if (!Repo.trashed(this).isEmpty()) adapter.startSelection(null);
        });
        bar.addView(btnSelect, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        btnDeleteAll = new TextView(this);
        btnDeleteAll.setText(R.string.empty_trash);
        btnDeleteAll.setTextSize(15);
        btnDeleteAll.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btnDeleteAll.setTextColor(getColor(R.color.bad));
        btnDeleteAll.setGravity(Gravity.CENTER);
        btnDeleteAll.setBackgroundResource(R.drawable.bg_pill);
        btnDeleteAll.setPadding(dp(16), 0, dp(16), 0);
        btnDeleteAll.setClickable(true);
        btnDeleteAll.setFocusable(true);
        btnDeleteAll.setOnClickListener(v -> Ui.confirm(this, R.string.empty_trash_title, R.string.empty_trash_message, R.string.empty_trash, () -> {
            for (Recording r : Repo.trashed(this)) Repo.delete(this, r.id);
            reload();
        }));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        dlp.setMarginStart(dp(8));
        bar.addView(btnDeleteAll, dlp);
        return bar;
    }

    private View buildSelectionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(8), dp(16), dp(4));

        ImageButton close = Ui.iconButton(this, R.drawable.ic_close, R.string.action_close);
        close.setOnClickListener(v -> exitSelection());
        bar.addView(close);

        selTitle = new TextView(this);
        selTitle.setTextSize(18);
        selTitle.setSingleLine(true);
        selTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        selTitle.setTextColor(c(R.attr.cTextPrimary));
        selTitle.setPadding(dp(10), 0, dp(10), 0);
        bar.addView(selTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        selAllTag = Ui.tag(this, getString(R.string.select_all));
        selAllTag.setOnClickListener(v -> adapter.selectAll(!adapter.allSelected()));
        bar.addView(selAllTag, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private LinearLayout buildSelectionActions() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.bg_keypad_panel);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72));
        plp.setMargins(dp(12), dp(4), dp(12), dp(12));
        bar.setLayoutParams(plp);

        LinearLayout restore = Ui.action(this, R.drawable.ic_restore, R.string.restore, c(R.attr.cAccentText));
        restore.setOnClickListener(v -> {
            List<Recording> sel = adapter.selectedItems();
            if (sel.isEmpty()) return;
            for (Recording r : sel) Repo.restore(this, r);
            Toast.makeText(this, getString(R.string.restored_fmt, sel.size()), Toast.LENGTH_SHORT).show();
            exitSelection();
            reload();
        });
        bar.addView(restore, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout forever = Ui.action(this, R.drawable.ic_trash, R.string.delete_forever, getColor(R.color.bad));
        forever.setOnClickListener(v -> {
            final List<Recording> sel = adapter.selectedItems();
            if (sel.isEmpty()) return;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.delete_title)
                    .setMessage(getString(R.string.bulk_forever_message, sel.size()))
                    .setPositiveButton(R.string.delete_forever, (d, w) -> {
                        for (Recording r : sel) Repo.delete(this, r.id);
                        exitSelection();
                        reload();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
        bar.addView(forever, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return bar;
    }

    private void onSelection(int count, int total) {
        if (!inSelection) {
            inSelection = true;
            normalBar.setVisibility(View.GONE);
            selBar.setVisibility(View.VISIBLE);
            selActions.setVisibility(View.VISIBLE);
            backCallback.setEnabled(true);
        }
        selTitle.setText(getString(R.string.selected_fmt, count));
        selAllTag.setText(count == total && total > 0 ? R.string.deselect_all : R.string.select_all);
        selActions.setAlpha(count == 0 ? 0.4f : 1f);
    }

    private void exitSelection() {
        if (!inSelection) return;
        inSelection = false;
        adapter.stopSelection();
        normalBar.setVisibility(View.VISIBLE);
        selBar.setVisibility(View.GONE);
        selActions.setVisibility(View.GONE);
        backCallback.setEnabled(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Repo.purge(this);
        reload();
    }

    private void reload() {
        List<Recording> items = Repo.trashed(this);
        Collections.sort(items, (a, b) -> Long.compare(b.deletedAt, a.deletedAt));
        adapter.submit(items);
        boolean none = items.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        rv.setVisibility(none ? View.INVISIBLE : View.VISIBLE);
        btnDeleteAll.setVisibility(none ? View.INVISIBLE : View.VISIBLE);
        btnSelect.setVisibility(none ? View.INVISIBLE : View.VISIBLE);
        hint.setVisibility(none ? View.INVISIBLE : View.VISIBLE);
        if (none && inSelection) exitSelection();
    }

    private void showMenu(Recording r) {
        String[] items = {getString(R.string.restore), getString(R.string.delete_forever)};
        new AlertDialog.Builder(this)
                .setTitle(r.title)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        Repo.restore(this, r);
                        reload();
                    } else {
                        Ui.confirm(this, R.string.delete_title, R.string.delete_forever_message, R.string.delete_forever, () -> {
                            Repo.delete(this, r.id);
                            reload();
                        });
                    }
                })
                .show();
    }
}
