package com.rec.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.UUID;

public final class RecorderService extends Service {

    public static final String ACTION_START = "app.rec.studio.action.START";
    public static final String ACTION_PAUSE = "app.rec.studio.action.PAUSE";
    public static final String ACTION_RESUME = "app.rec.studio.action.RESUME";
    public static final String ACTION_STOP = "app.rec.studio.action.STOP";
    public static final String ACTION_MARK = "app.rec.studio.action.MARK";

    public static final int IDLE = 0;
    public static final int RECORDING = 1;
    public static final int PAUSED = 2;

    private static final String CHANNEL = "rec_channel";
    private static final int NOTIF_ID = 7;
    private static final int SAMPLE_MS = 50;
    private static final int PEAK_BARS = 240;
    private static final long MIN_DURATION_MS = 500;

    private static RecorderService instance;
    private static String lastSavedId;
    private static volatile boolean startFailed;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Long> markers = new ArrayList<>();
    private float[] levels = new float[2048];
    private int levelCount;

    private MediaRecorder recorder;
    private String id;
    private int state = IDLE;
    private int quality;
    private long accumulated;
    private long segmentStart;
    private long createdAt;

    private final Runnable sampler = new Runnable() {
        @Override
        public void run() {
            if (recorder != null && state == RECORDING) {
                int amp = 0;
                try {
                    amp = recorder.getMaxAmplitude();
                } catch (RuntimeException ignored) {
                }
                pushLevel(toLevel(amp));
            }
            if (state != IDLE) handler.postDelayed(this, SAMPLE_MS);
        }
    };

    public static RecorderService get() {
        return instance;
    }

    public static boolean isActive() {
        return instance != null && instance.state != IDLE;
    }

    public static String takeSaved() {
        String s = lastSavedId;
        lastSavedId = null;
        return s;
    }

    public static boolean hasStartFailed() {
        return startFailed;
    }

    public static void resetStartFailed() {
        startFailed = false;
    }

    public static void start(Context c) {
        startFailed = false;
        Intent i = new Intent(c, RecorderService.class).setAction(ACTION_START);
        ContextCompat.startForegroundService(c, i);
    }

    public int state() { return state; }

    public int quality() { return quality; }

    public long elapsedMs() {
        if (state == RECORDING) return accumulated + (System.currentTimeMillis() - segmentStart);
        return accumulated;
    }

    public int levelCount() { return levelCount; }

    public float levelAt(int i) { return levels[i]; }

    public int markerCount() { return markers.size(); }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ensureChannel(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            if (state == IDLE) stopSelf();
            return START_NOT_STICKY;
        }
        switch (action) {
            case ACTION_START:
                if (state == IDLE) {
                    if (!enterForeground() || !begin()) {
                        startFailed = true;
                        cleanupFailed();
                    }
                }
                break;
            case ACTION_PAUSE:
                pause();
                break;
            case ACTION_RESUME:
                resume();
                break;
            case ACTION_STOP:
                finish();
                break;
            case ACTION_MARK:
                mark();
                break;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(sampler);
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
            }
            recorder.release();
            recorder = null;
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private boolean enterForeground() {
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(RECORDING, 0),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean begin() {
        quality = new Store(this).getQuality();
        id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        createdAt = System.currentTimeMillis();
        File out = Repo.audio(this, id);
        if (!prepare(out, Store.sampleRate(quality), Store.bitRate(quality))) {
            if (!prepare(out, 44100, 128000)) return false;
            quality = Store.Q_HIGH;
        }
        try {
            recorder.start();
        } catch (RuntimeException e) {
            releaseRecorder();
            out.delete();
            return false;
        }
        markers.clear();
        levelCount = 0;
        accumulated = 0;
        segmentStart = System.currentTimeMillis();
        state = RECORDING;
        handler.removeCallbacks(sampler);
        handler.postDelayed(sampler, SAMPLE_MS);
        updateNotification();
        return true;
    }

    private boolean prepare(File out, int rate, int bits) {
        releaseRecorder();
        try {
            MediaRecorder r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder = r;
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioChannels(1);
            r.setAudioSamplingRate(rate);
            r.setAudioEncodingBitRate(bits);
            r.setOutputFile(out.getAbsolutePath());
            r.setOnErrorListener((mr, what, extra) -> handler.post(this::finish));
            r.prepare();
            return true;
        } catch (Exception e) {
            releaseRecorder();
            out.delete();
            return false;
        }
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try {
                recorder.release();
            } catch (RuntimeException ignored) {
            }
            recorder = null;
        }
    }

    private void cleanupFailed() {
        releaseRecorder();
        state = IDLE;
        stopForegroundCompat();
        stopSelf();
    }

    public boolean pause() {
        if (state != RECORDING || recorder == null) return false;
        try {
            recorder.pause();
        } catch (RuntimeException e) {
            return false;
        }
        accumulated += System.currentTimeMillis() - segmentStart;
        state = PAUSED;
        updateNotification();
        return true;
    }

    public boolean resume() {
        if (state != PAUSED || recorder == null) return false;
        try {
            recorder.resume();
        } catch (RuntimeException e) {
            return false;
        }
        segmentStart = System.currentTimeMillis();
        state = RECORDING;
        updateNotification();
        return true;
    }

    public boolean mark() {
        if (state == IDLE) return false;
        markers.add(elapsedMs());
        return true;
    }

    public String finish() {
        if (state == IDLE || recorder == null) return null;
        long duration = elapsedMs();
        handler.removeCallbacks(sampler);
        boolean ok = true;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            ok = false;
        }
        releaseRecorder();
        state = IDLE;
        String savedId = null;
        if (ok && duration >= MIN_DURATION_MS) {
            Store store = new Store(this);
            Recording r = new Recording(id, getString(R.string.default_title) + " " + store.nextIndex(), createdAt);
            r.durationMs = duration;
            r.quality = quality;
            r.markers.addAll(markers);
            r.peaks = downsample();
            Repo.save(this, r);
            savedId = id;
        } else {
            Repo.delete(this, id);
        }
        lastSavedId = savedId;
        stopForegroundCompat();
        stopSelf();
        return savedId;
    }

    public void discard() {
        if (state == IDLE) return;
        handler.removeCallbacks(sampler);
        try {
            if (recorder != null) recorder.stop();
        } catch (RuntimeException ignored) {
        }
        releaseRecorder();
        state = IDLE;
        Repo.delete(this, id);
        lastSavedId = null;
        stopForegroundCompat();
        stopSelf();
    }

    private void stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    }

    private void pushLevel(float v) {
        if (levelCount == levels.length) {
            float[] bigger = new float[levels.length * 2];
            System.arraycopy(levels, 0, bigger, 0, levelCount);
            levels = bigger;
        }
        levels[levelCount++] = v;
    }

    private static float toLevel(int amp) {
        if (amp <= 0) return 0f;
        double db = 20.0 * Math.log10(amp / 32767.0);
        double v = (db + 52.0) / 52.0;
        return (float) Math.max(0.0, Math.min(1.0, v));
    }

    private int[] downsample() {
        if (levelCount == 0) return new int[0];
        int bars = Math.min(PEAK_BARS, levelCount);
        int[] out = new int[bars];
        for (int i = 0; i < bars; i++) {
            int from = (int) ((long) i * levelCount / bars);
            int to = (int) ((long) (i + 1) * levelCount / bars);
            if (to <= from) to = from + 1;
            float m = 0f;
            for (int k = from; k < to && k < levelCount; k++) m = Math.max(m, levels[k]);
            out[i] = Math.round(m * 100f);
        }
        return out;
    }

    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(state, elapsedMs()));
    }

    private PendingIntent actionIntent(String action, int code) {
        Intent i = new Intent(this, RecorderService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification(int st, long elapsed) {
        Intent open = new Intent(this, RecordActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean paused = st == PAUSED;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(paused ? R.string.notif_paused : R.string.notif_recording))
                .setContentText(getString(R.string.notif_tap))
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(getColor(R.color.accent));
        if (st == RECORDING) {
            b.setShowWhen(true).setUsesChronometer(true).setWhen(System.currentTimeMillis() - elapsed);
        } else {
            b.setShowWhen(false);
        }
        b.addAction(new NotificationCompat.Action.Builder(0,
                getString(paused ? R.string.action_resume : R.string.action_pause),
                actionIntent(paused ? ACTION_RESUME : ACTION_PAUSE, 1)).build());
        b.addAction(new NotificationCompat.Action.Builder(0, getString(R.string.action_stop), actionIntent(ACTION_STOP, 2)).build());
        return b.build();
    }
}
