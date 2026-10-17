package com.rec.app;

final class Voice16k {

    static final int RATE = 16000;

    private double sum;
    private int count;
    private double pos;

    int convert(short[] data, int n, int rate, int channels, float[] out) {
        double ratio = rate / (double) RATE;
        int produced = 0;
        int frames = n / channels;
        for (int f = 0; f < frames; f++) {
            float s = 0f;
            for (int c = 0; c < channels; c++) s += data[f * channels + c];
            s = s / channels / 32768f;
            sum += s;
            count++;
            pos += 1.0;
            if (pos >= ratio) {
                out[produced++] = (float) (sum / count);
                sum = 0;
                count = 0;
                pos -= ratio;
            }
        }
        return produced;
    }
}
