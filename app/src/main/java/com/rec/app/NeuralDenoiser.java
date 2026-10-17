package com.rec.app;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

final class NeuralDenoiser implements Closeable {

    interface Prog {
        void on(float p);
    }

    static final String FILE = "gtcrn_simple.onnx";
    static final String URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx";
    private static final String P = "com.k2fsa.sherpa.onnx.";
    private static final int BLOCK = 30 * Voice16k.RATE;
    private static final int OVERLAP = Voice16k.RATE / 2;
    private static final float TARGET_RMS = 0.1f;

    private final Object den;
    private final Method run;
    private final Method samples;
    private final Method release;

    private NeuralDenoiser(Object den, Method run, Method samples, Method release) {
        this.den = den;
        this.run = run;
        this.samples = samples;
        this.release = release;
    }

    static File file(Context c) {
        return new File(Models.root(c), FILE);
    }

    static boolean installed(Context c) {
        File f = file(c);
        return f.isFile() && f.length() > 100000;
    }

    static boolean ensure(Context c) {
        if (installed(c)) return true;
        File f = file(c);
        File part = new File(f.getParentFile(), FILE + ".part");
        try {
            Models.proxyKey = new Store(c).getCloudKey();
            HttpURLConnection con = Models.connect(URL, 0);
            if (con.getResponseCode() != 200) {
                con.disconnect();
                return false;
            }
            try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] b = new byte[32 * 1024];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            } finally {
                con.disconnect();
            }
            if (part.length() < 100000) {
                part.delete();
                return false;
            }
            return part.renameTo(f);
        } catch (IOException e) {
            part.delete();
            return false;
        }
    }

    static NeuralDenoiser open(Context c) throws Exception {
        Class<?> gc = Class.forName(P + "OfflineSpeechDenoiserGtcrnModelConfig");
        Object g = gc.getDeclaredConstructor().newInstance();
        gc.getMethod("setModel", String.class).invoke(g, file(c).getAbsolutePath());

        Class<?> mc = Class.forName(P + "OfflineSpeechDenoiserModelConfig");
        Object m = mc.getDeclaredConstructor().newInstance();
        mc.getMethod("setGtcrn", gc).invoke(m, g);
        mc.getMethod("setNumThreads", int.class).invoke(m, 2);

        Class<?> cc = Class.forName(P + "OfflineSpeechDenoiserConfig");
        Object cfg = cc.getDeclaredConstructor().newInstance();
        cc.getMethod("setModel", mc).invoke(cfg, m);

        Class<?> dc = Class.forName(P + "OfflineSpeechDenoiser");
        Object d = dc.getConstructor(AssetManager.class, cc).newInstance(null, cfg);
        Method run = dc.getMethod("run", float[].class, int.class);
        Class<?> rc = Class.forName(P + "DenoisedAudio");
        Method samples = rc.getMethod("getSamples");
        Method release = dc.getMethod("release");
        return new NeuralDenoiser(d, run, samples, release);
    }

    private float[] denoise(float[] x) throws Exception {
        Object r = run.invoke(den, x, Voice16k.RATE);
        return (float[]) samples.invoke(r);
    }

    float process(File raw, File out, BooleanSupplier cancel, Prog prog) throws Exception {
        long total = Math.max(1, raw.length() / 2);
        long readSamples = 0;
        double sq = 0;
        long cnt = 0;
        float[] carry = new float[0];
        float[] tail = null;
        boolean first = true;
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(raw), 64 * 1024);
             BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out), 64 * 1024)) {
            while (true) {
                if (cancel.getAsBoolean()) throw new Models.CancelledException();
                int want = first ? BLOCK + OVERLAP : BLOCK;
                float[] x = new float[carry.length + want];
                System.arraycopy(carry, 0, x, 0, carry.length);
                int got = readFloats(in, x, carry.length, want);
                int len = carry.length + got;
                if (len == 0) break;
                if (len < x.length) x = Arrays.copyOf(x, len);
                readSamples += got;
                boolean last = got < want;

                float[] y = denoise(x);
                if (y.length < len) y = Arrays.copyOf(y, len);
                int emitN = last ? len : BLOCK;
                float[] o = Arrays.copyOf(y, emitN);
                if (!first && tail != null) {
                    int f = Math.min(OVERLAP, emitN);
                    for (int i = 0; i < f; i++) {
                        float w = (i + 0.5f) / OVERLAP;
                        o[i] = tail[i] * (1f - w) + y[i] * w;
                    }
                }
                for (float v : o) {
                    float s = v * 32767f;
                    if (s > 32767f) s = 32767f;
                    else if (s < -32768f) s = -32768f;
                    short sh = (short) s;
                    os.write(sh & 0xff);
                    os.write((sh >> 8) & 0xff);
                    if (Math.abs(v) > 0.005f) {
                        sq += (double) v * v;
                        cnt++;
                    }
                }
                prog.on(Math.min(1f, readSamples / (float) total));
                if (last) break;
                tail = Arrays.copyOfRange(y, BLOCK, BLOCK + OVERLAP);
                carry = Arrays.copyOfRange(x, BLOCK, len);
                first = false;
            }
        }
        if (cnt < 1000) return 1f;
        float rms = (float) Math.sqrt(sq / cnt);
        float g = TARGET_RMS / Math.max(rms, 1e-4f);
        return Math.max(1f, Math.min(10f, g));
    }

    static void applyGain(short[] b, int n, float gain) {
        for (int i = 0; i < n; i++) {
            float v = b[i] / 32768f * gain;
            float a = Math.abs(v);
            if (a > 0.85f) {
                float t = 0.85f + 0.14f * (float) Math.tanh((a - 0.85f) / 0.14f);
                v = v < 0 ? -t : t;
            }
            b[i] = (short) Math.round(v * 32767f);
        }
    }

    private static int readFloats(InputStream in, float[] dst, int off, int count) throws IOException {
        byte[] buf = new byte[count * 2];
        int got = 0;
        while (got < buf.length) {
            int r = in.read(buf, got, buf.length - got);
            if (r < 0) break;
            got += r;
        }
        int n = got / 2;
        for (int i = 0; i < n; i++) {
            short s = (short) ((buf[2 * i] & 0xff) | (buf[2 * i + 1] << 8));
            dst[off + i] = s / 32768f;
        }
        return n;
    }

    @Override
    public void close() {
        try {
            release.invoke(den);
        } catch (Exception ignored) {
        }
    }
}
