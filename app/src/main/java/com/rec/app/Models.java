package com.rec.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class Models {

    public interface Progress {
        void onProgress(float fraction);
    }

    public static final class CancelledException extends IOException {
        public CancelledException() {
            super("cancelled");
        }
    }

    public static final class Spec {
        public final String folder;
        public final String lang;
        public final int mb;
        public final boolean heavy;

        Spec(String folder, String lang, int mb, boolean heavy) {
            this.folder = folder;
            this.lang = lang;
            this.mb = mb;
            this.heavy = heavy;
        }

        public String url() {
            return "https://alphacephei.com/vosk/models/" + folder + ".zip";
        }
    }

    public static final Spec[] ALL = {
            new Spec("vosk-model-ar-mgb2-0.4", "ar", 318, true),
            new Spec("vosk-model-small-ar-tn-0.1-linto", "ar", 158, false),
            new Spec("vosk-model-small-en-us-0.15", "en", 40, false),
            new Spec("vosk-model-en-us-0.22-lgraph", "en", 128, true),
            new Spec("vosk-model-small-fr-0.22", "fr", 41, false),
            new Spec("vosk-model-small-es-0.42", "es", 39, false),
            new Spec("vosk-model-small-de-0.15", "de", 45, false),
            new Spec("vosk-model-small-tr-0.3", "tr", 35, false),
            new Spec("vosk-model-small-ru-0.22", "ru", 45, false)
    };

    private Models() {}

    public static File root(Context c) {
        File d = new File(c.getFilesDir(), "models");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File dir(Context c, Spec s) {
        return new File(root(c), s.folder);
    }

    public static boolean installed(Context c, Spec s) {
        File d = dir(c, s);
        return new File(d, "am").isDirectory() && new File(d, "conf").isDirectory();
    }

    public static boolean anyInstalled(Context c) {
        for (Spec s : ALL) if (installed(c, s)) return true;
        return false;
    }

    public static int currentIndex(Context c, Store store) {
        int i = store.getSpeechModel();
        if (i >= 0 && i < ALL.length && installed(c, ALL[i])) return i;
        for (int k = 0; k < ALL.length; k++) if (installed(c, ALL[k])) return k;
        return -1;
    }

    public static long partialBytes(Context c, Spec s) {
        File part = new File(root(c), s.folder + ".zip.part");
        return part.exists() ? part.length() : 0;
    }

    public static void delete(Context c, Spec s) {
        deleteRecursive(dir(c, s));
        deleteRecursive(new File(root(c), s.folder + ".tmp"));
        new File(root(c), s.folder + ".zip").delete();
        new File(root(c), s.folder + ".zip.part").delete();
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    public static void download(Context c, Spec s, Progress sink, BooleanSupplier cancelled) throws IOException {
        File root = root(c);
        File part = new File(root, s.folder + ".zip.part");
        File zip = new File(root, s.folder + ".zip");

        if (!zip.exists()) {
            long have = part.exists() ? part.length() : 0;
            HttpURLConnection con = (HttpURLConnection) new URL(s.url()).openConnection();
            con.setConnectTimeout(20000);
            con.setReadTimeout(30000);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", "RecStudio");
            if (have > 0) con.setRequestProperty("Range", "bytes=" + have + "-");
            int code = con.getResponseCode();
            if (code == 416) {
                part.renameTo(zip);
            } else {
                boolean append;
                long total;
                if (code == 206) {
                    append = true;
                    total = have + con.getContentLengthLong();
                } else if (code == 200) {
                    append = false;
                    have = 0;
                    total = con.getContentLengthLong();
                } else {
                    throw new IOException("HTTP " + code);
                }
                try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(part, append)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = have;
                    long lastReport = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled.getAsBoolean()) throw new CancelledException();
                        out.write(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (now - lastReport > 250) {
                            lastReport = now;
                            sink.onProgress(total > 0 ? 0.92f * done / total : -1f);
                        }
                    }
                } finally {
                    con.disconnect();
                }
                if (!part.renameTo(zip)) throw new IOException("rename failed");
            }
        }

        File tmp = new File(root, s.folder + ".tmp");
        deleteRecursive(tmp);
        tmp.mkdirs();
        unzip(zip, tmp, sink, cancelled);

        File inner = new File(tmp, s.folder);
        File src = inner.isDirectory() ? inner : tmp;
        if (!new File(src, "am").isDirectory() || !new File(src, "conf").isDirectory()) {
            deleteRecursive(tmp);
            zip.delete();
            throw new IOException("invalid model");
        }
        File dest = dir(c, s);
        deleteRecursive(dest);
        if (!src.renameTo(dest)) throw new IOException("install failed");
        deleteRecursive(tmp);
        zip.delete();
        sink.onProgress(1f);
    }

    private static void unzip(File zip, File dest, Progress sink, BooleanSupplier cancelled) throws IOException {
        String destPath = dest.getCanonicalPath() + File.separator;
        long size = Math.max(1, zip.length());
        long lastReport = 0;
        try (FileInputStream raw = new FileInputStream(zip); ZipInputStream zin = new ZipInputStream(raw)) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                if (cancelled.getAsBoolean()) throw new CancelledException();
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(destPath)) throw new IOException("bad entry");
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        long now = System.currentTimeMillis();
                        if (now - lastReport > 250) {
                            lastReport = now;
                            float f = Math.min(1f, raw.getChannel().position() / (float) size);
                            sink.onProgress(0.92f + 0.08f * f);
                        }
                    }
                }
            }
        }
    }
}
