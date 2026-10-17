package com.rec.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

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
    private static final int WATCHDOG_MS = 250;
    private static final int PEAK_BARS = 240;
    private static final long MIN_DURATION_MS = 500;
    private static final long CODEC_TIMEOUT_US = 10000;
    private static final long JOIN_TIMEOUT_MS = 6000;

    private static RecorderService instance;
    private static String lastSavedId;
    private static volatile boolean startFailed;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Long> markers = new ArrayList<>();
    private float[] levels = new float[2048];
    private int levelCount;

    private Session session;
    private Thread worker;
    private volatile boolean stopRequested;
    private volatile boolean paused;
    private volatile boolean workerFailed;
    private volatile long framesWritten;
    private volatile float lastLevel;
    private volatile long clipUntil;

    private String id;
    private int state = IDLE;
    private int quality;
    private int mode;
    private long createdAt;
    private boolean noiseApplied;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (state != IDLE && worker != null && !worker.isAlive()) {
                finish();
                return;
            }
            if (state != IDLE) handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    private static final class Session {
        AudioRecord record;
        MediaCodec codec;
        MediaMuxer muxer;
        NoiseSuppressor suppressor;
        AutomaticGainControl agc;
        int channels;
        int rate;
        int track = -1;
        boolean muxerStarted;
        boolean codecStarted;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    }

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

    public int mode() { return mode; }

    public boolean noiseReduction() { return noiseApplied; }

    public float lastLevel() { return state == RECORDING ? lastLevel : 0f; }

    public boolean isClipping() { return SystemClock.uptimeMillis() < clipUntil; }

    public long elapsedMs() {
        Session s = session;
        int rate = s == null ? 0 : s.rate;
        if (rate <= 0) return 0;
        return framesWritten * 1000L / rate;
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
        handler.removeCallbacks(watchdog);
        if (worker != null && worker.isAlive()) {
            stopRequested = true;
            try {
                worker.join(JOIN_TIMEOUT_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
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
        Store store = new Store(this);
        quality = store.getQuality();
        mode = store.getMode();
        id = Repo.newId();
        createdAt = System.currentTimeMillis();
        File out = Repo.audio(this, id);

        int source = MediaRecorder.AudioSource.MIC;
        int channels = 1;
        int rate = Store.sampleRate(quality);
        int bits = Store.effectiveBitRate(quality, mode);
        if (mode == Store.M_INTERVIEW) {
            channels = 2;
        } else if (mode == Store.M_SPEECH) {
            source = MediaRecorder.AudioSource.VOICE_RECOGNITION;
            rate = 16000;
        }

        final int focus = store.getFocus();
        boolean noise = store.getNoise();
        boolean agc = store.getAgc();
        if (focus > 0) {
            noise = true;
            agc = true;
            if (channels == 1) source = MediaRecorder.AudioSource.VOICE_RECOGNITION;
        }
        Session s = open(out, source, channels, rate, bits, noise, agc);
        if (s == null && channels == 2) s = open(out, source, 1, rate, bits / 2, noise, agc);
        if (s == null) s = open(out, MediaRecorder.AudioSource.MIC, 1, 44100, 128000, noise, agc);
        if (s == null) {
            out.delete();
            return false;
        }

        session = s;
        noiseApplied = s.suppressor != null || s.agc != null;
        markers.clear();
        levelCount = 0;
        framesWritten = 0;
        lastLevel = 0f;
        clipUntil = 0;
        stopRequested = false;
        paused = false;
        workerFailed = false;
        state = RECORDING;

        final float gain = (float) Math.pow(10.0, Store.gainDb(store.getGainIndex()) / 20.0);
        final boolean lowCut = store.getLowCut();
        final Session running = s;
        worker = new Thread(() -> capture(running, gain, lowCut, focus), "rec-capture");
        worker.start();
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_MS);
        updateNotification();
        return true;
    }

    private Session open(File out, int source, int channels, int rate, int bits, boolean noise, boolean agc) {
        Session s = new Session();
        s.channels = channels;
        s.rate = rate;
        try {
            int channelCfg = channels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
            int minBuf = AudioRecord.getMinBufferSize(rate, channelCfg, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) return null;
            int chunkBytes = (rate / 20) * channels * 2;
            int bufBytes = Math.max(minBuf * 2, chunkBytes * 4);
            s.record = new AudioRecord(source, rate, channelCfg, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
            if (s.record.getState() != AudioRecord.STATE_INITIALIZED) {
                release(s);
                return null;
            }
            int sid = s.record.getAudioSessionId();
            if (noise && NoiseSuppressor.isAvailable()) {
                s.suppressor = NoiseSuppressor.create(sid);
                if (s.suppressor != null) s.suppressor.setEnabled(true);
            }
            if (agc && AutomaticGainControl.isAvailable()) {
                s.agc = AutomaticGainControl.create(sid);
                if (s.agc != null) s.agc.setEnabled(true);
            }

            MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bits);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768);
            s.codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            s.codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            s.muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            return s;
        } catch (IOException | RuntimeException e) {
            release(s);
            out.delete();
            return null;
        }
    }

    private void capture(Session s, float gain, boolean lowCut, int focus) {
        int ch = s.channels;
        short[] buf = new short[(s.rate / 20) * ch];
        float[] px = new float[ch];
        float[] py = new float[ch];
        double rc = 1.0 / (2.0 * Math.PI * 80.0);
        float alpha = (float) (rc / (rc + 1.0 / s.rate));
        LiveVoice voice = focus > 0 ? new LiveVoice(s.rate, ch, focus) : null;
        boolean recording = false;
        long framesFed = 0;
        boolean ok = true;
        try {
            s.codec.start();
            s.codecStarted = true;
            while (!stopRequested) {
                if (paused) {
                    if (recording) {
                        stopQuietly(s.record);
                        recording = false;
                    }
                    SystemClock.sleep(20);
                    continue;
                }
                if (!recording) {
                    s.record.startRecording();
                    recording = true;
                }
                int n = s.record.read(buf, 0, buf.length);
                if (n < 0) {
                    ok = false;
                    break;
                }
                if (n == 0) continue;
                int peak = process(buf, n, ch, gain, lowCut, alpha, px, py);
                if (voice != null) peak = voice.process(buf, n);
                framesFed = feed(s, buf, n, framesFed);
                framesWritten = framesFed;
                final float level = toLevel(peak);
                lastLevel = level;
                if (peak >= 32000) clipUntil = SystemClock.uptimeMillis() + 800;
                handler.post(() -> pushLevel(level));
            }
            if (recording) stopQuietly(s.record);
            if (ok) finishEncoder(s, framesFed);
        } catch (RuntimeException | IOException e) {
            ok = false;
        } finally {
            release(s);
            if (!ok) workerFailed = true;
        }
    }

    private static int process(short[] b, int n, int ch, float gain, boolean lowCut, float a, float[] px, float[] py) {
        int peak = 0;
        for (int i = 0; i < n; i++) {
            int c = i % ch;
            float x = b[i];
            if (lowCut) {
                float y = a * (py[c] + x - px[c]);
                px[c] = x;
                py[c] = y;
                x = y;
            }
            x *= gain;
            if (x > 32767f) x = 32767f;
            else if (x < -32768f) x = -32768f;
            short v = (short) x;
            b[i] = v;
            int abs = Math.abs((int) v);
            if (abs > peak) peak = abs;
        }
        return peak;
    }

    private long feed(Session s, short[] buf, int n, long framesFed) throws IOException {
        int offset = 0;
        int guard = 0;
        while (offset < n) {
            int idx = s.codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
            if (idx < 0) {
                drain(s, false);
                if (++guard > 400) throw new IOException("encoder stalled");
                continue;
            }
            ByteBuffer ib = s.codec.getInputBuffer(idx);
            if (ib == null) throw new IOException("no input buffer");
            ib.clear();
            ib.order(ByteOrder.nativeOrder());
            int count = Math.min(n - offset, ib.capacity() / 2);
            count -= count % s.channels;
            if (count <= 0) throw new IOException("input buffer too small");
            ib.asShortBuffer().put(buf, offset, count);
            long pts = framesFed * 1000000L / s.rate;
            s.codec.queueInputBuffer(idx, 0, count * 2, pts, 0);
            framesFed += count / s.channels;
            offset += count;
            drain(s, false);
        }
        return framesFed;
    }

    private void finishEncoder(Session s, long framesFed) throws IOException {
        int guard = 0;
        int idx;
        while ((idx = s.codec.dequeueInputBuffer(CODEC_TIMEOUT_US)) < 0) {
            drain(s, false);
            if (++guard > 400) throw new IOException("encoder stalled");
        }
        long pts = framesFed * 1000000L / s.rate;
        s.codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        drain(s, true);
    }

    private void drain(Session s, boolean untilEos) throws IOException {
        int tries = 0;
        while (true) {
            int idx = s.codec.dequeueOutputBuffer(s.info, untilEos ? CODEC_TIMEOUT_US : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos) return;
                if (++tries > 300) throw new IOException("encoder did not finish");
                continue;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                s.track = s.muxer.addTrack(s.codec.getOutputFormat());
                s.muxer.start();
                s.muxerStarted = true;
                continue;
            }
            if (idx < 0) continue;
            ByteBuffer ob = s.codec.getOutputBuffer(idx);
            if ((s.info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) s.info.size = 0;
            if (s.info.size > 0 && ob != null && s.muxerStarted) {
                ob.position(s.info.offset);
                ob.limit(s.info.offset + s.info.size);
                s.muxer.writeSampleData(s.track, ob, s.info);
            }
            boolean eos = (s.info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            s.codec.releaseOutputBuffer(idx, false);
            if (eos) return;
        }
    }

    private static void stopQuietly(AudioRecord r) {
        try {
            r.stop();
        } catch (IllegalStateException ignored) {
        }
    }

    private static void release(Session s) {
        if (s.suppressor != null) {
            try {
                s.suppressor.release();
            } catch (RuntimeException ignored) {
            }
            s.suppressor = null;
        }
        if (s.agc != null) {
            try {
                s.agc.release();
            } catch (RuntimeException ignored) {
            }
            s.agc = null;
        }
        if (s.record != null) {
            try {
                s.record.release();
            } catch (RuntimeException ignored) {
            }
            s.record = null;
        }
        if (s.codec != null) {
            try {
                if (s.codecStarted) s.codec.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                s.codec.release();
            } catch (RuntimeException ignored) {
            }
            s.codec = null;
        }
        if (s.muxer != null) {
            try {
                if (s.muxerStarted) s.muxer.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                s.muxer.release();
            } catch (RuntimeException ignored) {
            }
            s.muxer = null;
        }
    }

    private void cleanupFailed() {
        state = IDLE;
        stopForegroundCompat();
        stopSelf();
    }

    public boolean pause() {
        if (state != RECORDING) return false;
        paused = true;
        state = PAUSED;
        updateNotification();
        return true;
    }

    public boolean resume() {
        if (state != PAUSED) return false;
        paused = false;
        state = RECORDING;
        updateNotification();
        return true;
    }

    public boolean mark() {
        if (state == IDLE) return false;
        markers.add(elapsedMs());
        return true;
    }

    private void joinWorker() {
        stopRequested = true;
        if (worker != null) {
            try {
                worker.join(JOIN_TIMEOUT_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public String finish() {
        if (state == IDLE) return null;
        handler.removeCallbacks(watchdog);
        joinWorker();
        long duration = elapsedMs();
        boolean ok = !workerFailed && (worker == null || !worker.isAlive());
        state = IDLE;
        String savedId = null;
        File file = Repo.audio(this, id);
        if (ok && duration >= MIN_DURATION_MS && file.length() > 0) {
            Store store = new Store(this);
            Recording r = new Recording(id, getString(R.string.default_title) + " " + store.nextIndex(), createdAt);
            r.durationMs = duration;
            r.quality = quality;
            r.mode = mode;
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
        handler.removeCallbacks(watchdog);
        joinWorker();
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
        if (state == IDLE) return;
        if (levelCount == levels.length) {
            float[] bigger = new float[levels.length * 2];
            System.arraycopy(levels, 0, bigger, 0, levelCount);
            levels = bigger;
        }
        levels[levelCount++] = v;
    }

    private static float toLevel(int peak) {
        if (peak <= 0) return 0f;
        double db = 20.0 * Math.log10(peak / 32768.0);
        double v = (db + 56.0) / 56.0;
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
        boolean isPaused = st == PAUSED;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(isPaused ? R.string.notif_paused : R.string.notif_recording))
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
                getString(isPaused ? R.string.action_resume : R.string.action_pause),
                actionIntent(isPaused ? ACTION_RESUME : ACTION_PAUSE, 1)).build());
        b.addAction(new NotificationCompat.Action.Builder(0, getString(R.string.action_stop), actionIntent(ACTION_STOP, 2)).build());
        return b.build();
    }
}
