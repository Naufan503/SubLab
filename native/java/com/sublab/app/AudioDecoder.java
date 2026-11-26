package com.sublab.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Decode audio/video apa pun yang didukung Android menjadi PCM float 16 kHz mono. */
final class AudioDecoder {
    static final int TARGET_RATE = 16000;

    interface Cancel {
        boolean cancelled();
    }

    private static final int PCM_16 = 2;     // AudioFormat.ENCODING_PCM_16BIT
    private static final int PCM_FLOAT = 4;  // AudioFormat.ENCODING_PCM_FLOAT

    private float[] out = new float[TARGET_RATE * 60];
    private int n = 0;
    private double outPos = 0;
    private double step = 1;
    private long srcIndex = 0;
    private float prev = 0;

    static float[] decode(String path, long offsetMs, long durationMs, Cancel cancel) throws IOException {
        return new AudioDecoder().run(path, offsetMs, durationMs, cancel);
    }

    private void push(float x) {
        if (srcIndex == 0) {
            prev = x;
            while (outPos <= 0) {
                add(x);
                outPos += step;
            }
        } else {
            while (outPos <= srcIndex) {
                double frac = outPos - (srcIndex - 1);
                add((float) (prev + (x - prev) * frac));
                outPos += step;
            }
            prev = x;
        }
        srcIndex++;
    }

    private void add(float v) {
        if (n == out.length) out = Arrays.copyOf(out, out.length * 2);
        out[n++] = v;
    }

    private float[] run(String path, long offsetMs, long durationMs, Cancel cancel) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(path);
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
            if (track < 0) throw new IOException("File ini tidak punya trek audio.");
            ex.selectTrack(track);
            if (offsetMs > 0) ex.seekTo(offsetMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            int srcRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int ch = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int enc = PCM_16;
            step = srcRate / (double) TARGET_RATE;

            long wantUs = durationMs > 0 ? durationMs * 1000L
                    : (fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) - offsetMs * 1000L : 0);
            if (wantUs > 0) out = new float[(int) Math.min(Integer.MAX_VALUE / 2, wantUs / 1000L * TARGET_RATE / 1000L + TARGET_RATE)];

            final long startUs = offsetMs * 1000L;
            final long endUs = durationMs > 0 ? (offsetMs + durationMs) * 1000L : Long.MAX_VALUE;

            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            while (!outDone) {
                if (cancel != null && cancel.cancelled()) break;
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int sz = ib == null ? -1 : ex.readSampleData(ib, 0);
                        long pts = ex.getSampleTime();
                        if (sz < 0 || pts > endUs) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, pts, 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = codec.getOutputBuffer(oi);
                        if (ob != null) {
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            ob.order(ByteOrder.nativeOrder());
                            consume(ob, ch, enc, srcRate, info.presentationTimeUs, startUs, endUs);
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = codec.getOutputFormat();
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        int nr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (nr != srcRate) { srcRate = nr; step = srcRate / (double) TARGET_RATE; }
                    }
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    enc = nf.containsKey("pcm-encoding") ? nf.getInteger("pcm-encoding") : PCM_16;
                }
            }
            return Arrays.copyOf(out, n);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new IOException("Decode audio gagal: " + e.getMessage(), e);
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) {}
            ex.release();
        }
    }

    private void consume(ByteBuffer ob, int ch, int enc, int srcRate, long ptsUs, long startUs, long endUs) {
        int bytes = enc == PCM_FLOAT ? 4 : 2;
        int frames = ob.remaining() / (bytes * Math.max(1, ch));
        long skip = ptsUs < startUs ? (long) ((startUs - ptsUs) * (double) srcRate / 1e6) : 0;
        long limit = endUs == Long.MAX_VALUE ? frames : (long) ((endUs - ptsUs) * (double) srcRate / 1e6);
        if (limit > frames) limit = frames;
        for (int f = 0; f < frames; f++) {
            float sum = 0;
            for (int c = 0; c < ch; c++) {
                sum += enc == PCM_FLOAT ? ob.getFloat() : ob.getShort() / 32768f;
            }
            if (f < skip || f >= limit) continue;
            push(sum / Math.max(1, ch));
        }
    }

    /** Normalisasi puncak dan/atau high-pass 80 Hz sederhana. */
    static void post(float[] a, boolean normalize, boolean denoise) {
        if (denoise && a.length > 1) {
            final double rc = 1.0 / (2 * Math.PI * 80), dt = 1.0 / TARGET_RATE, k = rc / (rc + dt);
            float px = a[0], py = 0;
            for (int i = 0; i < a.length; i++) {
                float x = a[i];
                float y = (float) (k * (py + x - px));
                px = x;
                py = y;
                a[i] = y;
            }
        }
        if (normalize) {
            float peak = 0;
            for (float v : a) peak = Math.max(peak, Math.abs(v));
            if (peak > 1e-4f) {
                float g = Math.min(20f, 0.95f / peak);
                for (int i = 0; i < a.length; i++) a[i] *= g;
            }
        }
    }

    private AudioDecoder() {}
}
