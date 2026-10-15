package com.rec.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.StatFs;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Repo {

    public static final long TRASH_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    public static final long DAY_MS = 24L * 60L * 60L * 1000L;

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

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static List<Recording> all(Context c) {
        return list(c, false);
    }

    public static List<Recording> trashed(Context c) {
        return list(c, true);
    }

    private static List<Recording> list(Context c, boolean trashed) {
        List<Recording> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(META_EXT)) continue;
            String id = name.substring(0, name.length() - META_EXT.length());
            if (!audio(c, id).exists()) continue;
            Recording r = read(f);
            if (r != null && (r.deletedAt > 0) == trashed) out.add(r);
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
            r.mode = o.optInt("mode", Store.M_STANDARD);
            r.label = o.optInt("label", 0);
            r.deletedAt = o.optLong("del", 0);
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
            o.put("mode", r.mode);
            o.put("label", r.label);
            o.put("del", r.deletedAt);
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

    public static void trash(Context c, Recording r) {
        r.deletedAt = System.currentTimeMillis();
        save(c, r);
    }

    public static void restore(Context c, Recording r) {
        r.deletedAt = 0;
        save(c, r);
    }

    public static void purge(Context c) {
        long now = System.currentTimeMillis();
        for (Recording r : trashed(c)) {
            if (now - r.deletedAt > TRASH_RETENTION_MS) delete(c, r.id);
        }
    }

    public static void delete(Context c, String id) {
        audio(c, id).delete();
        meta(c, id).delete();
        new File(dir(c), id + ".tmp").delete();
        new File(dir(c), id + ".trim").delete();
    }

    public static long totalSize(Context c) {
        long sum = 0;
        File[] files = dir(c).listFiles();
        if (files == null) return 0;
        for (File f : files) sum += f.length();
        return sum;
    }

    public static long freeBytes(Context c) {
        try {
            return new StatFs(dir(c).getAbsolutePath()).getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private static String safeName(Recording r) {
        String safe = r.title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? r.id : safe;
    }

    public static Uri uri(Context c, Recording r) {
        return FileProvider.getUriForFile(c, c.getPackageName() + ".files", r.file(c), safeName(r) + AUDIO_EXT);
    }

    public static Recording duplicate(Context c, Recording r, String title) {
        String id = newId();
        try (InputStream in = new FileInputStream(r.file(c)); OutputStream out = new FileOutputStream(audio(c, id))) {
            copy(in, out);
        } catch (IOException e) {
            audio(c, id).delete();
            return null;
        }
        Recording d = new Recording(id, title, System.currentTimeMillis());
        d.durationMs = r.durationMs;
        d.quality = r.quality;
        d.mode = r.mode;
        d.label = r.label;
        d.markers.addAll(r.markers);
        d.peaks = r.peaks.clone();
        save(c, d);
        return d;
    }

    public static boolean exportToDevice(Context c, Recording r) {
        if (Build.VERSION.SDK_INT < 29) return false;
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Audio.Media.DISPLAY_NAME, safeName(r) + AUDIO_EXT);
        v.put(MediaStore.Audio.Media.TITLE, r.title);
        v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        v.put(MediaStore.Audio.Media.RELATIVE_PATH, (Build.VERSION.SDK_INT >= 31 ? "Recordings/" : "Music/") + "SEMO Recorder");
        v.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) return false;
        try (InputStream in = new FileInputStream(r.file(c)); OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new IOException();
            copy(in, out);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            return false;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Audio.Media.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        return true;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
