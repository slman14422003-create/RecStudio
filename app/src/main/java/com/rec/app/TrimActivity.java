package com.rec.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.IOException;

public final class TrimActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "id";

    private Store store;
    private Recording rec;
    private MediaPlayer mp;
    private boolean ready;
    private boolean busy;
    private long durationMs;
    private float selA = 0f;
    private float selB = 1f;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private WaveformView wave;
    private TextView tvStart;
    private TextView tvEnd;
    private TextView tvLen;
    private TextView btnSave;
    private ImageView playIcon;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (mp == null || !ready || !mp.isPlaying()) return;
            int pos = mp.getCurrentPosition();
            if (pos >= selB * durationMs) {
                mp.pause();
                mp.seekTo((int) (selA * durationMs));
                playIcon.setImageResource(R.drawable.ic_play);
                wave.setProgress(selA);
                return;
            }
            if (durationMs > 0) wave.setProgress((float) pos / durationMs);
            handler.postDelayed(this, 30);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        String id = getIntent().getStringExtra(EXTRA_ID);
        rec = id == null ? null : Repo.get(this, id);
        if (rec == null) {
            finish();
            return;
        }
        durationMs = rec.durationMs;
        buildUi();
        setupPlayer();
        updateLabels();
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausePreview();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        releasePlayer();
        super.onDestroy();
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
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(spacer);

        btnSave = new TextView(this);
        btnSave.setText(R.string.save);
        btnSave.setTextSize(15);
        btnSave.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btnSave.setTextColor(getColor(R.color.on_accent));
        btnSave.setGravity(Gravity.CENTER);
        btnSave.setBackgroundResource(R.drawable.bg_pill_accent);
        btnSave.setPadding(dp(20), 0, dp(20), 0);
        btnSave.setClickable(true);
        btnSave.setFocusable(true);
        btnSave.setOnClickListener(v -> askSave());
        bar.addView(btnSave, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        root.addView(bar);

        root.addView(Ui.largeTitle(this, R.string.trim_title));

        TextView hint = new TextView(this);
        hint.setText(R.string.trim_hint);
        hint.setTextSize(14);
        hint.setTextColor(c(R.attr.cTextSecondary));
        hint.setPadding(dp(22), 0, dp(22), dp(16));
        root.addView(hint);

        LinearLayout card = Ui.card(this);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        wave = new WaveformView(this);
        wave.setSelectable(true);
        wave.setPeaks(rec.peaksAsFloat());
        wave.setDuration(durationMs);
        wave.setMinGap(Math.min(0.5f, 500f / Math.max(1f, durationMs)));
        wave.setOnSelection((a, b, finished) -> {
            selA = a;
            selB = b;
            pausePreview();
            updateLabels();
            if (finished) {
                if (mp != null && ready) mp.seekTo((int) (selA * durationMs));
                wave.setProgress(selA);
            }
        });
        card.addView(wave, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dp(16), 0, dp(16), 0);
        root.addView(card, clp);

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.setPadding(dp(26), dp(12), dp(26), 0);
        tvStart = timeText();
        tvLen = timeText();
        tvLen.setGravity(Gravity.CENTER);
        tvLen.setTextColor(c(R.attr.cAccentText));
        tvEnd = timeText();
        tvEnd.setGravity(Gravity.END);
        times.addView(tvStart, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(tvLen, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(tvEnd, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(times);

        FrameLayout play = new FrameLayout(this);
        play.setBackgroundResource(R.drawable.bg_pill_accent);
        play.setClickable(true);
        play.setFocusable(true);
        play.setContentDescription(getString(R.string.action_play));
        play.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(this, R.animator.press_scale));
        playIcon = new ImageView(this);
        playIcon.setImageResource(R.drawable.ic_play);
        playIcon.setImageTintList(ColorStateList.valueOf(getColor(R.color.on_accent)));
        play.addView(playIcon, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        play.setOnClickListener(v -> {
            Ui.haptic(v, store);
            togglePreview();
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(76), dp(76));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(28);
        root.addView(play, plp);

        TextView label = new TextView(this);
        label.setText(R.string.trim_preview);
        label.setTextSize(13);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(c(R.attr.cTextSecondary));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(10);
        root.addView(label, llp);

        setContentView(root);
    }

    private TextView timeText() {
        TextView t = new TextView(this);
        t.setTextSize(14);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(c(R.attr.cTextSecondary));
        return t;
    }

    private void setupPlayer() {
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(rec.file(this).getAbsolutePath());
            mp.prepare();
            ready = true;
            int d = mp.getDuration();
            if (d > 0) {
                durationMs = d;
                wave.setDuration(durationMs);
                wave.setMinGap(Math.min(0.5f, 500f / Math.max(1f, durationMs)));
            }
            mp.setOnCompletionListener(p -> {
                handler.removeCallbacks(tick);
                playIcon.setImageResource(R.drawable.ic_play);
                p.seekTo((int) (selA * durationMs));
            });
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, R.string.play_failed, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void releasePlayer() {
        if (mp != null) {
            try {
                mp.release();
            } catch (RuntimeException ignored) {
            }
            mp = null;
        }
        ready = false;
    }

    private void updateLabels() {
        long a = (long) (selA * durationMs);
        long b = (long) (selB * durationMs);
        tvStart.setText(Fmt.durationTenths(a));
        tvEnd.setText(Fmt.durationTenths(b));
        tvLen.setText(Fmt.durationTenths(b - a));
    }

    private void togglePreview() {
        if (mp == null || !ready || busy) return;
        if (mp.isPlaying()) {
            pausePreview();
            return;
        }
        int pos = mp.getCurrentPosition();
        if (pos < selA * durationMs || pos >= selB * durationMs - 100) mp.seekTo((int) (selA * durationMs));
        mp.start();
        playIcon.setImageResource(R.drawable.ic_pause);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void pausePreview() {
        handler.removeCallbacks(tick);
        if (mp != null && ready && mp.isPlaying()) mp.pause();
        if (playIcon != null) playIcon.setImageResource(R.drawable.ic_play);
    }

    private void askSave() {
        if (busy) return;
        long len = (long) ((selB - selA) * durationMs);
        if (len < 300) {
            Toast.makeText(this, R.string.trim_too_short, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = {getString(R.string.trim_save_new), getString(R.string.trim_replace)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.trim_title)
                .setItems(items, (d, which) -> runTrim(which == 1))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void runTrim(final boolean replace) {
        pausePreview();
        busy = true;
        btnSave.setAlpha(0.5f);
        Toast.makeText(this, R.string.trim_saving, Toast.LENGTH_SHORT).show();
        final long startMs = (long) (selA * durationMs);
        final long endMs = (long) (selB * durationMs);
        final float a = selA;
        final float b = selB;
        final String newId = replace ? rec.id : Repo.newId();
        final File src = rec.file(this);
        final File tmp = new File(Repo.dir(this), newId + ".trim");
        new Thread(() -> {
            final long dur = Trimmer.trim(src, tmp, startMs * 1000L, endMs * 1000L);
            runOnUiThread(() -> finishTrim(replace, newId, tmp, dur, startMs, endMs, a, b));
        }).start();
    }

    private void finishTrim(boolean replace, String newId, File tmp, long dur, long startMs, long endMs, float a, float b) {
        if (dur <= 0) {
            tmp.delete();
            busy = false;
            btnSave.setAlpha(1f);
            Toast.makeText(this, R.string.trim_failed, Toast.LENGTH_LONG).show();
            return;
        }
        releasePlayer();
        File dst = Repo.audio(this, newId);
        dst.delete();
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            busy = false;
            btnSave.setAlpha(1f);
            Toast.makeText(this, R.string.trim_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Recording out;
        if (replace) {
            out = rec;
        } else {
            out = new Recording(newId, getString(R.string.trim_copy_title, rec.title), System.currentTimeMillis());
            out.quality = rec.quality;
            out.mode = rec.mode;
            out.label = rec.label;
        }
        out.durationMs = dur;
        out.peaks = Recording.slicePeaks(rec.peaks, a, b);
        java.util.ArrayList<Long> shifted = new java.util.ArrayList<>();
        for (long m : rec.markers) {
            if (m >= startMs && m <= endMs) shifted.add(m - startMs);
        }
        out.markers.clear();
        out.markers.addAll(shifted);
        Repo.save(this, out);
        Intent data = new Intent();
        data.putExtra(EXTRA_ID, out.id);
        setResult(RESULT_OK, data);
        finish();
    }
}
