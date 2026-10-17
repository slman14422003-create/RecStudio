package com.rec.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

public final class SpeechService extends Service {

    public static final String ACTION_DOWNLOAD = "app.rec.studio.speech.DOWNLOAD";
    public static final String ACTION_TRANSCRIBE = "app.rec.studio.speech.TRANSCRIBE";
    public static final String ACTION_ENHANCE = "app.rec.studio.speech.ENHANCE";
    public static final String ACTION_CANCEL = "app.rec.studio.speech.CANCEL";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_MODEL = "model";

    public static final int JOB_NONE = 0;
    public static final int JOB_DOWNLOAD = 1;
    public static final int JOB_TRANSCRIBE = 2;
    public static final int JOB_ENHANCE = 3;

    private static final String CHANNEL = "speech_channel";
    private static final int NOTIF_ID = 21;
    private static final int DONE_ID = 22;
    private static final int WORDS_PER_SEGMENT = 12;

    private static volatile int jobType = JOB_NONE;
    private static volatile String jobKey = "";
    private static volatile String jobLabel = "";
    private static volatile float jobProgress = -1f;
    private static volatile boolean cancel;

    private static volatile long doneSeq;
    private static volatile int doneType;
    private static volatile String doneKey = "";
    private static volatile boolean doneCancelled;

    private static volatile long errorSeq;
    private static volatile int errorRes;
    private static volatile String errorDetail = "";

    private static Model cachedModel;
    private static String cachedPath;

    private long lastNotify;

    public static boolean busy() { return jobType != JOB_NONE; }
    public static int type() { return jobType; }
    public static String key() { return jobKey; }
    public static String label() { return jobLabel; }
    public static float progress() { return jobProgress; }
    public static long doneSeq() { return doneSeq; }
    public static int doneType() { return doneType; }
    public static String doneKey() { return doneKey; }
    public static boolean doneCancelled() { return doneCancelled; }
    public static long errorSeq() { return errorSeq; }
    public static int errorRes() { return errorRes; }
    public static String errorDetail() { return errorDetail; }

    public static void startDownload(Context c, int model) {
        Intent i = new Intent(c, SpeechService.class).setAction(ACTION_DOWNLOAD).putExtra(EXTRA_MODEL, model);
        ContextCompat.startForegroundService(c, i);
    }

    public static void startTranscribe(Context c, String id) {
        Intent i = new Intent(c, SpeechService.class).setAction(ACTION_TRANSCRIBE).putExtra(EXTRA_ID, id);
        ContextCompat.startForegroundService(c, i);
    }

    public static void startEnhance(Context c, String id) {
        Intent i = new Intent(c, SpeechService.class).setAction(ACTION_ENHANCE).putExtra(EXTRA_ID, id);
        ContextCompat.startForegroundService(c, i);
    }

    public static void requestCancel(Context c) {
        cancel = true;
        if (busy()) {
            Intent i = new Intent(c, SpeechService.class).setAction(ACTION_CANCEL);
            c.startService(i);
        }
    }

    public static synchronized void releaseModel() {
        if (cachedModel != null && jobType == JOB_NONE) {
            try {
                cachedModel.close();
            } catch (Exception ignored) {
            }
            cachedModel = null;
            cachedPath = null;
        }
    }

    private static synchronized Model model(String path) throws IOException {
        if (cachedModel != null && path.equals(cachedPath)) return cachedModel;
        if (cachedModel != null) {
            try {
                cachedModel.close();
            } catch (Exception ignored) {
            }
            cachedModel = null;
        }
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        cachedModel = new Model(path);
        cachedPath = path;
        return cachedModel;
    }

    @Override
    public void onCreate() {
        super.onCreate();
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
            if (!busy()) stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(action)) {
            cancel = true;
            return START_NOT_STICKY;
        }

        final int type;
        final String key;
        final int modelIndex = intent.getIntExtra(EXTRA_MODEL, -1);
        String id = intent.getStringExtra(EXTRA_ID);
        if (ACTION_DOWNLOAD.equals(action)) {
            type = JOB_DOWNLOAD;
            key = String.valueOf(modelIndex);
        } else if (ACTION_TRANSCRIBE.equals(action)) {
            type = JOB_TRANSCRIBE;
            key = id == null ? "" : id;
        } else if (ACTION_ENHANCE.equals(action)) {
            type = JOB_ENHANCE;
            key = id == null ? "" : id;
        } else {
            return START_NOT_STICKY;
        }

        if (busy()) {
            enterForeground(jobType, jobLabel, jobProgress);
            return START_NOT_STICKY;
        }

        String label = "";
        if (type == JOB_DOWNLOAD) {
            String[] names = getResources().getStringArray(R.array.speech_model_names);
            if (modelIndex >= 0 && modelIndex < names.length) label = names[modelIndex];
        } else {
            Recording r = Repo.get(this, key);
            if (r != null) label = r.title;
        }
        jobType = type;
        jobKey = key;
        jobLabel = label;
        jobProgress = -1f;
        cancel = false;
        enterForeground(type, label, -1f);

        final String fKey = key;
        new Thread(() -> run(type, fKey, modelIndex), "speech-job").start();
        return START_NOT_STICKY;
    }

    private void enterForeground(int type, String label, float p) {
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(type, label, p),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (RuntimeException ignored) {
        }
    }

    private void run(int type, String key, int modelIndex) {
        boolean success = false;
        try {
            if (type == JOB_DOWNLOAD) download(modelIndex);
            else if (type == JOB_TRANSCRIBE) transcribe(key);
            else enhanceCopy(key);
            doneType = type;
            doneKey = type == JOB_ENHANCE ? lastCreatedId : key;
            doneCancelled = false;
            success = true;
            doneSeq++;
        } catch (Models.CancelledException e) {
            doneType = type;
            doneKey = key;
            doneCancelled = true;
            doneSeq++;
        } catch (Throwable e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            errorDetail = detail;
            if (type == JOB_DOWNLOAD && e instanceof IOException) errorRes = R.string.err_download;
            else if (e instanceof UnsatisfiedLinkError || e instanceof OutOfMemoryError) errorRes = R.string.err_engine;
            else errorRes = R.string.err_generic;
            errorSeq++;
        } finally {
            final int finishedType = type;
            final String finishedLabel = jobLabel;
            final String finishedKey = type == JOB_ENHANCE ? lastCreatedId : key;
            jobType = JOB_NONE;
            jobProgress = -1f;
            if (success) postDone(finishedType, finishedLabel, finishedKey);
            stopForegroundCompat();
            stopSelf();
        }
    }

    private String lastCreatedId = "";

    private void setProgress(float p) {
        jobProgress = p;
        long now = System.currentTimeMillis();
        if (now - lastNotify > 700) {
            lastNotify = now;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(jobType, jobLabel, p));
        }
    }

    private void download(int modelIndex) throws IOException {
        if (modelIndex < 0 || modelIndex >= Models.ALL.length) throw new IOException("bad model");
        Models.download(this, Models.ALL[modelIndex], this::setProgress, () -> cancel);
        Store store = new Store(this);
        int cur = store.getSpeechModel();
        if (cur < 0 || cur >= Models.ALL.length || !Models.installed(this, Models.ALL[cur])) store.setSpeechModel(modelIndex);
        synchronized (SpeechService.class) {
            if (cachedModel != null) {
                try {
                    cachedModel.close();
                } catch (Exception ignored) {
                }
                cachedModel = null;
                cachedPath = null;
            }
        }
    }

    private static final class Pipe {
        final Voice16k conv = new Voice16k();
        final Enhancer enh;
        float[] mono = new float[4096];
        float[] clean = new float[4096 + 2 * Enhancer.N];
        short[] pcm = new short[8192];

        Pipe(Enhancer enh) {
            this.enh = enh;
        }

        int feed(short[] data, int n, int rate, int ch) {
            int need = (n / ch) * Voice16k.RATE / rate + 8;
            if (mono.length < need) mono = new float[need];
            int m = conv.convert(data, n, rate, ch, mono);
            return finish(m, true);
        }

        int flush() {
            if (enh == null) return 0;
            if (clean.length < 3 * Enhancer.N) clean = new float[3 * Enhancer.N];
            int c = enh.flush(clean);
            return toPcm(clean, c);
        }

        private int finish(int m, boolean fromMono) {
            float[] src = mono;
            int cnt = m;
            if (enh != null) {
                if (clean.length < m + 2 * Enhancer.N) clean = new float[m + 2 * Enhancer.N];
                cnt = enh.process(mono, m, clean);
                src = clean;
            }
            return toPcm(src, cnt);
        }

        private int toPcm(float[] src, int cnt) {
            if (pcm.length < cnt) pcm = new short[cnt];
            for (int i = 0; i < cnt; i++) {
                float v = src[i] * 32767f;
                if (v > 32767f) v = 32767f;
                else if (v < -32768f) v = -32768f;
                pcm[i] = (short) v;
            }
            return cnt;
        }
    }

    private void transcribe(String id) throws Exception {
        Store store = new Store(this);
        Recording rec = Repo.get(this, id);
        if (rec == null) throw new IOException("missing recording");
        int mi = Models.currentIndex(this, store);
        if (mi < 0) throw new IOException("no speech model");
        Models.Spec spec = Models.ALL[mi];
        Model model = model(Models.dir(this, spec).getAbsolutePath());

        File audio = rec.file(this);
        long totalUs = PcmDecoder.durationUs(audio);
        if (totalUs <= 0) totalUs = Math.max(1, rec.durationMs) * 1000L;
        final long total = totalUs;

        Transcript t = new Transcript();
        t.lang = spec.lang;
        final Pipe pipe = new Pipe(store.getClean() ? new Enhancer(store.getCleanLevel(), true) : null);
        final long[] lastEnd = {0};

        try (Recognizer rcg = new Recognizer(model, 16000f)) {
            rcg.setWords(true);
            PcmDecoder.decode(audio, (data, n, rate, ch, pts) -> {
                int c = pipe.feed(data, n, rate, ch);
                feedRecognizer(rcg, pipe.pcm, c, t, lastEnd);
                setProgress(Math.min(0.99f, pts / (float) total));
            }, () -> cancel);
            int c = pipe.flush();
            feedRecognizer(rcg, pipe.pcm, c, t, lastEnd);
            collect(t, rcg.getFinalResult(), lastEnd);
        }
        Repo.saveTranscript(this, id, t);
        setProgress(1f);
    }

    private void feedRecognizer(Recognizer r, short[] pcm, int n, Transcript t, long[] lastEnd) {
        if (n <= 0) return;
        byte[] b = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            b[2 * i] = (byte) (pcm[i] & 0xff);
            b[2 * i + 1] = (byte) ((pcm[i] >> 8) & 0xff);
        }
        if (r.acceptWaveForm(b, b.length)) collect(t, r.getResult(), lastEnd);
    }

    private void collect(Transcript t, String json, long[] lastEnd) {
        try {
            JSONObject o = new JSONObject(json);
            String text = o.optString("text", "").trim();
            if (text.isEmpty()) return;
            JSONArray w = o.optJSONArray("result");
            if (w == null || w.length() == 0) {
                t.segs.add(new Transcript.Seg(lastEnd[0], lastEnd[0], Transcript.polish(text)));
                return;
            }
            int i = 0;
            while (i < w.length()) {
                int j = Math.min(w.length(), i + WORDS_PER_SEGMENT);
                StringBuilder sb = new StringBuilder();
                for (int k = i; k < j; k++) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(w.getJSONObject(k).getString("word"));
                }
                long st = (long) (w.getJSONObject(i).getDouble("start") * 1000);
                long en = (long) (w.getJSONObject(j - 1).getDouble("end") * 1000);
                String s = sb.toString();
                if (i == 0 && !s.isEmpty() && Character.isLetter(s.charAt(0))) s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
                if (j == w.length()) s = s + ".";
                t.segs.add(new Transcript.Seg(st, en, s));
                lastEnd[0] = en;
                i = j;
            }
        } catch (JSONException ignored) {
        }
    }

    private void enhanceCopy(String id) throws Exception {
        Store store = new Store(this);
        Recording rec = Repo.get(this, id);
        if (rec == null) throw new IOException("missing recording");
        File audio = rec.file(this);
        long totalUs = PcmDecoder.durationUs(audio);
        if (totalUs <= 0) totalUs = Math.max(1, rec.durationMs) * 1000L;
        final long total = totalUs;

        final Pipe pipe = new Pipe(new Enhancer(store.getCleanLevel(), true));
        String newId = Repo.newId();
        File out = Repo.audio(this, newId);
        final ArrayList<Float> levels = new ArrayList<>();
        final float[] peak = {0f};
        final int[] counter = {0};
        final int chunk = Voice16k.RATE / 20;
        boolean ok = false;
        AacWriter writer = new AacWriter(out, Voice16k.RATE, 1, 48000);
        try (writer) {
            PcmDecoder.decode(audio, (data, n, rate, ch, pts) -> {
                int c = pipe.feed(data, n, rate, ch);
                writer.write(pipe.pcm, c);
                track(pipe.pcm, c, levels, peak, counter, chunk);
                setProgress(Math.min(0.99f, pts / (float) total));
            }, () -> cancel);
            int c = pipe.flush();
            writer.write(pipe.pcm, c);
            track(pipe.pcm, c, levels, peak, counter, chunk);
            ok = true;
        } finally {
            if (!ok) out.delete();
        }

        Recording copy = new Recording(newId, getString(R.string.clean_copy_title, rec.title), System.currentTimeMillis());
        copy.durationMs = writer.frames() * 1000L / Voice16k.RATE;
        copy.quality = rec.quality;
        copy.mode = rec.mode;
        copy.label = rec.label;
        copy.markers.addAll(rec.markers);
        copy.peaks = peaksFrom(levels);
        Repo.save(this, copy);
        lastCreatedId = newId;
        setProgress(1f);
    }

    private static void track(short[] pcm, int n, ArrayList<Float> levels, float[] peak, int[] counter, int chunk) {
        for (int i = 0; i < n; i++) {
            float a = Math.abs((int) pcm[i]) / 32768f;
            if (a > peak[0]) peak[0] = a;
            if (++counter[0] >= chunk) {
                levels.add(toLevel(peak[0]));
                peak[0] = 0f;
                counter[0] = 0;
            }
        }
    }

    private static float toLevel(float peak) {
        if (peak <= 0f) return 0f;
        double db = 20.0 * Math.log10(peak);
        return (float) Math.max(0.0, Math.min(1.0, (db + 56.0) / 56.0));
    }

    private static int[] peaksFrom(ArrayList<Float> levels) {
        int count = levels.size();
        if (count == 0) return new int[0];
        int bars = Math.min(240, count);
        int[] out = new int[bars];
        for (int i = 0; i < bars; i++) {
            int from = (int) ((long) i * count / bars);
            int to = (int) ((long) (i + 1) * count / bars);
            if (to <= from) to = from + 1;
            float m = 0f;
            for (int k = from; k < to && k < count; k++) m = Math.max(m, levels.get(k));
            out[i] = Math.round(m * 100f);
        }
        return out;
    }

    private void stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    }

    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.notif_speech_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(int type, String label, float p) {
        int titleRes = type == JOB_DOWNLOAD ? R.string.job_download : (type == JOB_TRANSCRIBE ? R.string.job_transcribe : R.string.job_enhance);
        Intent cancelIntent = new Intent(this, SpeechService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelPi = PendingIntent.getService(this, 3, cancelIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(titleRes))
                .setContentText(label)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setColor(getColor(R.color.accent))
                .addAction(new NotificationCompat.Action.Builder(0, getString(R.string.cancel), cancelPi).build());
        if (p < 0f) b.setProgress(0, 0, true);
        else b.setProgress(100, Math.round(p * 100f), false);
        return b.build();
    }

    private void postDone(int type, String label, String key) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        int titleRes = type == JOB_DOWNLOAD ? R.string.notif_done_download : (type == JOB_TRANSCRIBE ? R.string.notif_done_transcribe : R.string.notif_done_enhance);
        PendingIntent content;
        if (type == JOB_DOWNLOAD) {
            Intent i = new Intent(this, SpeechActivity.class);
            content = PendingIntent.getActivity(this, 4, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            Intent i = new Intent(this, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_ID, key);
            content = PendingIntent.getActivity(this, 5, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(titleRes))
                .setContentText(label)
                .setContentIntent(content)
                .setAutoCancel(true)
                .setColor(getColor(R.color.accent))
                .build();
        nm.notify(DONE_ID, n);
    }
}
