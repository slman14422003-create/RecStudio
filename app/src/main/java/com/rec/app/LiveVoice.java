package com.rec.app;

final class LiveVoice {

    private static final int CAP = 1 << 15;
    private static final int MASK = CAP - 1;
    private static final float TARGET = 0.08f;

    private final int ch;
    private final int holdMax;
    private final float gateFloor;
    private final float maxGain;
    private final Enhancer[] enh;
    private final float[][] q;
    private final int[] head;
    private final int[] size;
    private final float[] env;
    private final float[] nf;
    private final float[] gate;
    private final float[] agc;
    private final int[] hold;
    private float[] in = new float[0];
    private float[] out = new float[0];

    LiveVoice(int rate, int ch, int mode) {
        this.ch = ch;
        boolean far = mode >= 2;
        gateFloor = far ? 0.16f : 0.38f;
        maxGain = far ? 14f : 6f;
        holdMax = (int) (rate * (far ? 0.4f : 0.3f));
        enh = new Enhancer[ch];
        q = new float[ch][CAP];
        head = new int[ch];
        size = new int[ch];
        env = new float[ch];
        nf = new float[ch];
        gate = new float[ch];
        agc = new float[ch];
        hold = new int[ch];
        for (int c = 0; c < ch; c++) {
            enh[c] = new Enhancer(far ? 2 : 1, false, rate);
            nf[c] = 0.002f;
            gate[c] = 1f;
            agc[c] = 1f;
            size[c] = Enhancer.N + Enhancer.H;
        }
    }

    int process(short[] b, int n) {
        int cnt = n / ch;
        if (cnt <= 0) return 0;
        if (in.length < cnt) {
            in = new float[cnt];
            out = new float[cnt + 2 * Enhancer.N];
        }
        int peak = 0;
        for (int c = 0; c < ch; c++) {
            for (int i = 0; i < cnt; i++) in[i] = b[i * ch + c] / 32768f;
            int m = enh[c].process(in, cnt, out);
            for (int i = 0; i < m; i++) {
                if (size[c] >= CAP) break;
                q[c][(head[c] + size[c]) & MASK] = out[i];
                size[c]++;
            }
            for (int i = 0; i < cnt; i++) {
                float x = 0f;
                if (size[c] > 0) {
                    x = q[c][head[c]];
                    head[c] = (head[c] + 1) & MASK;
                    size[c]--;
                }
                x = shape(c, x);
                int v = Math.round(x * 32767f);
                if (v > 32767) v = 32767;
                else if (v < -32768) v = -32768;
                b[i * ch + c] = (short) v;
                int a = Math.abs(v);
                if (a > peak) peak = a;
            }
        }
        return peak;
    }

    private float shape(int c, float x) {
        float a = Math.abs(x);
        float e = env[c];
        e += (a > e ? 0.02f : 0.0015f) * (a - e);
        env[c] = e;
        float f = nf[c];
        f += (e < f ? 0.01f : 0.00002f) * (e - f);
        if (f < 0.00005f) f = 0.00005f;
        nf[c] = f;

        boolean speech = e > f * 2.6f + 0.0012f;
        if (speech) hold[c] = holdMax;
        else if (hold[c] > 0) hold[c]--;
        boolean active = hold[c] > 0;

        float gt = active ? 1f : gateFloor;
        float g = gate[c];
        g += (gt - g) * (gt > g ? 0.01f : 0.0008f);
        gate[c] = g;

        float ag = agc[c];
        if (active && e > 0.003f) {
            float desired = TARGET / e;
            if (desired > maxGain) desired = maxGain;
            else if (desired < 0.5f) desired = 0.5f;
            ag += (desired - ag) * (desired < ag ? 0.002f : 0.0003f);
            agc[c] = ag;
        }

        float y = x * g * ag;
        float ay = Math.abs(y);
        if (ay > 0.7f) {
            float t = 0.7f + 0.3f * (float) Math.tanh((ay - 0.7f) / 0.3f);
            y = y < 0 ? -t : t;
        }
        return y;
    }
}
