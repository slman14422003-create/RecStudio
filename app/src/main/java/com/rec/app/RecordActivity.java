package com.rec.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public final class RecordActivity extends AppCompatActivity {

    public static final String EXTRA_AUTOSTART = "autostart";

    private static final long START_TIMEOUT_MS = 4000;

    private Store store;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvTime;
    private TextView tvInfo;
    private TextView tagState;
    private TextView labelPause;
    private ImageButton btnPause;
    private WaveformView wave;

    private boolean seenActive;
    private long requestedAt;
    private int consumed;
    private float pending;
    private int pendingCount;
    private int lastState = -1;
    private int lastMarkers = -1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        buildUi();
        if (store.getKeepAwake()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        boolean auto = savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_AUTOSTART, false);
        if (auto && !RecorderService.isActive()) {
            RecorderService.start(this);
            requestedAt = System.currentTimeMillis();
        } else if (!RecorderService.isActive()) {
            finish();
            return;
        }
        getIntent().removeExtra(EXTRA_AUTOSTART);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        consumed = 0;
        pendingCount = 0;
        pending = 0f;
        wave.clearLevels();
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

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(8), dp(16), dp(4));

        ImageButton back = Ui.iconButton(this, R.drawable.ic_back, R.string.action_back);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        FrameLayout center = new FrameLayout(this);
        tagState = Ui.tag(this, getString(R.string.state_recording));
        tagState.setClickable(false);
        center.addView(tagState, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        bar.addView(center, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton discard = Ui.iconButton(this, R.drawable.ic_trash, R.string.discard);
        discard.setImageTintList(ColorStateList.valueOf(getColor(R.color.bad)));
        discard.setOnClickListener(v -> Ui.confirm(this, R.string.discard_title, R.string.discard_message, R.string.discard, this::discard));
        bar.addView(discard);
        root.addView(bar);

        tvTime = new TextView(this);
        tvTime.setText(Fmt.duration(0));
        tvTime.setGravity(Gravity.CENTER);
        tvTime.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        tvTime.setTextColor(c(R.attr.cTextPrimary));
        tvTime.setIncludeFontPadding(false);
        Ui.autoFit(tvTime, 40, 76);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96));
        tlp.setMargins(dp(24), dp(28), dp(24), 0);
        root.addView(tvTime, tlp);

        tvInfo = new TextView(this);
        tvInfo.setGravity(Gravity.CENTER);
        tvInfo.setTextSize(14);
        tvInfo.setTextColor(c(R.attr.cTextSecondary));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(4);
        root.addView(tvInfo, ilp);

        LinearLayout mid = new LinearLayout(this);
        mid.setGravity(Gravity.CENTER);
        LinearLayout card = Ui.card(this);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        wave = new WaveformView(this);
        wave.setLive(true);
        card.addView(wave, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180));
        clp.setMargins(dp(16), 0, dp(16), 0);
        mid.addView(card, clp);
        root.addView(mid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_keypad_panel);
        panel.setPadding(dp(12), dp(20), dp(12), dp(16));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(dp(12), dp(4), dp(12), dp(12));

        btnPause = Ui.roundButton(this, R.drawable.ic_pause, R.string.action_pause, 60);
        labelPause = controlLabel(getString(R.string.action_pause));
        panel.addView(cell(btnPause, labelPause), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout stop = new FrameLayout(this);
        stop.setBackgroundResource(R.drawable.bg_rec_ring);
        stop.setClickable(true);
        stop.setFocusable(true);
        stop.setContentDescription(getString(R.string.action_stop));
        stop.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(this, R.animator.press_scale));
        View square = new View(this);
        square.setBackgroundResource(R.drawable.bg_rec_stop);
        stop.addView(square, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        stop.setOnClickListener(v -> {
            Ui.haptic(v, store);
            stopAndOpen();
        });
        FrameLayout.LayoutParams stopLp = new FrameLayout.LayoutParams(dp(84), dp(84));
        FrameLayout stopWrap = new FrameLayout(this);
        stopWrap.addView(stop, stopLp);
        panel.addView(cell(stopWrap, controlLabel(getString(R.string.action_stop))), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton btnMark = Ui.roundButton(this, R.drawable.ic_flag, R.string.action_marker, 60);
        btnMark.setOnClickListener(v -> {
            RecorderService s = RecorderService.get();
            if (s != null && s.mark()) {
                Ui.haptic(v, store);
                updateInfo(s);
            }
        });
        panel.addView(cell(btnMark, controlLabel(getString(R.string.action_marker))), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnPause.setOnClickListener(v -> {
            RecorderService s = RecorderService.get();
            if (s == null) return;
            Ui.haptic(v, store);
            if (s.state() == RecorderService.RECORDING) s.pause();
            else if (s.state() == RecorderService.PAUSED) s.resume();
        });

        root.addView(panel, plp);
        setContentView(root);
    }

    private TextView controlLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(c(R.attr.cTextSecondary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout cell(View button, TextView label) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) (button.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? button.getLayoutParams()
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        l.addView(button, blp);
        l.addView(label);
        return l;
    }

    private void update() {
        RecorderService s = RecorderService.get();
        if (s == null || s.state() == RecorderService.IDLE) {
            if (seenActive) {
                String saved = RecorderService.takeSaved();
                seenActive = false;
                handler.removeCallbacks(tick);
                if (saved != null) openPlayer(saved);
                finish();
                return;
            }
            if (RecorderService.hasStartFailed()
                    || (requestedAt > 0 && System.currentTimeMillis() - requestedAt > START_TIMEOUT_MS)) {
                Toast.makeText(this, R.string.start_failed, Toast.LENGTH_LONG).show();
                finish();
            }
            return;
        }
        seenActive = true;

        tvTime.setText(Fmt.duration(s.elapsedMs()));

        int n = s.levelCount();
        boolean changed = false;
        while (consumed < n) {
            pending = Math.max(pending, s.levelAt(consumed));
            pendingCount++;
            consumed++;
            if (pendingCount >= 2) {
                wave.addLevel(pending);
                pending = 0f;
                pendingCount = 0;
                changed = true;
            }
        }
        if (changed) wave.invalidate();

        int st = s.state();
        if (st != lastState) {
            lastState = st;
            boolean paused = st == RecorderService.PAUSED;
            tagState.setText(paused ? R.string.state_paused : R.string.state_recording);
            if (paused) Ui.setTagState(tagState, false);
            else Ui.setTagRecording(tagState);
            tvTime.setTextColor(paused ? c(R.attr.cTextSecondary) : c(R.attr.cTextPrimary));
            btnPause.setImageResource(paused ? R.drawable.ic_mic : R.drawable.ic_pause);
            labelPause.setText(paused ? R.string.action_resume : R.string.action_pause);
            btnPause.setContentDescription(getString(paused ? R.string.action_resume : R.string.action_pause));
            updateInfo(s);
        }
        if (s.markerCount() != lastMarkers) updateInfo(s);
    }

    private void updateInfo(RecorderService s) {
        lastMarkers = s.markerCount();
        String[] q = getResources().getStringArray(R.array.quality_options);
        String quality = q[Math.max(0, Math.min(q.length - 1, s.quality()))];
        String text = quality;
        if (lastMarkers > 0) text = quality + "  \u00B7  " + getString(R.string.markers_short, lastMarkers);
        tvInfo.setText(text);
    }

    private void stopAndOpen() {
        RecorderService s = RecorderService.get();
        if (s == null) {
            finish();
            return;
        }
        handler.removeCallbacks(tick);
        String id = s.finish();
        seenActive = false;
        RecorderService.takeSaved();
        if (id == null) {
            Toast.makeText(this, R.string.too_short, Toast.LENGTH_SHORT).show();
        } else {
            openPlayer(id);
        }
        finish();
    }

    private void discard() {
        RecorderService s = RecorderService.get();
        handler.removeCallbacks(tick);
        if (s != null) s.discard();
        seenActive = false;
        RecorderService.takeSaved();
        finish();
    }

    private void openPlayer(String id) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_ID, id);
        startActivity(i);
    }
}
