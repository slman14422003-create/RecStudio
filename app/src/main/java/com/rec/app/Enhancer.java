package com.rec.app;

final class Enhancer {

    static final int N = 512;
    static final int H = 256;
    private static final int BINS = N / 2 + 1;
    private static final int SUB_FRAMES = 12;
    private static final int SUB_WINDOWS = 8;
    private static final float BIAS = 1.7f;
    private static final float BETA = 0.92f;

    private final Fft fft = new Fft(N);
    private final float[] window = new float[N];
    private final float[] inBuf = new float[N];
    private final float[] ola = new float[N];
    private final float[] re = new float[N];
    private final float[] im = new float[N];
    private final float[] smooth = new float[BINS];
    private final float[] noise = new float[BINS];
    private final float[] prevClean = new float[BINS];
    private final float[] subMin = new float[BINS];
    private final float[][] subWin = new float[SUB_WINDOWS][BINS];
    private final float[] gain = new float[BINS];
    private final float[] gainSmooth = new float[BINS];

    private final float gMin;
    private final float over;
    private final boolean normalize;

    private int inCount;
    private boolean started;
    private int subIdx;
    private int subFrames;
    private float env;
    private float agcGain = 1f;

    Enhancer(int level, boolean normalize) {
        this.normalize = normalize;
        if (level <= 0) {
            gMin = 0.35f;
            over = 1.0f;
        } else if (level == 1) {
            gMin = 0.18f;
            over = 1.2f;
        } else {
            gMin = 0.09f;
            over = 1.5f;
        }
        for (int i = 0; i < N; i++) window[i] = (float) Math.sin(Math.PI * (i + 0.5) / N);
    }

    int process(float[] in, int n, float[] out) {
        int outCount = 0;
        for (int i = 0; i < n; i++) {
            inBuf[inCount++] = in[i];
            if (inCount == N) {
                frame(out, outCount);
                outCount += H;
                System.arraycopy(inBuf, H, inBuf, 0, N - H);
                inCount = N - H;
            }
        }
        if (normalize) agc(out, outCount);
        return outCount;
    }

    int flush(float[] out) {
        float[] zeros = new float[N];
        int produced = process(zeros, N, out);
        return produced;
    }

    private void frame(float[] out, int offset) {
        for (int i = 0; i < N; i++) {
            re[i] = inBuf[i] * window[i];
            im[i] = 0f;
        }
        fft.transform(re, im, false);

        boolean first = !started;
        for (int k = 0; k < BINS; k++) {
            float p = re[k] * re[k] + im[k] * im[k];
            if (first) {
                smooth[k] = p;
                noise[k] = p * BIAS;
                subMin[k] = p;
                for (int u = 0; u < SUB_WINDOWS; u++) subWin[u][k] = p;
            }
            smooth[k] = 0.85f * smooth[k] + 0.15f * p;
            if (smooth[k] < subMin[k]) subMin[k] = smooth[k];
            float quick = smooth[k] * BIAS;
            if (quick < noise[k]) noise[k] = quick;
        }
        started = true;

        subFrames++;
        if (subFrames >= SUB_FRAMES) {
            subFrames = 0;
            for (int k = 0; k < BINS; k++) {
                subWin[subIdx][k] = subMin[k];
                subMin[k] = smooth[k];
                float m = subWin[0][k];
                for (int u = 1; u < SUB_WINDOWS; u++) if (subWin[u][k] < m) m = subWin[u][k];
                noise[k] = m * BIAS;
            }
            subIdx = (subIdx + 1) % SUB_WINDOWS;
        }

        for (int k = 0; k < BINS; k++) {
            float p = re[k] * re[k] + im[k] * im[k];
            float nz = Math.max(noise[k] * over, 1e-9f);
            float gamma = p / nz;
            float xi = BETA * (prevClean[k] / nz) + (1f - BETA) * Math.max(gamma - 1f, 0f);
            float g = xi / (1f + xi);
            gain[k] = Math.max(g, gMin);
        }
        for (int k = 0; k < BINS; k++) {
            float left = gain[k == 0 ? 0 : k - 1];
            float right = gain[k == BINS - 1 ? BINS - 1 : k + 1];
            gainSmooth[k] = 0.25f * left + 0.5f * gain[k] + 0.25f * right;
        }
        for (int k = 0; k < BINS; k++) {
            float p = re[k] * re[k] + im[k] * im[k];
            float g = gainSmooth[k];
            prevClean[k] = g * g * p;
            re[k] *= g;
            im[k] *= g;
        }
        for (int k = 1; k < N / 2; k++) {
            re[N - k] = re[k];
            im[N - k] = -im[k];
        }

        fft.transform(re, im, true);
        for (int i = 0; i < N; i++) ola[i] += re[i] * window[i];
        System.arraycopy(ola, 0, out, offset, H);
        System.arraycopy(ola, H, ola, 0, N - H);
        for (int i = N - H; i < N; i++) ola[i] = 0f;
    }

    private void agc(float[] x, int n) {
        for (int i = 0; i < n; i++) {
            float a = Math.abs(x[i]);
            env = a > env ? env + (a - env) * 0.01f : env * 0.9995f;
            float desired = env > 0.004f ? Math.min(0.12f / env, 8f) : agcGain;
            agcGain += (desired - agcGain) * 0.0004f;
            float v = x[i] * agcGain;
            if (v > 0.97f) v = 0.97f;
            else if (v < -0.97f) v = -0.97f;
            x[i] = v;
        }
    }
}
