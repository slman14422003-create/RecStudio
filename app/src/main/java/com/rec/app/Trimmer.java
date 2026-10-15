package com.rec.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class Trimmer {

    private Trimmer() {}

    public static long trim(File src, File dst, long startUs, long endUs) {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        long result = -1;
        try {
            extractor.setDataSource(src.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) return -1;
            extractor.selectTrack(track);
            muxer = new MediaMuxer(dst.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int out = muxer.addTrack(format);
            muxer.start();
            started = true;
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            int maxSize = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
            if (maxSize < 65536) maxSize = 65536;
            ByteBuffer buffer = ByteBuffer.allocate(maxSize);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long first = -1;
            long last = 0;
            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                long t = extractor.getSampleTime();
                if (t > endUs) break;
                if (first < 0) first = t;
                int flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, size, t - first, flags);
                muxer.writeSampleData(out, buffer, info);
                last = t;
                extractor.advance();
            }
            if (first >= 0) result = (last - first) / 1000 + 23;
        } catch (IOException | RuntimeException e) {
            result = -1;
        } finally {
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } catch (RuntimeException e) {
                    result = -1;
                }
                try {
                    muxer.release();
                } catch (RuntimeException ignored) {
                }
            }
            extractor.release();
        }
        return result;
    }
}
