package com.rec.app;

import android.content.res.ColorStateList;
import android.graphics.Rect;
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
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "id";
    public static final String EXTRA_FRESH = "fresh";

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
    private LinearLayout transcriptBox;
    private Transcript transcript;
    private final List<TextView> segTexts = new ArrayList<>();
    private int highlighted = -1;
    private ScrollView mainScroll;
    private TextView liveCaption;
    private final List<View> segRows = new ArrayList<>();
    private long lastUserScroll;
    private boolean summarizing;
    private long seenDone;
    private long seenError;
    private boolean lastBusyHere;
    private android.widget.ProgressBar jobBar;
    private TextView jobPct;
    private final Runnable jobTick = new Runnable() {
        @Override
        public void run() {
            pollJob();
            handler.postDelayed(this, 300);
        }
    };

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

        seenDone = SpeechService.doneSeq();
        seenError = SpeechService.errorSeq();

        buildUi();
        setupPlayer();
        refreshAll();

        if (getIntent().getBooleanExtra(EXTRA_FRESH, false) && store.getAutoTranscribe()
                && transcript == null && Models.anyInstalled(this) && !SpeechService.busy()) {
            SpeechService.startTranscribe(this, rec.id);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rec != null) handler.post(jobTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(jobTick);
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
        mainScroll = scroll;
        scroll.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN || ev.getAction() == MotionEvent.ACTION_MOVE) lastUserScroll = SystemClock.uptimeMillis();
            return false;
        });
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

        liveCaption = new TextView(this);
        liveCaption.setTextSize(19);
        liveCaption.setLineSpacing(0, 1.18f);
        liveCaption.setGravity(Gravity.CENTER);
        liveCaption.setMinLines(2);
        liveCaption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        liveCaption.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        liveCaption.setTextColor(c(R.attr.cTextPrimary));
        liveCaption.setVisibility(View.GONE);
        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capLp.setMargins(dp(24), dp(16), dp(24), dp(4));
        content.addView(liveCaption, capLp);

        markersCard = new LinearLayout(this);
        markersCard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.setMargins(dp(16), 0, dp(16), 0);
        Ui.groupTitle(markersCard, R.string.markers_title);
        markersBox = Ui.card(this);
        markersCard.addView(markersBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(markersCard, mlp);

        LinearLayout transcriptCard = new LinearLayout(this);
        transcriptCard.setOrientation(LinearLayout.VERTICAL);
        Ui.groupTitle(transcriptCard, R.string.transcript_title);
        transcriptBox = Ui.card(this);
        transcriptCard.addView(transcriptBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tcl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tcl.setMargins(dp(16), 0, dp(16), 0);
        content.addView(transcriptCard, tcl);

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
        transcript = Repo.loadTranscript(this, rec.id);
        refreshTranscript();
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
                getString(R.string.clean_copy),
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
                            if (SpeechService.busy()) {
                                Toast.makeText(this, R.string.speech_busy, Toast.LENGTH_SHORT).show();
                            } else {
                                pausePlayback();
                                SpeechService.startEnhance(this, rec.id);
                            }
                            break;
                        case 4:
                            if (Repo.duplicate(this, rec, getString(R.string.copy_title, rec.title)) != null) {
                                Toast.makeText(this, R.string.duplicated, Toast.LENGTH_SHORT).show();
                            }
                            break;
                        case 5:
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

    private boolean busyHere() {
        if (rec == null || !SpeechService.busy()) return false;
        int t = SpeechService.type();
        return (t == SpeechService.JOB_TRANSCRIBE || t == SpeechService.JOB_ENHANCE) && rec.id.equals(SpeechService.key());
    }

    private void pollJob() {
        if (rec == null) return;
        if (SpeechService.doneSeq() != seenDone) {
            seenDone = SpeechService.doneSeq();
            if (!SpeechService.doneCancelled()) {
                if (SpeechService.doneType() == SpeechService.JOB_TRANSCRIBE && rec.id.equals(SpeechService.doneKey())) {
                    transcript = Repo.loadTranscript(this, rec.id);
                    lastBusyHere = false;
                    refreshTranscript();
                    Toast.makeText(this, R.string.notif_done_transcribe, Toast.LENGTH_SHORT).show();
                    if (!Cloud.fallbackNote.isEmpty()) {
                        Toast.makeText(this, getString(R.string.cloud_fallback) + "\n" + Cloud.fallbackNote, Toast.LENGTH_LONG).show();
                        Cloud.fallbackNote = "";
                    }
                    if (!Cloud.lastError.isEmpty()) {
                        Toast.makeText(this, getString(R.string.fix_failed) + "\n" + Cloud.lastError, Toast.LENGTH_LONG).show();
                        Cloud.lastError = "";
                    }
                } else if (SpeechService.doneType() == SpeechService.JOB_ENHANCE) {
                    Toast.makeText(this, R.string.notif_done_enhance, Toast.LENGTH_LONG).show();
                    if (!Cloud.enhanceNote.isEmpty()) {
                        Toast.makeText(this, getString(R.string.neural_fallback) + "\n" + Cloud.enhanceNote, Toast.LENGTH_LONG).show();
                        Cloud.enhanceNote = "";
                    }
                }
            }
        }
        if (SpeechService.errorSeq() != seenError) {
            seenError = SpeechService.errorSeq();
            Toast.makeText(this, getString(SpeechService.errorRes()) + "\n" + SpeechService.errorDetail(), Toast.LENGTH_LONG).show();
        }
        boolean busy = busyHere();
        if (busy != lastBusyHere) {
            lastBusyHere = busy;
            refreshTranscript();
        } else if (busy && jobBar != null) {
            float p = SpeechService.progress();
            jobBar.setIndeterminate(p < 0);
            if (p >= 0) jobBar.setProgress(Math.round(p * 100f));
            if (jobPct != null) jobPct.setText(p < 0 ? "" : Math.round(p * 100f) + "%");
        }
    }

    private LinearLayout.LayoutParams boxLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(topDp), dp(16), 0);
        return lp;
    }

    private TextView infoText(int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextSize(14);
        t.setTextColor(c(R.attr.cTextSecondary));
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    private TextView actionTag(int res, Runnable action, boolean primary) {
        TextView t = Ui.tag(this, getString(res));
        if (primary) Ui.setTagState(t, true);
        t.setOnClickListener(v -> action.run());
        return t;
    }

    private void refreshTranscript() {
        if (transcriptBox == null || rec == null) return;
        transcriptBox.removeAllViews();
        segTexts.clear();
        segRows.clear();
        highlighted = -1;
        if (liveCaption != null) {
            liveCaption.setText("");
            liveCaption.setVisibility(View.GONE);
        }
        jobBar = null;
        jobPct = null;

        if (busyHere()) {
            boolean enhancing = SpeechService.type() == SpeechService.JOB_ENHANCE;
            TextView label = infoText(enhancing ? R.string.job_enhance : R.string.job_transcribe);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setTextColor(c(R.attr.cTextPrimary));
            transcriptBox.addView(label, boxLp(16));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            jobBar = Ui.progressBar(this);
            float p = SpeechService.progress();
            jobBar.setIndeterminate(p < 0);
            if (p >= 0) jobBar.setProgress(Math.round(p * 100f));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(6), 1f);
            blp.setMarginEnd(dp(12));
            row.addView(jobBar, blp);
            jobPct = new TextView(this);
            jobPct.setTextSize(13);
            jobPct.setTextColor(c(R.attr.cTextSecondary));
            row.addView(jobPct);
            transcriptBox.addView(row, boxLp(12));

            TextView cancel = actionTag(R.string.cancel, () -> SpeechService.requestCancel(this), false);
            LinearLayout.LayoutParams cl = boxLp(14);
            cl.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            cl.bottomMargin = dp(16);
            transcriptBox.addView(cancel, cl);
            return;
        }

        if (transcript != null && !transcript.isEmpty()) {
            HorizontalScrollView scroller = new HorizontalScrollView(this);
            scroller.setHorizontalScrollBarEnabled(false);
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(dp(12), dp(12), dp(12), dp(10));
            actions.addView(actionTag(R.string.copy_all, this::copyTranscript, true), pillLp());
            actions.addView(actionTag(R.string.summarize, this::summarize, false), pillLp());
            final TextView follow = actionTag(R.string.follow_text, null, false);
            Ui.setTagState(follow, store.getFollow());
            follow.setOnClickListener(v -> {
                boolean on = !store.getFollow();
                store.setFollow(on);
                Ui.setTagState(follow, on);
            });
            actions.addView(follow, pillLp());
            actions.addView(actionTag(R.string.share_text, this::shareTranscript, false), pillLp());
            actions.addView(actionTag(R.string.share_srt, this::shareSrt, false), pillLp());
            actions.addView(actionTag(R.string.redo, this::startTranscribe, false), pillLp());
            TextView del = actionTag(R.string.delete, () -> {
                Repo.deleteTranscript(this, rec.id);
                transcript = null;
                refreshTranscript();
            }, false);
            del.setTextColor(getColor(R.color.bad));
            actions.addView(del, pillLp());
            scroller.addView(actions);
            transcriptBox.addView(scroller);

            if (liveCaption != null) liveCaption.setVisibility(View.VISIBLE);
            for (int i = 0; i < transcript.segs.size(); i++) {
                final int segIndex = i;
                final Transcript.Seg seg = transcript.segs.get(i);
                transcriptBox.addView(Ui.divider(this, 16));
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(16), dp(12), dp(16), dp(12));
                row.setBackgroundResource(R.drawable.bg_row_flat);
                row.setClickable(true);
                row.setFocusable(true);

                TextView time = new TextView(this);
                time.setText(Fmt.duration(seg.startMs));
                time.setTextSize(12);
                time.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                time.setTextColor(c(R.attr.cAccentText));
                time.setPadding(0, dp(3), 0, 0);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT);
                row.addView(time, tl);

                TextView text = new TextView(this);
                text.setText(seg.text);
                text.setTextSize(16);
                text.setLineSpacing(0, 1.12f);
                text.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
                text.setTextColor(c(R.attr.cTextPrimary));
                row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                segTexts.add(text);
                segRows.add(row);

                row.setOnClickListener(v -> seekAndPlay(seg.startMs));
                row.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(this)
                            .setItems(new CharSequence[]{getString(R.string.copy_line), getString(R.string.edit_line)}, (d, which) -> {
                                if (which == 0) {
                                    copyText(seg.text);
                                } else {
                                    Ui.editLines(this, R.string.edit_line, R.string.transcript_title, seg.text, 3, nv -> {
                                        if (nv.isEmpty() || transcript == null || segIndex >= transcript.segs.size()) return;
                                        transcript.segs.set(segIndex, new Transcript.Seg(seg.startMs, seg.endMs, nv));
                                        Repo.saveTranscript(this, rec.id, transcript);
                                        refreshTranscript();
                                    });
                                }
                            })
                            .show();
                    return true;
                });
                transcriptBox.addView(row);
            }
            return;
        }

        if (transcript != null) {
            transcriptBox.addView(infoText(R.string.transcript_none), boxLp(16));
            LinearLayout.LayoutParams lp = boxLp(12);
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.bottomMargin = dp(16);
            transcriptBox.addView(actionTag(R.string.redo, this::startTranscribe, true), lp);
            return;
        }

        boolean ready = Models.anyInstalled(this);
        transcriptBox.addView(infoText(ready ? R.string.transcript_hint : R.string.transcript_setup_hint), boxLp(16));
        LinearLayout.LayoutParams lp = boxLp(12);
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.bottomMargin = dp(16);
        transcriptBox.addView(actionTag(ready ? R.string.transcribe : R.string.speech_setup, this::startTranscribe, true), lp);
    }

    private void startTranscribe() {
        if (SpeechService.busy()) {
            Toast.makeText(this, R.string.speech_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Models.anyInstalled(this)) {
            startActivity(new Intent(this, SpeechActivity.class));
            return;
        }
        pausePlayback();
        SpeechService.startTranscribe(this, rec.id);
    }

    private void seekAndPlay(long ms) {
        if (mp == null || !ready) return;
        mp.seekTo((int) ms);
        updateProgress();
        if (!mp.isPlaying()) togglePlayback();
    }

    private void highlight(long pos) {
        if (transcript == null || segTexts.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < transcript.segs.size() && i < segTexts.size(); i++) {
            Transcript.Seg sg = transcript.segs.get(i);
            if (pos >= sg.startMs && pos < Math.max(sg.endMs, sg.startMs + 300)) {
                idx = i;
                break;
            }
        }
        if (idx == highlighted) return;
        if (highlighted >= 0 && highlighted < segTexts.size()) {
            segTexts.get(highlighted).setTextColor(c(R.attr.cTextPrimary));
            segTexts.get(highlighted).setTypeface(Typeface.DEFAULT);
            segRows.get(highlighted).setBackgroundResource(R.drawable.bg_row_flat);
        }
        if (idx >= 0) {
            int accent = c(R.attr.cAccentText);
            segTexts.get(idx).setTextColor(accent);
            segTexts.get(idx).setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            segRows.get(idx).setBackgroundColor((accent & 0x00FFFFFF) | 0x1F000000);
            if (liveCaption != null) liveCaption.setText(transcript.segs.get(idx).text);
            if (store.getFollow() && mainScroll != null && SystemClock.uptimeMillis() - lastUserScroll > 4000) {
                Rect r = new Rect(0, 0, segRows.get(idx).getWidth(), segRows.get(idx).getHeight());
                mainScroll.offsetDescendantRectToMyCoords(segRows.get(idx), r);
                mainScroll.smoothScrollTo(0, Math.max(0, r.top - mainScroll.getHeight() / 3));
            }
        }
        highlighted = idx;
    }

    private void summarize() {
        if (transcript == null || transcript.isEmpty() || summarizing) return;
        final String key = store.getCloudKey();
        if (key.isEmpty()) {
            startActivity(new Intent(this, SpeechActivity.class));
            return;
        }
        summarizing = true;
        Toast.makeText(this, R.string.summarizing, Toast.LENGTH_SHORT).show();
        final String text = transcript.fullText();
        new Thread(() -> {
            String out;
            boolean ok = true;
            try {
                out = Cloud.summarize(key, text);
            } catch (Exception e) {
                ok = false;
                out = Cloud.explain(e);
            }
            final String res = out;
            final boolean good = ok;
            runOnUiThread(() -> {
                summarizing = false;
                if (isFinishing()) return;
                if (!good) {
                    Toast.makeText(this, res, Toast.LENGTH_LONG).show();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.summarize)
                        .setMessage(res)
                        .setPositiveButton(R.string.copy_all, (d, w) -> copyText(res))
                        .setNegativeButton(android.R.string.ok, null)
                        .show();
            });
        }).start();
    }

    private void copyText(String text) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText(rec.title, text));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void copyTranscript() {
        if (transcript != null) copyText(transcript.fullText());
    }

    private void shareTranscript() {
        if (transcript == null) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, rec.title);
        i.putExtra(Intent.EXTRA_TEXT, transcript.fullText());
        startActivity(Intent.createChooser(i, getString(R.string.share_text)));
    }

    private void shareSrt() {
        if (transcript == null) return;
        try {
            File dir = new File(getCacheDir(), "subtitles");
            dir.mkdirs();
            String safe = rec.title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (safe.isEmpty()) safe = rec.id;
            File f = new File(dir, safe + ".srt");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(transcript.toSrt().getBytes(StandardCharsets.UTF_8));
            }
            android.net.Uri u = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/x-subrip");
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.setClipData(android.content.ClipData.newRawUri(rec.title, u));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.share_srt)));
        } catch (IOException e) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
        }
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
        highlight(pos);
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
