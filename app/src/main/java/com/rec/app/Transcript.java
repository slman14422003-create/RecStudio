package com.rec.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Transcript {

    public static final class Seg {
        public final long startMs;
        public final long endMs;
        public final String text;

        public Seg(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    public String lang = "";
    public final List<Seg> segs = new ArrayList<>();

    public boolean isEmpty() {
        return segs.isEmpty();
    }

    public String fullText() {
        StringBuilder sb = new StringBuilder();
        for (Seg s : segs) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s.text);
        }
        return sb.toString();
    }

    public String toSrt() {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Seg s : segs) {
            long end = Math.max(s.endMs, s.startMs + 500);
            sb.append(i++).append('\n')
                    .append(stamp(s.startMs)).append(" --> ").append(stamp(end)).append('\n')
                    .append(s.text).append("\n\n");
        }
        return sb.toString();
    }

    private static String stamp(long ms) {
        long h = ms / 3600000L;
        long m = (ms / 60000L) % 60;
        long s = (ms / 1000L) % 60;
        long r = ms % 1000L;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, r);
    }

    public static String polish(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) return "";
        char first = t.charAt(0);
        if (Character.isLetter(first)) t = Character.toUpperCase(first) + t.substring(1);
        char last = t.charAt(t.length() - 1);
        if (last != '.' && last != '!' && last != '?') t = t + ".";
        return t;
    }
}
