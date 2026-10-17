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

    public static final int ENGINE_VOSK = 0;
    public static final int ENGINE_WHISPER = 1;
    public static final int ENGINE_CLOUD = 2;

    public static final String WHISPER_ENCODER = "encoder.int8.onnx";
    public static final String WHISPER_DECODER = "decoder.int8.onnx";
    public static final String WHISPER_TOKENS = "tokens.txt";

    public static final class Spec {
        public final String folder;
        public final String lang;
        public final int mb;
        public final boolean heavy;
        public final int engine;
        public final String repo;
        public final String prefix;

        Spec(String folder, String lang, int mb, boolean heavy) {
            this(folder, lang, mb, heavy, ENGINE_VOSK, null, null);
        }

        Spec(String folder, String lang, int mb, boolean heavy, int engine, String repo, String prefix) {
            this.folder = folder;
            this.lang = lang;
            this.mb = mb;
            this.heavy = heavy;
            this.engine = engine;
            this.repo = repo;
            this.prefix = prefix;
        }

        public String url() {
            return "https://alphacephei.com/vosk/models/" + folder + ".zip";
        }

        public String[][] files() {
            String base = "https://huggingface.co/csukuangfj/" + repo + "/resolve/main/";
            return new String[][]{
                    {base + prefix + "-encoder.int8.onnx", WHISPER_ENCODER},
                    {base + prefix + "-decoder.int8.onnx", WHISPER_DECODER},
                    {base + prefix + "-tokens.txt", WHISPER_TOKENS}
            };
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
            new Spec("vosk-model-small-ru-0.22", "ru", 45, false),
            new Spec("whisper-small-ar", "ar", 360, false, ENGINE_WHISPER, "sherpa-onnx-whisper-small", "small"),
            new Spec("whisper-medium-ar", "ar", 900, true, ENGINE_WHISPER, "sherpa-onnx-whisper-medium", "medium"),
            new Spec("whisper-turbo-ar", "ar", 1000, true, ENGINE_WHISPER, "sherpa-onnx-whisper-turbo", "turbo"),
            new Spec("cloud-groq-whisper", "ar", 0, false, ENGINE_CLOUD, null, null)
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
        if (s.engine == ENGINE_CLOUD) return !new Store(c).getCloudKey().isEmpty();
        File d = dir(c, s);
        if (s.engine == ENGINE_WHISPER) {
            return new File(d, WHISPER_ENCODER).isFile() && new File(d, WHISPER_DECODER).isFile() && new File(d, WHISPER_TOKENS).isFile();
        }
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
        if (s.engine == ENGINE_WHISPER) {
            File[] kids = new File(root(c), s.folder + ".dl").listFiles();
            long sum = 0;
            if (kids != null) for (File k : kids) sum += k.length();
            return sum;
        }
        File part = new File(root(c), s.folder + ".zip.part");
        return part.exists() ? part.length() : 0;
    }

    public static void delete(Context c, Spec s) {
        deleteRecursive(dir(c, s));
        deleteRecursive(new File(root(c), s.folder + ".tmp"));
        deleteRecursive(new File(root(c), s.folder + ".dl"));
        new File(root(c), s.folder + ".zip").delete();
        new File(root(c), s.folder + ".zip.part").delete();
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    static String proxyKey = "";

    static HttpURLConnection connect(String url, long have) throws IOException {
        IOException first = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean proxy = attempt == 1;
            if (proxy && (proxyKey == null || proxyKey.isEmpty() || Cloud.BASE.contains("YOUR-SUBDOMAIN"))) break;
            try {
                String u = proxy ? Cloud.BASE + "dl?u=" + java.net.URLEncoder.encode(url, "UTF-8") : url;
                HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
                con.setConnectTimeout(proxy ? 30000 : 12000);
                con.setReadTimeout(30000);
                con.setInstanceFollowRedirects(true);
                con.setRequestProperty("User-Agent", "RecStudio");
                if (proxy) con.setRequestProperty("Authorization", "Bearer " + proxyKey);
                if (have > 0) con.setRequestProperty("Range", "bytes=" + have + "-");
                int code = con.getResponseCode();
                if (code == 200 || code == 206 || code == 416) return con;
                con.disconnect();
                first = new IOException("HTTP " + code);
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }
        throw first != null ? first : new IOException("connect failed");
    }

    public static void download(Context c, Spec s, Progress sink, BooleanSupplier cancelled) throws IOException {
        proxyKey = new Store(c).getCloudKey();
        if (s.engine == ENGINE_WHISPER) {
            downloadFiles(c, s, sink, cancelled);
            return;
        }
        File root = root(c);
        File part = new File(root, s.folder + ".zip.part");
        File zip = new File(root, s.folder + ".zip");

        if (!zip.exists()) {
            long have = part.exists() ? part.length() : 0;
            HttpURLConnection con = connect(s.url(), have);
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

    private static void downloadFiles(Context c, Spec s, Progress sink, BooleanSupplier cancelled) throws IOException {
        File root = root(c);
        File stage = new File(root, s.folder + ".dl");
        stage.mkdirs();
        String[][] files = s.files();
        long expected = Math.max(1L, s.mb * 1024L * 1024L);
        long[] done = {0};
        for (String[] f : files) {
            File fin = new File(stage, f[1]);
            if (fin.isFile() && fin.length() > 0) {
                done[0] += fin.length();
                continue;
            }
            fetchFile(f[0], new File(stage, f[1] + ".part"), fin, done, expected, sink, cancelled);
        }
        File dest = dir(c, s);
        deleteRecursive(dest);
        if (!stage.renameTo(dest)) throw new IOException("install failed");
        if (!installed(c, s)) {
            deleteRecursive(dest);
            throw new IOException("invalid model");
        }
        sink.onProgress(1f);
    }

    private static void fetchFile(String url, File part, File out, long[] done, long expected, Progress sink, BooleanSupplier cancelled) throws IOException {
        long have = part.exists() ? part.length() : 0;
        HttpURLConnection con = connect(url, have);
        int code = con.getResponseCode();
        if (code == 416) {
            if (!part.renameTo(out)) throw new IOException("rename failed");
            done[0] += out.length();
            con.disconnect();
            return;
        }
        boolean append;
        if (code == 206) {
            append = true;
            done[0] += have;
        } else if (code == 200) {
            append = false;
        } else {
            con.disconnect();
            throw new IOException("HTTP " + code);
        }
        try (InputStream in = con.getInputStream(); FileOutputStream fos = new FileOutputStream(part, append)) {
            byte[] buf = new byte[64 * 1024];
            long lastReport = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled.getAsBoolean()) throw new CancelledException();
                fos.write(buf, 0, n);
                done[0] += n;
                long now = System.currentTimeMillis();
                if (now - lastReport > 250) {
                    lastReport = now;
                    sink.onProgress(0.92f * Math.min(1f, done[0] / (float) expected));
                }
            }
        } finally {
            con.disconnect();
        }
        if (!part.renameTo(out)) throw new IOException("rename failed");
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
