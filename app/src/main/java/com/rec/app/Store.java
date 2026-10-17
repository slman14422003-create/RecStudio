package com.rec.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class Store {

    public static final int Q_STANDARD = 0;
    public static final int Q_HIGH = 1;
    public static final int Q_MAX = 2;

    public static final int M_STANDARD = 0;
    public static final int M_INTERVIEW = 1;
    public static final int M_SPEECH = 2;

    public static final int SORT_NEWEST = 0;
    public static final int SORT_OLDEST = 1;
    public static final int SORT_LONGEST = 2;
    public static final int SORT_NAME = 3;

    private static final String PREFS = "rec_prefs";
    private static final String K_HAPTIC = "haptic";
    private static final String K_KEEP_AWAKE = "keep_awake";
    private static final String K_QUALITY = "quality";
    private static final String K_MODE = "mode";
    private static final String K_SORT = "sort";
    private static final String K_SKIP = "skip_seconds";
    private static final String K_SPEED = "speed";
    private static final String K_INDEX = "title_index";
    private static final String K_NOTIF_ASKED = "notif_asked";
    private static final String K_NOISE = "noise_reduction";
    private static final String K_AGC = "auto_gain";
    private static final String K_LOWCUT = "low_cut";
    private static final String K_GAIN = "mic_gain";
    private static final String K_SPEECH_MODEL = "speech_model";
    private static final String K_CLEAN = "speech_clean";
    private static final String K_CLEAN_LEVEL = "speech_clean_level";
    private static final String K_AUTO_TR = "speech_auto";
    private static final String K_VOCAB = "speech_vocab";
    private static final String K_CLOUD_KEY = "cloud_key";
    private static final String K_SMART_FIX = "cloud_fix";
    private static final String K_FOLLOW = "tr_follow";
    private static final String K_FOCUS = "voice_focus";
    private static final String K_NEURAL = "neural_denoise";

    private final SharedPreferences prefs;

    public Store(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int sampleRate(int quality) {
        if (quality == Q_STANDARD) return 22050;
        if (quality == Q_HIGH) return 44100;
        return 48000;
    }

    public static int bitRate(int quality) {
        if (quality == Q_STANDARD) return 64000;
        if (quality == Q_HIGH) return 128000;
        return 256000;
    }

    public static int effectiveBitRate(int quality, int mode) {
        if (mode == M_SPEECH) return 48000;
        int base = bitRate(quality);
        if (mode == M_INTERVIEW) return Math.min(base * 2, 320000);
        return base;
    }

    public boolean getHaptic() { return prefs.getBoolean(K_HAPTIC, true); }
    public void setHaptic(boolean b) { prefs.edit().putBoolean(K_HAPTIC, b).apply(); }

    public boolean getKeepAwake() { return prefs.getBoolean(K_KEEP_AWAKE, true); }
    public void setKeepAwake(boolean b) { prefs.edit().putBoolean(K_KEEP_AWAKE, b).apply(); }

    public int getQuality() { return prefs.getInt(K_QUALITY, Q_HIGH); }
    public void setQuality(int q) { prefs.edit().putInt(K_QUALITY, q).apply(); }

    public int getMode() { return prefs.getInt(K_MODE, M_STANDARD); }
    public void setMode(int m) { prefs.edit().putInt(K_MODE, m).apply(); }

    public int getSort() { return prefs.getInt(K_SORT, SORT_NEWEST); }
    public void setSort(int s) { prefs.edit().putInt(K_SORT, s).apply(); }

    public int getSkip() { return prefs.getInt(K_SKIP, 15); }
    public void setSkip(int s) { prefs.edit().putInt(K_SKIP, s).apply(); }

    public float getSpeed() { return prefs.getFloat(K_SPEED, 1f); }
    public void setSpeed(float s) { prefs.edit().putFloat(K_SPEED, s).apply(); }

    public int nextIndex() {
        int next = prefs.getInt(K_INDEX, 0) + 1;
        prefs.edit().putInt(K_INDEX, next).apply();
        return next;
    }

    public boolean getNotifAsked() { return prefs.getBoolean(K_NOTIF_ASKED, false); }
    public void setNotifAsked(boolean b) { prefs.edit().putBoolean(K_NOTIF_ASKED, b).apply(); }

    public boolean getNoise() { return prefs.getBoolean(K_NOISE, true); }
    public void setNoise(boolean b) { prefs.edit().putBoolean(K_NOISE, b).apply(); }

    public boolean getAgc() { return prefs.getBoolean(K_AGC, false); }
    public void setAgc(boolean b) { prefs.edit().putBoolean(K_AGC, b).apply(); }

    public boolean getLowCut() { return prefs.getBoolean(K_LOWCUT, true); }
    public void setLowCut(boolean b) { prefs.edit().putBoolean(K_LOWCUT, b).apply(); }

    public int getGainIndex() { return prefs.getInt(K_GAIN, 0); }
    public void setGainIndex(int i) { prefs.edit().putInt(K_GAIN, i).apply(); }

    public static int gainDb(int index) {
        int[] db = {0, 3, 6, 9};
        return db[Math.max(0, Math.min(db.length - 1, index))];
    }

    public int getSpeechModel() { return prefs.getInt(K_SPEECH_MODEL, -1); }
    public void setSpeechModel(int i) { prefs.edit().putInt(K_SPEECH_MODEL, i).apply(); }

    public boolean getClean() { return prefs.getBoolean(K_CLEAN, true); }
    public void setClean(boolean b) { prefs.edit().putBoolean(K_CLEAN, b).apply(); }

    public int getCleanLevel() { return prefs.getInt(K_CLEAN_LEVEL, 1); }
    public void setCleanLevel(int i) { prefs.edit().putInt(K_CLEAN_LEVEL, i).apply(); }

    public boolean getAutoTranscribe() { return prefs.getBoolean(K_AUTO_TR, false); }
    public void setAutoTranscribe(boolean b) { prefs.edit().putBoolean(K_AUTO_TR, b).apply(); }

    public String getVocab() { return prefs.getString(K_VOCAB, ""); }
    public void setVocab(String v) { prefs.edit().putString(K_VOCAB, v == null ? "" : v).apply(); }

    public String getCloudKey() { return prefs.getString(K_CLOUD_KEY, ""); }
    public void setCloudKey(String k) { prefs.edit().putString(K_CLOUD_KEY, k == null ? "" : k.trim()).apply(); }

    public boolean getSmartFix() { return prefs.getBoolean(K_SMART_FIX, true); }
    public void setSmartFix(boolean b) { prefs.edit().putBoolean(K_SMART_FIX, b).apply(); }

    public boolean getNeural() { return prefs.getBoolean(K_NEURAL, true); }
    public void setNeural(boolean b) { prefs.edit().putBoolean(K_NEURAL, b).apply(); }

    public int getFocus() { return prefs.getInt(K_FOCUS, 1); }
    public void setFocus(int i) { prefs.edit().putInt(K_FOCUS, i).apply(); }

    public boolean getFollow() { return prefs.getBoolean(K_FOLLOW, false); }
    public void setFollow(boolean b) { prefs.edit().putBoolean(K_FOLLOW, b).apply(); }
}
