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
    private TextView emptyAll;
    private TextView hint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.attrColor(this, R.attr.cBg));
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 4));
        ImageButton back = Ui.iconButton(this, R.drawable.ic_back, R.string.action_back);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(spacer);

        emptyAll = new TextView(this);
        emptyAll.setText(R.string.empty_trash);
        emptyAll.setTextSize(15);
        emptyAll.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        emptyAll.setTextColor(getColor(R.color.bad));
        emptyAll.setGravity(Gravity.CENTER);
        emptyAll.setBackgroundResource(R.drawable.bg_pill);
        emptyAll.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        emptyAll.setClickable(true);
        emptyAll.setFocusable(true);
        emptyAll.setOnClickListener(v -> Ui.confirm(this, R.string.empty_trash_title, R.string.empty_trash_message, R.string.empty_trash, () -> {
            for (Recording r : Repo.trashed(this)) Repo.delete(this, r.id);
            reload();
        }));
        bar.addView(emptyAll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36)));
        root.addView(bar);

        root.addView(Ui.largeTitle(this, R.string.trash_title));

        hint = new TextView(this);
        hint.setText(R.string.trash_hint);
        hint.setTextSize(14);
        hint.setTextColor(Ui.attrColor(this, R.attr.cTextSecondary));
        hint.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), Ui.dp(this, 10));
        root.addView(hint);

        FrameLayout body = new FrameLayout(this);
        rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setClipToPadding(false);
        rv.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 24));
        adapter = new RecordingAdapter(this, true, new RecordingAdapter.Listener() {
            @Override public void onOpen(Recording r) { showMenu(r); }
            @Override public void onMenu(Recording r) { showMenu(r); }
        });
        rv.setAdapter(adapter);
        body.addView(rv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), Ui.dp(this, 64));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_trash);
        icon.setImageTintList(ColorStateList.valueOf(Ui.attrColor(this, R.attr.cTextHint)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
        TextView text = new TextView(this);
        text.setText(R.string.trash_empty);
        text.setTextColor(Ui.attrColor(this, R.attr.cTextSecondary));
        text.setTextSize(17);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 12);
        box.addView(text, tlp);
        empty = box;
        body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
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
        emptyAll.setVisibility(none ? View.INVISIBLE : View.VISIBLE);
        hint.setVisibility(none ? View.INVISIBLE : View.VISIBLE);
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
