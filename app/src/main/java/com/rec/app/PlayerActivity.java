package com.rec.app;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.content.Intent;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;

public final class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "id";

    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    private Store store;
    private Recording rec;
    private MediaPlayer mp;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private ActivityResultLauncher<Intent> trimLauncher;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvTitle;
    private TextView tvMeta;
    private TextView tvPos;
    private TextView tvRemain;
    private TextView tagSpeed;
    private TextView tagRepeat;
    private TextView tagLabel;
    private WaveformView wave;
    private ImageView playIcon;
    private ImageButton btnStar;
    private LinearLayout markersBox;
    private LinearLayout markersCard;

    private float speed = 1f;
    private boolean loop;
    private boolean dragging;
    private boolean ready;
    private long durationMs;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (mp != null && mp.isPlaying()) handler.postDelayed(this, 33);
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pausePlayback();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        speed = store.getSpeed();

        trimLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                String id = result.getData().getStringExtra(TrimActivity.EXTRA_ID);
                if (id != null) reloadWith(id);
            }
        });

        String id = getIntent().getStringExtra(EXTRA_ID);
        rec = id == null ? null : Repo.get(this, id);
        if (rec == null) {
            finish();
            return;
        }

        buildUi();
        setupPlayer();
        refreshAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausePlayback();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        abandonFocus();
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

        btnStar = Ui.iconButton(this, R.drawable.ic_star, R.string.star);
        btnStar.setOnClickListener(v -> {
            rec.favorite = !rec.favorite;
            Repo.save(this, rec);
            refreshStar();
        });
        bar.addView(btnStar, spaced());

        ImageButton btnShare = Ui.iconButton(this, R.drawable.ic_share, R.string.action_share);
        btnShare.setOnClickListener(v -> Ui.share(this, rec));
        bar.addView(btnShare, spaced());

        ImageButton btnMore = Ui.iconButton(this, R.drawable.ic_more, R.string.action_more);
        btnMore.setOnClickListener(v -> showMore());
        bar.addView(btnMore, spaced());
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tvTitle = Ui.largeTitle(this, rec.title);
        tvTitle.setClickable(true);
        tvTitle.setOnClickListener(v -> renameCurrent());
        content.addView(tvTitle);

        tvMeta = new TextView(this);
        tvMeta.setTextSize(14);
        tvMeta.setTextColor(c(R.attr.cTextSecondary));
        tvMeta.setPadding(dp(22), 0, dp(22), dp(14));
        content.addView(tvMeta);

        LinearLayout waveCard = Ui.card(this);
        waveCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        wave = new WaveformView(this);
        wave.setInteractive(true);
        wave.setPeaks(rec.peaksAsFloat());
        wave.setMarkers(rec.markers);
        wave.setOnSeek((f, finished) -> {
            dragging = !finished;
            long target = (long) (f * durationMs);
            updateTimes(target);
            if (finished && mp != null && ready) mp.seekTo((int) target);
        });
        waveCard.addView(wave, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.setMargins(dp(16), 0, dp(16), 0);
        content.addView(waveCard, wlp);

        tvPos = new TextView(this);
        tvPos.setGravity(Gravity.CENTER);
        tvPos.setTextSize(46);
        tvPos.setIncludeFontPadding(false);
        tvPos.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        tvPos.setTextColor(c(R.attr.cTextPrimary));
        LinearLayout.LayoutParams plp0 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp0.topMargin = dp(18);
        content.addView(tvPos, plp0);

        tvRemain = timeText();
        tvRemain.setGravity(Gravity.CENTER);
        tvRemain.setPadding(0, dp(4), 0, 0);
        content.addView(tvRemain);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(22), 0, dp(8));
        controls.addView(skipButton(false));

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
            togglePlayback();
        });
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(76), dp(76));
        playLp.setMargins(dp(24), 0, dp(24), 0);
        controls.addView(play, playLp);

        controls.addView(skipButton(true));
        content.addView(controls);

        HorizontalScrollView pillScroll = new HorizontalScrollView(this);
        pillScroll.setHorizontalScrollBarEnabled(false);
        pillScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        pillScroll.setFillViewport(true);
        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setGravity(Gravity.CENTER);
        pills.setPadding(dp(16), dp(12), dp(16), dp(4));

        tagSpeed = Ui.tag(this, Fmt.speed(speed));
        tagSpeed.setOnClickListener(v -> cycleSpeed());
        pills.addView(tagSpeed, pillLp());

        tagRepeat = Ui.tag(this, getString(R.string.repeat));
        tagRepeat.setOnClickListener(v -> {
            loop = !loop;
            if (mp != null && ready) mp.setLooping(loop);
            Ui.setTagState(tagRepeat, loop);
        });
        pills.addView(tagRepeat, pillLp());

        TextView tagMark = Ui.tag(this, getString(R.string.add_marker));
        tagMark.setOnClickListener(v -> addMarker());
        pills.addView(tagMark, pillLp());

        tagLabel = Ui.tag(this, getString(R.string.label_title));
        tagLabel.setOnClickListener(v -> Ui.chooseLabel(this, rec, this::refreshLabelTag));
        pills.addView(tagLabel, pillLp());

        pillScroll.addView(pills, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(pillScroll);

        markersCard = new LinearLayout(this);
        markersCard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.setMargins(dp(16), 0, dp(16), 0);
        Ui.groupTitle(markersCard, R.string.markers_title);
        markersBox = Ui.card(this);
        markersCard.addView(markersBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(markersCard, mlp);

        setContentView(root);
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.setMarginStart(dp(8));
        return lp;
    }

    private LinearLayout.LayoutParams pillLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(5), 0, dp(5), 0);
        return lp;
    }

    private TextView timeText() {
        TextView t = new TextView(this);
        t.setTextSize(13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(c(R.attr.cTextSecondary));
        return t;
    }

    private View skipButton(boolean forward) {
        FrameLayout f = new FrameLayout(this);
        f.setClickable(true);
        f.setFocusable(true);
        f.setBackgroundResource(R.drawable.bg_round_field);
        f.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(this, R.animator.press_scale));
        f.setContentDescription(getString(forward ? R.string.action_forward : R.string.action_rewind));
        ImageView icon = new ImageView(this);
        icon.setImageResource(forward ? R.drawable.ic_skip_forward : R.drawable.ic_skip_back);
        icon.setImageTintList(ColorStateList.valueOf(c(R.attr.cTextPrimary)));
        f.addView(icon, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        TextView n = new TextView(this);
        n.setText(String.valueOf(store.getSkip()));
        n.setTextSize(8);
        n.setIncludeFontPadding(false);
        n.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        n.setTextColor(c(R.attr.cTextPrimary));
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        nlp.topMargin = dp(3);
        f.addView(n, nlp);
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
        f.setOnClickListener(v -> {
            Ui.haptic(v, store);
            seekBy((forward ? 1 : -1) * store.getSkip() * 1000L);
        });
        return f;
    }

    private void setupPlayer() {
        durationMs = rec.durationMs;
        ready = false;
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
            if (d > 0) durationMs = d;
            mp.setLooping(loop);
            mp.setOnCompletionListener(p -> {
                handler.removeCallbacks(tick);
                abandonFocus();
                playIcon.setImageResource(R.drawable.ic_play);
                p.seekTo(0);
                wave.setProgress(0f);
                updateTimes(0);
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

    private void reloadWith(String id) {
        Recording next = Repo.get(this, id);
        if (next == null) return;
        handler.removeCallbacks(tick);
        abandonFocus();
        releasePlayer();
        rec = next;
        playIcon.setImageResource(R.drawable.ic_play);
        wave.setPeaks(rec.peaksAsFloat());
        wave.setProgress(0f);
        tvTitle.setText(rec.title);
        setupPlayer();
        refreshAll();
    }

    private void refreshAll() {
        if (rec == null) return;
        wave.setDuration(durationMs);
        wave.setMarkers(rec.markers);
        CharSequence when = DateUtils.formatDateTime(this, rec.createdAt,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_ABBREV_MONTH);
        String[] modes = getResources().getStringArray(R.array.mode_options);
        String mode = modes[Math.max(0, Math.min(modes.length - 1, rec.mode))];
        tvMeta.setText(when + "  \u00B7  " + Fmt.size(rec.size(this)) + "  \u00B7  " + mode);
        refreshStar();
        refreshLabelTag();
        refreshMarkers();
        updateTimes(0);
    }

    private void refreshStar() {
        btnStar.setImageResource(rec.favorite ? R.drawable.ic_star_fill : R.drawable.ic_star);
        btnStar.setImageTintList(ColorStateList.valueOf(rec.favorite ? getColor(R.color.warn) : c(R.attr.cTextPrimary)));
        btnStar.setContentDescription(getString(rec.favorite ? R.string.unstar : R.string.star));
    }

    private void refreshLabelTag() {
        if (rec.label > 0) {
            tagLabel.setText(Labels.name(this, rec.label));
            int color = Labels.color(this, rec.label);
            int left = tagLabel.getPaddingLeft();
            int top = tagLabel.getPaddingTop();
            int right = tagLabel.getPaddingRight();
            int bottom = tagLabel.getPaddingBottom();
            tagLabel.setBackground(Ui.round(color, dp(9), 0, 0));
            tagLabel.setPadding(left, top, right, bottom);
            tagLabel.setTextColor(getColor(R.color.on_accent));
        } else {
            tagLabel.setText(R.string.label_title);
            Ui.setTagState(tagLabel, false);
        }
    }

    private void refreshMarkers() {
        markersBox.removeAllViews();
        markersCard.setVisibility(rec.markers.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < rec.markers.size(); i++) {
            final int index = i;
            final long at = rec.markers.get(i);
            if (i > 0) markersBox.addView(Ui.divider(this, 16));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(52));
            row.setPadding(dp(16), dp(4), dp(8), dp(4));
            row.setBackgroundResource(R.drawable.bg_row_flat);
            row.setClickable(true);
            row.setFocusable(true);

            ImageView flag = new ImageView(this);
            flag.setImageResource(R.drawable.ic_flag);
            flag.setImageTintList(ColorStateList.valueOf(getColor(R.color.warn)));
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(20), dp(20));
            flp.setMarginEnd(dp(12));
            row.addView(flag, flp);

            TextView label = new TextView(this);
            label.setText(getString(R.string.marker_n, i + 1));
            label.setTextSize(16);
            label.setTextColor(c(R.attr.cTextPrimary));
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView time = new TextView(this);
            time.setText(Fmt.durationTenths(at));
            time.setTextSize(15);
            time.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            time.setTextColor(c(R.attr.cAccentText));
            row.addView(time);

            ImageButton remove = Ui.iconButton(this, R.drawable.ic_trash, R.string.delete);
            remove.setBackground(null);
            remove.setImageTintList(ColorStateList.valueOf(c(R.attr.cTextHint)));
            remove.setOnClickListener(v -> {
                rec.markers.remove(index);
                Repo.save(this, rec);
                wave.setMarkers(rec.markers);
                refreshMarkers();
            });
            row.addView(remove, new LinearLayout.LayoutParams(dp(40), dp(40)));

            row.setOnClickListener(v -> {
                if (mp != null && ready) {
                    mp.seekTo((int) at);
                    updateProgress();
                }
            });
            markersBox.addView(row);
        }
    }

    private void renameCurrent() {
        Ui.rename(this, rec.title, name -> {
            rec.title = name;
            Repo.save(this, rec);
            tvTitle.setText(name);
        });
    }

    private void showMore() {
        String[] items = {
                getString(R.string.rename),
                getString(R.string.label_title),
                getString(R.string.trim),
                getString(R.string.duplicate),
                getString(R.string.save_device),
                getString(R.string.move_trash)
        };
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            renameCurrent();
                            break;
                        case 1:
                            Ui.chooseLabel(this, rec, this::refreshLabelTag);
                            break;
                        case 2:
                            pausePlayback();
                            Intent i = new Intent(this, TrimActivity.class);
                            i.putExtra(TrimActivity.EXTRA_ID, rec.id);
                            trimLauncher.launch(i);
                            break;
                        case 3:
                            if (Repo.duplicate(this, rec, getString(R.string.copy_title, rec.title)) != null) {
                                Toast.makeText(this, R.string.duplicated, Toast.LENGTH_SHORT).show();
                            }
                            break;
                        case 4:
                            Ui.saveToDevice(this, rec);
                            break;
                        default:
                            stopPlayback();
                            Repo.trash(this, rec);
                            Toast.makeText(this, R.string.moved_trash, Toast.LENGTH_SHORT).show();
                            finish();
                            break;
                    }
                })
                .show();
    }

    private void addMarker() {
        if (mp == null || !ready) return;
        rec.addMarker(mp.getCurrentPosition());
        Repo.save(this, rec);
        wave.setMarkers(rec.markers);
        refreshMarkers();
    }

    private void togglePlayback() {
        if (mp == null || !ready) return;
        if (mp.isPlaying()) {
            pausePlayback();
        } else {
            if (!requestFocus()) return;
            mp.start();
            applySpeed();
            playIcon.setImageResource(R.drawable.ic_pause);
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
    }

    private void pausePlayback() {
        if (mp != null && ready && mp.isPlaying()) mp.pause();
        handler.removeCallbacks(tick);
        if (playIcon != null) playIcon.setImageResource(R.drawable.ic_play);
        abandonFocus();
    }

    private void stopPlayback() {
        handler.removeCallbacks(tick);
        if (mp != null) {
            try {
                if (mp.isPlaying()) mp.stop();
            } catch (RuntimeException ignored) {
            }
        }
        abandonFocus();
    }

    private void seekBy(long deltaMs) {
        if (mp == null || !ready) return;
        long target = Math.max(0, Math.min(durationMs, mp.getCurrentPosition() + deltaMs));
        mp.seekTo((int) target);
        updateTimes(target);
        if (durationMs > 0) wave.setProgress((float) target / durationMs);
    }

    private void cycleSpeed() {
        int idx = 0;
        for (int i = 0; i < SPEEDS.length; i++) if (Math.abs(SPEEDS[i] - speed) < 0.001f) idx = i;
        speed = SPEEDS[(idx + 1) % SPEEDS.length];
        store.setSpeed(speed);
        tagSpeed.setText(Fmt.speed(speed));
        applySpeed();
    }

    private void applySpeed() {
        if (mp == null || !ready || !mp.isPlaying()) return;
        try {
            PlaybackParams p = mp.getPlaybackParams();
            p.setSpeed(speed);
            mp.setPlaybackParams(p);
        } catch (RuntimeException ignored) {
        }
    }

    private void updateProgress() {
        if (mp == null || !ready || dragging) return;
        int pos = mp.getCurrentPosition();
        if (durationMs > 0) wave.setProgress((float) pos / durationMs);
        updateTimes(pos);
    }

    private void updateTimes(long pos) {
        tvPos.setText(Fmt.durationTenths(pos));
        tvRemain.setText("-" + Fmt.duration(Math.max(0, durationMs - pos)) + "  \u00B7  " + Fmt.duration(durationMs));
    }

    private boolean requestFocus() {
        if (audio == null) return true;
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(focusListener)
                    .build();
            result = audio.requestAudioFocus(focusRequest);
        } else {
            result = requestFocusLegacy();
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private int requestFocusLegacy() {
        return audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        if (audio == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        } else {
            audio.abandonAudioFocus(focusListener);
        }
    }
}
