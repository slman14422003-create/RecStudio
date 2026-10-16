package com.rec.app;

import android.Manifest;
import android.animation.AnimatorInflater;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_STARRED = 1;
    private static final int FILTER_MARKED = 2;
    private static final int FILTER_LABEL_BASE = 3;

    private Store store;
    private List<Recording> all = new ArrayList<>();
    private int filter = FILTER_ALL;
    private String query = "";

    private RecordingAdapter adapter;
    private RecyclerView rv;
    private View emptyView;
    private TextView emptyText;
    private TextView summary;
    private TextView banner;
    private TextView tagQuality;
    private TextView tagFree;
    private EditText search;
    private HorizontalScrollView chipScroll;
    private TextView[] chips;
    private TextView[] segItems;
    private View recInner;
    private boolean lastActive;
    private View normalBar;
    private View selBar;
    private View recPanel;
    private LinearLayout selActions;
    private TextView selTitle;
    private TextView selAllTag;
    private ImageButton selMore;
    private boolean inSelection;
    private OnBackPressedCallback backCallback;

    private ActivityResultLauncher<String[]> permLauncher;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshRecState();
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        store = new Store(this);
        Repo.purge(this);

        permLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            if (hasAudioPermission()) {
                openRecorder(true);
            } else {
                showPermissionDenied();
            }
        });

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                exitSelection();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        refreshLabels();
        lastActive = !RecorderService.isActive();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private int c(int attr) { return Ui.attrColor(this, attr); }
    private int dp(int v) { return Ui.dp(this, v); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(c(R.attr.cBg));
        root.setFitsSystemWindows(true);

        normalBar = buildTopBar();
        selBar = buildSelectionBar();
        selBar.setVisibility(View.GONE);
        root.addView(normalBar);
        root.addView(selBar);
        root.addView(Ui.largeTitle(this, R.string.recordings_title));
        root.addView(buildFilters());

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(R.string.search_hint);
        search.setTextSize(16);
        search.setTextColor(c(R.attr.cTextPrimary));
        search.setHintTextColor(c(R.attr.cTextHint));
        search.setBackground(Ui.round(c(R.attr.cField), dp(14), 0, 0));
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.getDefault());
                applyFilter();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        slp.setMargins(dp(16), dp(10), dp(16), 0);
        root.addView(search, slp);

        summary = new TextView(this);
        summary.setTextSize(13);
        summary.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        summary.setTextColor(c(R.attr.cTextSecondary));
        summary.setPadding(dp(22), dp(14), dp(22), dp(4));
        root.addView(summary);

        banner = new TextView(this);
        banner.setTextSize(15);
        banner.setGravity(Gravity.CENTER);
        banner.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        banner.setTextColor(getColor(R.color.key_danger_text));
        banner.setBackground(Ui.round(getColor(R.color.key_danger_soft), dp(14), 0, 0));
        banner.setClickable(true);
        banner.setFocusable(true);
        banner.setVisibility(View.GONE);
        banner.setOnClickListener(v -> openRecorder(false));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        blp.setMargins(dp(16), dp(6), dp(16), dp(4));
        root.addView(banner, blp);

        FrameLayout body = new FrameLayout(this);
        rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setClipToPadding(false);
        rv.setPadding(dp(16), dp(2), dp(16), dp(16));
        adapter = new RecordingAdapter(this, false, new RecordingAdapter.Listener() {
            @Override public void onOpen(Recording r) { openPlayer(r.id); }
            @Override public void onSelectionChanged(int count, int total) { onSelection(count, total); }
        });
        rv.setAdapter(adapter);
        attachSwipe();
        body.addView(rv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout emptyBox = new LinearLayout(this);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        emptyBox.setGravity(Gravity.CENTER);
        emptyBox.setPadding(dp(32), 0, dp(32), dp(32));
        ImageView emptyIcon = new ImageView(this);
        emptyIcon.setImageResource(R.drawable.ic_mic);
        emptyIcon.setImageTintList(android.content.res.ColorStateList.valueOf(c(R.attr.cTextHint)));
        emptyIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        emptyBox.addView(emptyIcon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        emptyText = new TextView(this);
        emptyText.setTextColor(c(R.attr.cTextSecondary));
        emptyText.setTextSize(17);
        emptyText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(12);
        emptyBox.addView(emptyText, elp);
        emptyView = emptyBox;
        body.addView(emptyView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        recPanel = buildRecordPanel();
        root.addView(recPanel);
        selActions = buildSelectionActions();
        selActions.setVisibility(View.GONE);
        root.addView(selActions);
        setContentView(root);
        refreshChips();
        refreshSegment();
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(8), dp(16), dp(4));

        ImageButton btnSearch = Ui.iconButton(this, R.drawable.ic_search, R.string.action_search);
        btnSearch.setOnClickListener(v -> toggleSearch());
        bar.addView(btnSearch);

        ImageButton btnTrash = Ui.iconButton(this, R.drawable.ic_trash, R.string.trash_title);
        btnTrash.setOnClickListener(v -> startActivity(new Intent(this, TrashActivity.class)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(40), dp(40));
        tlp.setMarginStart(dp(8));
        bar.addView(btnTrash, tlp);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(spacer);

        ImageButton btnSort = Ui.iconButton(this, R.drawable.ic_sort, R.string.sort_title);
        btnSort.setOnClickListener(v -> chooseSort());
        bar.addView(btnSort);

        ImageButton btnSettings = Ui.iconButton(this, R.drawable.ic_settings, R.string.action_settings);
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(40), dp(40));
        slp.setMarginStart(dp(8));
        bar.addView(btnSettings, slp);
        return bar;
    }

    private View buildFilters() {
        chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        chipScroll.setFillViewport(true);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        sLp.topMargin = dp(10);
        chipScroll.setLayoutParams(sLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), 0, dp(13), 0);

        String[] names = Labels.names(this);
        String[] labels = new String[FILTER_LABEL_BASE + names.length];
        labels[FILTER_ALL] = getString(R.string.filter_all);
        labels[FILTER_STARRED] = getString(R.string.filter_starred);
        labels[FILTER_MARKED] = getString(R.string.filter_flagged);
        System.arraycopy(names, 0, labels, FILTER_LABEL_BASE, names.length);

        chips = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int f = i;
            TextView chip = Ui.chip(this, labels[i]);
            chip.setOnClickListener(v -> {
                filter = f;
                refreshChips();
                applyFilter();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            clp.setMargins(dp(3), 0, dp(3), 0);
            row.addView(chip, clp);
            chips[i] = chip;
        }
        chipScroll.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return chipScroll;
    }

    private View buildRecordPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_keypad_panel);
        panel.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(dp(12), dp(4), dp(12), dp(12));
        panel.setLayoutParams(plp);

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(Ui.round(getColor(R.color.field), dp(12), 0, 0));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        String[] modes = getResources().getStringArray(R.array.mode_options);
        segItems = new TextView[modes.length];
        for (int i = 0; i < modes.length; i++) {
            final int m = i;
            TextView t = new TextView(this);
            t.setText(modes[i]);
            t.setTextSize(13);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setClickable(true);
            t.setFocusable(true);
            t.setOnClickListener(v -> selectMode(m));
            seg.addView(t, new LinearLayout.LayoutParams(0, dp(34), 1f));
            segItems[i] = t;
        }
        panel.addView(seg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(14);

        FrameLayout left = new FrameLayout(this);
        tagQuality = Ui.tag(this, "");
        tagQuality.setOnClickListener(v -> chooseQuality());
        left.addView(tagQuality, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout button = new FrameLayout(this);
        button.setBackgroundResource(R.drawable.bg_rec_ring);
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(getString(R.string.action_record));
        button.setStateListAnimator(AnimatorInflater.loadStateListAnimator(this, R.animator.press_scale));
        recInner = new View(this);
        recInner.setBackgroundResource(R.drawable.bg_rec_dot);
        button.addView(recInner, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        button.setOnClickListener(v -> {
            Ui.haptic(v, store);
            onRecordTapped();
        });
        row.addView(button, new LinearLayout.LayoutParams(dp(84), dp(84)));

        FrameLayout right = new FrameLayout(this);
        tagFree = Ui.tag(this, "");
        tagFree.setClickable(false);
        tagFree.setFocusable(false);
        right.addView(tagFree, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        panel.addView(row, rlp);
        return panel;
    }

    private void attachSwipe() {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Drawable trash = ContextCompat.getDrawable(this, R.drawable.ic_trash);
        final Drawable star = ContextCompat.getDrawable(this, R.drawable.ic_star_fill);
        final float radius = dp(18);
        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder v, @NonNull RecyclerView.ViewHolder t) {
                return false;
            }

            @Override
            public int getSwipeDirs(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh) {
                return adapter.isSelecting() ? 0 : super.getSwipeDirs(r, vh);
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                final int pos = vh.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                final Recording r = adapter.get(pos);
                if (direction == ItemTouchHelper.RIGHT) {
                    r.favorite = !r.favorite;
                    Repo.save(MainActivity.this, r);
                    applyFilter();
                } else {
                    Repo.trash(MainActivity.this, r);
                    Toast.makeText(MainActivity.this, R.string.moved_trash, Toast.LENGTH_SHORT).show();
                    reload();
                }
            }

            @Override
            public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh,
                                    float dX, float dY, int state, boolean active) {
                View v = vh.itemView;
                if (dX != 0) {
                    boolean toRight = dX > 0;
                    paint.setColor(getColor(toRight ? R.color.warn : R.color.bad));
                    float left = toRight ? v.getLeft() : v.getRight() + dX;
                    float right = toRight ? v.getLeft() + dX : v.getRight();
                    canvas.drawRoundRect(left, v.getTop(), right, v.getBottom(), radius, radius, paint);
                    Drawable icon = toRight ? star : trash;
                    if (icon != null) {
                        int size = dp(24);
                        int cy = (v.getTop() + v.getBottom()) / 2;
                        int x = toRight ? v.getLeft() + dp(22) : v.getRight() - dp(22) - size;
                        if (Math.abs(dX) > dp(22) + size) {
                            icon.setBounds(x, cy - size / 2, x + size, cy + size / 2);
                            icon.draw(canvas);
                        }
                    }
                }
                super.onChildDraw(canvas, r, vh, dX, dY, state, active);
            }
        });
        helper.attachToRecyclerView(rv);
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

        selMore = Ui.iconButton(this, R.drawable.ic_more, R.string.action_more);
        selMore.setOnClickListener(v -> {
            List<Recording> sel = adapter.selectedItems();
            if (sel.size() != 1) return;
            Recording r = sel.get(0);
            exitSelection();
            showMenu(r);
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(40), dp(40));
        mlp.setMarginStart(dp(6));
        bar.addView(selMore, mlp);
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

        LinearLayout star = Ui.action(this, R.drawable.ic_star, R.string.action_star_short, c(R.attr.cTextPrimary));
        star.setOnClickListener(v -> bulkStar());
        bar.addView(star, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout label = Ui.action(this, R.drawable.ic_label, R.string.label_title, c(R.attr.cTextPrimary));
        label.setOnClickListener(v -> bulkLabel());
        bar.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout share = Ui.action(this, R.drawable.ic_share, R.string.action_share, c(R.attr.cTextPrimary));
        share.setOnClickListener(v -> {
            List<Recording> sel = adapter.selectedItems();
            if (!sel.isEmpty()) Ui.shareMany(this, sel);
        });
        bar.addView(share, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout delete = Ui.action(this, R.drawable.ic_trash, R.string.delete, getColor(R.color.bad));
        delete.setOnClickListener(v -> bulkDelete());
        bar.addView(delete, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return bar;
    }

    private void onSelection(int count, int total) {
        if (!inSelection) enterSelectionUi();
        selTitle.setText(getString(R.string.selected_fmt, count));
        selAllTag.setText(count == total && total > 0 ? R.string.deselect_all : R.string.select_all);
        selMore.setVisibility(count == 1 ? View.VISIBLE : View.INVISIBLE);
        selActions.setAlpha(count == 0 ? 0.4f : 1f);
    }

    private void enterSelectionUi() {
        inSelection = true;
        normalBar.setVisibility(View.GONE);
        selBar.setVisibility(View.VISIBLE);
        recPanel.setVisibility(View.GONE);
        selActions.setVisibility(View.VISIBLE);
        backCallback.setEnabled(true);
    }

    private void exitSelection() {
        if (!inSelection) return;
        inSelection = false;
        adapter.stopSelection();
        normalBar.setVisibility(View.VISIBLE);
        selBar.setVisibility(View.GONE);
        recPanel.setVisibility(View.VISIBLE);
        selActions.setVisibility(View.GONE);
        backCallback.setEnabled(false);
    }

    private void bulkStar() {
        List<Recording> sel = adapter.selectedItems();
        if (sel.isEmpty()) return;
        boolean allFav = true;
        for (Recording r : sel) if (!r.favorite) allFav = false;
        for (Recording r : sel) {
            r.favorite = !allFav;
            Repo.save(this, r);
        }
        applyFilter();
    }

    private void bulkLabel() {
        final List<Recording> sel = adapter.selectedItems();
        if (sel.isEmpty()) return;
        String[] names = Labels.names(this);
        String[] items = new String[names.length + 1];
        items[0] = getString(R.string.label_none);
        System.arraycopy(names, 0, items, 1, names.length);
        new AlertDialog.Builder(this)
                .setTitle(R.string.label_title)
                .setItems(items, (d, which) -> {
                    for (Recording r : sel) {
                        r.label = which;
                        Repo.save(this, r);
                    }
                    applyFilter();
                })
                .show();
    }

    private void bulkDelete() {
        final List<Recording> sel = adapter.selectedItems();
        if (sel.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.bulk_delete_message, sel.size()))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    for (Recording r : sel) Repo.trash(this, r);
                    exitSelection();
                    Toast.makeText(this, R.string.moved_trash, Toast.LENGTH_SHORT).show();
                    reload();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void toggleSearch() {
        boolean show = search.getVisibility() != View.VISIBLE;
        search.setVisibility(show ? View.VISIBLE : View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (show) {
            search.requestFocus();
            if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        } else {
            search.setText("");
            if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
    }

    private void refreshChips() {
        for (int i = 0; i < chips.length; i++) {
            Ui.setChipState(chips[i], filter == i);
        }
    }

    private void refreshSegment() {
        int mode = store.getMode();
        for (int i = 0; i < segItems.length; i++) {
            boolean sel = i == mode;
            segItems[i].setBackground(sel ? Ui.round(getColor(R.color.seg_selected), dp(10), 0, 0) : null);
            segItems[i].setTextColor(sel ? c(R.attr.cTextPrimary) : c(R.attr.cTextSecondary));
            segItems[i].setTypeface(Typeface.create(sel ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        }
    }

    private void selectMode(int mode) {
        if (RecorderService.isActive()) {
            Toast.makeText(this, R.string.mode_locked, Toast.LENGTH_SHORT).show();
            return;
        }
        store.setMode(mode);
        refreshSegment();
        refreshLabels();
    }

    private void refreshLabels() {
        String[] quals = getResources().getStringArray(R.array.quality_short);
        if (store.getMode() == Store.M_SPEECH) {
            tagQuality.setText(R.string.quality_voice);
        } else {
            tagQuality.setText(quals[Math.max(0, Math.min(quals.length - 1, store.getQuality()))]);
        }
        refreshSegment();

        int bits = Store.effectiveBitRate(store.getQuality(), store.getMode());
        long seconds = Repo.freeBytes(this) / Math.max(1, bits / 8);
        long hours = seconds / 3600;
        if (hours >= 48) tagFree.setText(getString(R.string.free_days_fmt, hours / 24));
        else tagFree.setText(getString(R.string.free_hours_fmt, hours));
    }

    private void reload() {
        all = Repo.all(this);
        applyFilter();
    }

    private void applyFilter() {
        List<Recording> out = new ArrayList<>();
        long total = 0;
        for (Recording r : all) {
            if (filter == FILTER_STARRED && !r.favorite) continue;
            if (filter == FILTER_MARKED && r.markers.isEmpty()) continue;
            if (filter >= FILTER_LABEL_BASE && r.label != filter - FILTER_LABEL_BASE + 1) continue;
            if (!query.isEmpty() && !r.title.toLowerCase(Locale.getDefault()).contains(query)) continue;
            out.add(r);
            total += r.durationMs;
        }
        final int sort = store.getSort();
        Collections.sort(out, (a, b) -> {
            switch (sort) {
                case Store.SORT_OLDEST: return Long.compare(a.createdAt, b.createdAt);
                case Store.SORT_LONGEST: return Long.compare(b.durationMs, a.durationMs);
                case Store.SORT_NAME: return a.title.compareToIgnoreCase(b.title);
                default: return Long.compare(b.createdAt, a.createdAt);
            }
        });
        adapter.submit(out);
        boolean none = out.isEmpty();
        emptyView.setVisibility(none ? View.VISIBLE : View.GONE);
        rv.setVisibility(none ? View.INVISIBLE : View.VISIBLE);
        emptyText.setText(all.isEmpty() ? R.string.empty_library : R.string.empty_filtered);
        summary.setText(getString(R.string.summary_fmt, out.size(), Fmt.duration(total)));
    }

    private void chooseSort() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sort_title)
                .setSingleChoiceItems(R.array.sort_options, store.getSort(), (d, which) -> {
                    store.setSort(which);
                    d.dismiss();
                    applyFilter();
                })
                .show();
    }

    private void chooseQuality() {
        if (RecorderService.isActive()) {
            Toast.makeText(this, R.string.quality_locked, Toast.LENGTH_SHORT).show();
            return;
        }
        if (store.getMode() == Store.M_SPEECH) {
            Toast.makeText(this, R.string.quality_fixed, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.quality_title)
                .setSingleChoiceItems(R.array.quality_options, store.getQuality(), (d, which) -> {
                    store.setQuality(which);
                    d.dismiss();
                    refreshLabels();
                })
                .show();
    }

    private void showMenu(Recording r) {
        String[] items = {
                getString(R.string.rename),
                getString(r.favorite ? R.string.unstar : R.string.star),
                getString(R.string.label_title),
                getString(R.string.duplicate),
                getString(R.string.action_share),
                getString(R.string.save_device),
                getString(R.string.move_trash)
        };
        new AlertDialog.Builder(this)
                .setTitle(r.title)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            Ui.rename(this, r.title, name -> {
                                r.title = name;
                                Repo.save(this, r);
                                applyFilter();
                            });
                            break;
                        case 1:
                            r.favorite = !r.favorite;
                            Repo.save(this, r);
                            applyFilter();
                            break;
                        case 2:
                            Ui.chooseLabel(this, r, this::applyFilter);
                            break;
                        case 3:
                            if (Repo.duplicate(this, r, getString(R.string.copy_title, r.title)) != null) reload();
                            break;
                        case 4:
                            Ui.share(this, r);
                            break;
                        case 5:
                            Ui.saveToDevice(this, r);
                            break;
                        default:
                            Repo.trash(this, r);
                            Toast.makeText(this, R.string.moved_trash, Toast.LENGTH_SHORT).show();
                            reload();
                            break;
                    }
                })
                .show();
    }

    private void openPlayer(String id) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_ID, id);
        startActivity(i);
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void onRecordTapped() {
        if (RecorderService.isActive()) {
            openRecorder(false);
            return;
        }
        List<String> needed = new ArrayList<>();
        if (!hasAudioPermission()) needed.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && !store.getNotifAsked()
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
            store.setNotifAsked(true);
        }
        if (needed.isEmpty()) {
            openRecorder(true);
        } else {
            permLauncher.launch(needed.toArray(new String[0]));
        }
    }

    private void openRecorder(boolean autostart) {
        Intent i = new Intent(this, RecordActivity.class);
        i.putExtra(RecordActivity.EXTRA_AUTOSTART, autostart);
        startActivity(i);
    }

    private void showPermissionDenied() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.perm_title)
                .setMessage(R.string.perm_message)
                .setPositiveButton(R.string.open_settings, (d, w) -> {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(i);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void refreshRecState() {
        RecorderService s = RecorderService.get();
        boolean active = s != null && s.state() != RecorderService.IDLE;
        if (active) {
            String label = getString(s.state() == RecorderService.PAUSED ? R.string.banner_paused : R.string.banner_recording);
            banner.setText("\u25CF  " + label + "  " + Fmt.duration(s.elapsedMs()));
            banner.setVisibility(View.VISIBLE);
        } else {
            banner.setVisibility(View.GONE);
        }
        if (active != lastActive) {
            lastActive = active;
            recInner.setBackgroundResource(active ? R.drawable.bg_rec_stop : R.drawable.bg_rec_dot);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) recInner.getLayoutParams();
            lp.width = dp(active ? 30 : 64);
            lp.height = dp(active ? 30 : 64);
            recInner.setLayoutParams(lp);
            if (!active) reload();
        }
    }
}
