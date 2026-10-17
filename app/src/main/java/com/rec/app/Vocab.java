package com.rec.app;

import java.util.ArrayList;
import java.util.List;

final class Vocab {

    static final class Entry {
        final String[] words;
        final String[] keys;

        Entry(String[] words) {
            this.words = words;
            this.keys = new String[words.length];
            for (int i = 0; i < words.length; i++) keys[i] = key(words[i]);
        }
    }

    private Vocab() {}

    static List<Entry> parse(String raw) {
        List<Entry> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line : raw.split("[\\n,\\u060C;]")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String[] w = t.split("\\s+");
            boolean ok = true;
            for (String x : w) if (key(x).isEmpty()) ok = false;
            if (ok) out.add(new Entry(w));
        }
        return out;
    }

    static String key(String w) {
        StringBuilder sb = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if ((c >= '\u064B' && c <= '\u0652') || c == '\u0640') continue;
            switch (c) {
                case '\u0623':
                case '\u0625':
                case '\u0622':
                case '\u0671':
                    c = '\u0627';
                    break;
                case '\u0629':
                    c = '\u0647';
                    break;
                case '\u0649':
                case '\u0626':
                    c = '\u064A';
                    break;
                case '\u0624':
                    c = '\u0648';
                    break;
                case '\u0635':
                case '\u062B':
                    c = '\u0633';
                    break;
                case '\u0636':
                    c = '\u062F';
                    break;
                case '\u0638':
                case '\u0630':
                    c = '\u0632';
                    break;
                case '\u0637':
                    c = '\u062A';
                    break;
                case '\u0642':
                    c = '\u0643';
                    break;
                case '\u0621':
                    continue;
                default:
                    break;
            }
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static float similarity(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0 || m == 0) return 0f;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1f - prev[m] / (float) Math.max(n, m);
    }

    static float ratio(String a, String b) {
        String x = key(a);
        String y = key(b);
        if (x.equals(y)) return 1f;
        return similarity(x, y);
    }

    static String prompt(String raw) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : parse(raw)) {
            if (sb.length() > 0) sb.append("\u060C ");
            sb.append(String.join(" ", e.words));
            if (sb.length() > 600) break;
        }
        return sb.toString();
    }

    private static boolean close(String found, String want) {
        if (found.isEmpty()) return false;
        if (want.length() < 4) return found.equals(want);
        return similarity(found, want) >= 0.8f;
    }

    private static String lead(String tok) {
        int i = 0;
        while (i < tok.length() && !Character.isLetterOrDigit(tok.charAt(i))) i++;
        return tok.substring(0, i);
    }

    private static String trail(String tok) {
        int i = tok.length();
        while (i > 0 && !Character.isLetterOrDigit(tok.charAt(i - 1))) i--;
        return tok.substring(i);
    }

    static String fix(String text, List<Entry> entries) {
        if (entries.isEmpty() || text == null || text.isEmpty()) return text;
        String[] tok = text.split(" ");
        String[] keys = new String[tok.length];
        for (int i = 0; i < tok.length; i++) keys[i] = key(tok[i]);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < tok.length) {
            Entry hit = null;
            for (Entry e : entries) {
                int k = e.words.length;
                if (i + k > tok.length) continue;
                boolean all = true;
                for (int j = 0; j < k && all; j++) all = close(keys[i + j], e.keys[j]);
                if (all) {
                    hit = e;
                    break;
                }
            }
            if (out.length() > 0) out.append(' ');
            if (hit == null) {
                out.append(tok[i]);
                i++;
            } else {
                int k = hit.words.length;
                out.append(lead(tok[i]));
                for (int j = 0; j < k; j++) {
                    if (j > 0) out.append(' ');
                    out.append(hit.words[j]);
                }
                out.append(trail(tok[i + k - 1]));
                i += k;
            }
        }
        return out.toString();
    }
}
