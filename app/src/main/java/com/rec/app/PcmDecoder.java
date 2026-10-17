package com.rec.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BooleanSupplier;

final class PcmDecoder {

    interface Sink {
        void onPcm(short[] data, int count, int rate, int channels, long ptsUs) throws IOException;
    }

    private PcmDecoder() {}

    static long durationUs(File f) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/") && fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    return fmt.getLong(MediaFormat.KEY_DURATION);
                }
            }
        } catch (IOException | RuntimeException ignored) {
        } finally {
            ex.release();
        }
        return 0;
    }

    static void decode(File file, Sink sink, BooleanSupplier cancelled) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("no audio track");
            ex.selectTrack(track);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            short[] pcm = new short[16384];
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
                if (cancelled.getAsBoolean()) throw new Models.CancelledException();
                if (!inputDone) {
                    int idx = codec.dequeueInputBuffer(10000);
                    if (idx >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(idx);
                        int size = ib == null ? -1 : ex.readSampleData(ib, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(idx, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (out >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(out);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ob.order(ByteOrder.nativeOrder());
                        int shorts = info.size / 2;
                        if (pcm.length < shorts) pcm = new short[shorts];
                        ob.asShortBuffer().get(pcm, 0, shorts);
                        sink.onPcm(pcm, shorts, rate, Math.max(1, channels), info.presentationTimeUs);
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
        } catch (RuntimeException e) {
            throw new IOException(e);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                }
                try {
                    codec.release();
                } catch (RuntimeException ignored) {
                }
            }
            ex.release();
        }
    }
}
