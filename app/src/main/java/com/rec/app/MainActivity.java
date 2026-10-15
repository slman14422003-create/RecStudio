package com.rec.app;

import android.Manifest;
import android.animation.AnimatorInflater;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
    private static final int FILTER_FLAGGED = 2;

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
    private TextView tagSort;
    private TextView tagQuality;
    private EditText search;
    private final TextView[] chips = new TextView[3];
    private View recInner;
    private boolean lastActive;

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

        permLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            if (hasAudioPermission()) {
                openRecorder(true);
            } else {
                showPermissionDenied();
            }
        });

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

        root.addView(buildTopBar());
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
        adapter = new RecordingAdapter(this, new RecordingAdapter.Listener() {
            @Override public void onOpen(Recording r) { openPlayer(r.id); }
            @Override public void onMenu(Recording r) { showMenu(r); }
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
        root.addView(buildRecordPanel());
        setContentView(root);
        refreshChips();
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(8), dp(16), dp(4));

        ImageButton btnSearch = Ui.iconButton(this, R.drawable.ic_search, R.string.action_search);
        btnSearch.setOnClickListener(v -> toggleSearch());
        bar.addView(btnSearch);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(c(R.attr.cTextPrimary));
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);

        ImageButton btnSettings = Ui.iconButton(this, R.drawable.ic_settings, R.string.action_settings);
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        bar.addView(btnSettings);
        return bar;
    }

    private View buildFilters() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        String[] labels = {getString(R.string.filter_all), getString(R.string.filter_starred), getString(R.string.filter_flagged)};
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
    }

    private View buildRecordPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_keypad_panel);
        panel.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(dp(12), dp(4), dp(12), dp(12));
        panel.setLayoutParams(plp);

        FrameLayout left = new FrameLayout(this);
        tagSort = Ui.tag(this, "");
        tagSort.setOnClickListener(v -> chooseSort());
        left.addView(tagSort, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));
        panel.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

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
        panel.addView(button, new LinearLayout.LayoutParams(dp(84), dp(84)));

        FrameLayout right = new FrameLayout(this);
        tagQuality = Ui.tag(this, "");
        tagQuality.setOnClickListener(v -> chooseQuality());
        right.addView(tagQuality, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        panel.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return panel;
    }

    private void attachSwipe() {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(getColor(R.color.bad));
        final Drawable trash = ContextCompat.getDrawable(this, R.drawable.ic_trash);
        final float radius = dp(18);
        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder v, @NonNull RecyclerView.ViewHolder t) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                final int pos = vh.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                final Recording r = adapter.get(pos);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.delete_title)
                        .setMessage(R.string.delete_message)
                        .setPositiveButton(R.string.delete, (d, w) -> {
                            Repo.delete(MainActivity.this, r.id);
                            reload();
                        })
                        .setNegativeButton(R.string.cancel, (d, w) -> adapter.notifyItemChanged(pos))
                        .setOnCancelListener(d -> adapter.notifyItemChanged(pos))
                        .show();
            }

            @Override
            public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh,
                                    float dX, float dY, int state, boolean active) {
                View v = vh.itemView;
                if (dX != 0) {
                    float left;
                    float right;
                    if (dX > 0) {
                        left = v.getLeft();
                        right = v.getLeft() + dX;
                    } else {
                        left = v.getRight() + dX;
                        right = v.getRight();
                    }
                    canvas.drawRoundRect(left, v.getTop(), right, v.getBottom(), radius, radius, paint);
                    if (trash != null) {
                        int size = dp(24);
                        int cy = (v.getTop() + v.getBottom()) / 2;
                        int x = dX > 0 ? v.getLeft() + dp(22) : v.getRight() - dp(22) - size;
                        if (Math.abs(dX) > dp(22) + size) {
                            trash.setBounds(x, cy - size / 2, x + size, cy + size / 2);
                            trash.draw(canvas);
                        }
                    }
                }
                super.onChildDraw(canvas, r, vh, dX, dY, state, active);
            }
        });
        helper.attachToRecyclerView(rv);
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
        for (int i = 0; i < chips.length; i++) Ui.setChipState(chips[i], filter == i);
    }

    private void refreshLabels() {
        String[] sorts = getResources().getStringArray(R.array.sort_options);
        String[] quals = getResources().getStringArray(R.array.quality_short);
        tagSort.setText(sorts[Math.max(0, Math.min(sorts.length - 1, store.getSort()))]);
        tagQuality.setText(quals[Math.max(0, Math.min(quals.length - 1, store.getQuality()))]);
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
            if (filter == FILTER_FLAGGED && r.markers.isEmpty()) continue;
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
                    refreshLabels();
                    applyFilter();
                })
                .show();
    }

    private void chooseQuality() {
        if (RecorderService.isActive()) {
            Toast.makeText(this, R.string.quality_locked, Toast.LENGTH_SHORT).show();
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
                getString(R.string.action_share),
                getString(R.string.delete)
        };
        new AlertDialog.Builder(this)
                .setTitle(r.title)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        Ui.rename(this, r.title, name -> {
                            r.title = name;
                            Repo.save(this, r);
                            applyFilter();
                        });
                    } else if (which == 1) {
                        r.favorite = !r.favorite;
                        Repo.save(this, r);
                        applyFilter();
                    } else if (which == 2) {
                        Ui.share(this, r);
                    } else {
                        Ui.confirm(this, R.string.delete_title, R.string.delete_message, R.string.delete, () -> {
                            Repo.delete(this, r.id);
                            reload();
                        });
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
