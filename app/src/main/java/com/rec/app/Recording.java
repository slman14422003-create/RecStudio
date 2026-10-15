package com.rec.app;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;

public final class Recording {

    public final String id;
    public String title;
    public final long createdAt;
    public long durationMs;
    public boolean favorite;
    public int quality;
    public int mode;
    public int label;
    public long deletedAt;
    public final ArrayList<Long> markers = new ArrayList<>();
    public int[] peaks = new int[0];

    public Recording(String id, String title, long createdAt) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
    }

    public File file(Context c) {
        return Repo.audio(c, id);
    }

    public long size(Context c) {
        return file(c).length();
    }

    public float[] peaksAsFloat() {
        float[] out = new float[peaks.length];
        for (int i = 0; i < peaks.length; i++) out[i] = peaks[i] / 100f;
        return out;
    }

    public void addMarker(long ms) {
        markers.add(Math.max(0, ms));
        Collections.sort(markers);
    }

    public static int[] slicePeaks(int[] src, float from, float to) {
        if (src.length == 0) return new int[0];
        int a = Math.max(0, Math.min(src.length - 1, Math.round(from * src.length)));
        int b = Math.max(a + 1, Math.min(src.length, Math.round(to * src.length)));
        int[] out = new int[b - a];
        System.arraycopy(src, a, out, 0, out.length);
        return out;
    }
}
