package com.rec.app;

final class Fft {

    private final int n;
    private final float[] cos;
    private final float[] sin;
    private final int[] rev;

    Fft(int n) {
        this.n = n;
        cos = new float[n / 2];
        sin = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            cos[i] = (float) Math.cos(2.0 * Math.PI * i / n);
            sin[i] = (float) Math.sin(2.0 * Math.PI * i / n);
        }
        rev = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
    }

    void transform(float[] re, float[] im, boolean inverse) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                float t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int size = 2; size <= n; size <<= 1) {
            int half = size / 2;
            int step = n / size;
            for (int i = 0; i < n; i += size) {
                for (int j = i, k = 0; j < i + half; j++, k += step) {
                    float wr = cos[k];
                    float wi = inverse ? sin[k] : -sin[k];
                    int l = j + half;
                    float tr = re[l] * wr - im[l] * wi;
                    float ti = re[l] * wi + im[l] * wr;
                    re[l] = re[j] - tr;
                    im[l] = im[j] - ti;
                    re[j] += tr;
                    im[j] += ti;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }
}
