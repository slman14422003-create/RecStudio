package com.rec.app;

import java.util.Locale;

public final class Fmt {

    private Fmt() {}

    public static String duration(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    public static String durationTenths(long ms) {
        long safe = Math.max(0, ms);
        long tenth = (safe / 100) % 10;
        return duration(safe) + "." + tenth;
    }

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    public static String speed(float s) {
        String t = String.format(Locale.US, "%.2f", s);
        if (t.contains(".")) {
            while (t.endsWith("0")) t = t.substring(0, t.length() - 1);
            if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        }
        return t + "x";
    }
}
