package com.rec.app;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class AacWriter implements Closeable {

    private final MediaCodec codec;
    private final MediaMuxer muxer;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final int rate;
    private final int channels;
    private int track = -1;
    private boolean muxerStarted;
    private boolean closed;
    private long frames;

    AacWriter(File out, int rate, int channels, int bitRate) throws IOException {
        this.rate = rate;
        this.channels = channels;
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768);
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        codec.start();
    }

    void write(short[] pcm, int n) throws IOException {
        int offset = 0;
        int guard = 0;
        while (offset < n) {
            int idx = codec.dequeueInputBuffer(10000);
            if (idx < 0) {
                drain(false);
                if (++guard > 400) throw new IOException("encoder stalled");
                continue;
            }
            ByteBuffer ib = codec.getInputBuffer(idx);
            if (ib == null) throw new IOException("no input buffer");
            ib.clear();
            ib.order(ByteOrder.nativeOrder());
            int count = Math.min(n - offset, ib.capacity() / 2);
            count -= count % channels;
            if (count <= 0) throw new IOException("input buffer too small");
            ib.asShortBuffer().put(pcm, offset, count);
            long pts = frames * 1000000L / rate;
            codec.queueInputBuffer(idx, 0, count * 2, pts, 0);
            frames += count / channels;
            offset += count;
            drain(false);
        }
    }

    long frames() {
        return frames;
    }

    private void drain(boolean untilEos) throws IOException {
        int tries = 0;
        while (true) {
            int idx = codec.dequeueOutputBuffer(info, untilEos ? 10000 : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos) return;
                if (++tries > 300) throw new IOException("encoder did not finish");
                continue;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                continue;
            }
            if (idx < 0) continue;
            ByteBuffer ob = codec.getOutputBuffer(idx);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
            if (info.size > 0 && ob != null && muxerStarted) {
                ob.position(info.offset);
                ob.limit(info.offset + info.size);
                muxer.writeSampleData(track, ob, info);
            }
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(idx, false);
            if (eos) return;
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try {
            int guard = 0;
            int idx;
            while ((idx = codec.dequeueInputBuffer(10000)) < 0) {
                drain(false);
                if (++guard > 400) throw new IOException("encoder stalled");
            }
            codec.queueInputBuffer(idx, 0, 0, frames * 1000000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(true);
        } catch (IOException | RuntimeException e) {
            failure = e instanceof IOException ? (IOException) e : new IOException(e);
        } finally {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                codec.release();
            } catch (RuntimeException ignored) {
            }
            try {
                if (muxerStarted) muxer.stop();
            } catch (RuntimeException e) {
                if (failure == null) failure = new IOException(e);
            }
            try {
                muxer.release();
            } catch (RuntimeException ignored) {
            }
        }
        if (failure != null) throw failure;
    }
}
