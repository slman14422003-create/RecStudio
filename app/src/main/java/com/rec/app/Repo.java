package com.rec.app;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Repo {

    private static final String AUDIO_EXT = ".m4a";
    private static final String META_EXT = ".json";

    private Repo() {}

    public static File dir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File d = new File(base, "recordings");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File audio(Context c, String id) {
        return new File(dir(c), id + AUDIO_EXT);
    }

    private static File meta(Context c, String id) {
        return new File(dir(c), id + META_EXT);
    }

    public static List<Recording> all(Context c) {
        List<Recording> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(META_EXT)) continue;
            String id = name.substring(0, name.length() - META_EXT.length());
            if (!audio(c, id).exists()) continue;
            Recording r = read(f);
            if (r != null) out.add(r);
        }
        return out;
    }

    public static Recording get(Context c, String id) {
        File m = meta(c, id);
        if (!m.exists() || !audio(c, id).exists()) return null;
        return read(m);
    }

    private static Recording read(File f) {
        try {
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            JSONObject o = new JSONObject(new String(buf, StandardCharsets.UTF_8));
            Recording r = new Recording(o.getString("id"), o.getString("title"), o.getLong("created"));
            r.durationMs = o.optLong("duration", 0);
            r.favorite = o.optBoolean("fav", false);
            r.quality = o.optInt("q", Store.Q_HIGH);
            JSONArray m = o.optJSONArray("markers");
            if (m != null) {
                for (int i = 0; i < m.length(); i++) r.markers.add(m.getLong(i));
            }
            JSONArray p = o.optJSONArray("peaks");
            if (p != null) {
                r.peaks = new int[p.length()];
                for (int i = 0; i < p.length(); i++) r.peaks[i] = p.getInt(i);
            }
            return r;
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
    }

    public static void save(Context c, Recording r) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", r.id);
            o.put("title", r.title);
            o.put("created", r.createdAt);
            o.put("duration", r.durationMs);
            o.put("fav", r.favorite);
            o.put("q", r.quality);
            JSONArray m = new JSONArray();
            for (long v : r.markers) m.put(v);
            o.put("markers", m);
            JSONArray p = new JSONArray();
            for (int v : r.peaks) p.put(v);
            o.put("peaks", p);
            File target = meta(c, r.id);
            File tmp = new File(target.getParentFile(), r.id + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(target)) {
                target.delete();
                tmp.renameTo(target);
            }
        } catch (IOException | JSONException ignored) {
        }
    }

    public static void delete(Context c, String id) {
        audio(c, id).delete();
        meta(c, id).delete();
        new File(dir(c), id + ".tmp").delete();
    }

    public static long totalSize(Context c) {
        long sum = 0;
        File[] files = dir(c).listFiles();
        if (files == null) return 0;
        for (File f : files) sum += f.length();
        return sum;
    }

    public static Uri uri(Context c, Recording r) {
        String safe = r.title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safe.isEmpty()) safe = r.id;
        return FileProvider.getUriForFile(c, c.getPackageName() + ".files", r.file(c), safe + AUDIO_EXT);
    }
}
