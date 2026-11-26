package com.sublab.app;

import android.content.Context;
import android.os.StatFs;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** Daftar model, penyimpanan, dan unduhan (dapat dilanjutkan) dari Hugging Face. */
final class ModelStore {
    static final long MB = 1024L * 1024L;
    static final long RESERVE_MB = 300;

    static final class Spec {
        final String name, file, url;
        final long sizeMB;

        Spec(String name, long sizeMB, String file, String url) {
            this.name = name;
            this.sizeMB = sizeMB;
            this.file = file;
            this.url = url;
        }
    }

    static final class Canceled extends IOException {
        Canceled() { super("Unduhan dibatalkan"); }
    }

    static final class NoSpace extends IOException {
        NoSpace(String m) { super(m); }
    }

    interface Progress {
        void onProgress(String name, int percent, long doneMB, long totalMB, double speedMBps);
    }

    private static final String HF = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-";
    static final String VAD_NAME = "vad-silero";

    static final Spec[] SPECS = {
            m("tiny", 75), m("tiny-q5_1", 31), m("base", 142), m("base-q5_1", 57),
            m("small", 466), m("small-q5_1", 181), m("medium", 1500), m("medium-q5_0", 514),
            m("large-v2", 2900), m("large-v2-q5_0", 1100), m("large-v3", 2900), m("large-v3-q5_0", 1100),
            m("large-v3-turbo", 1600), m("large-v3-turbo-q5_0", 547), m("large-v3-turbo-q8_0", 834),
            new Spec(VAD_NAME, 1, "ggml-silero-v5.1.2.bin",
                    "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin")
    };

    private static Spec m(String n, long mb) {
        return new Spec(n, mb, "ggml-" + n + ".bin", HF + n + ".bin");
    }

    static Spec find(String name) {
        for (Spec s : SPECS) if (s.name.equals(name)) return s;
        return null;
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "models");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File file(Context c, Spec s) { return new File(dir(c), s.file); }

    static File part(Context c, Spec s) { return new File(dir(c), s.file + ".part"); }

    static boolean installed(Context c, Spec s) {
        File f = file(c, s);
        return f.exists() && f.length() > 0;
    }

    static long partialMB(Context c, Spec s) {
        File p = part(c, s);
        return p.exists() ? Math.round(p.length() / (double) MB) : 0;
    }

    static long freeMB(Context c) {
        return new StatFs(c.getFilesDir().getPath()).getAvailableBytes() / MB;
    }

    static String fmtMB(long mb) {
        return mb >= 1000 ? String.format(new Locale("id"), "%.1f GB", mb / 1000.0) : mb + " MB";
    }

    static void download(Context c, Spec s, AtomicInteger cancel, Progress cb) throws IOException {
        File out = file(c, s), part = part(c, s);
        if (out.exists() && out.length() > 0) return;
        long have = part.exists() ? part.length() : 0;

        HttpURLConnection con = (HttpURLConnection) new URL(s.url).openConnection();
        con.setConnectTimeout(20000);
        con.setReadTimeout(30000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "SubLab/1.0");
        if (have > 0) con.setRequestProperty("Range", "bytes=" + have + "-");
        try {
            int code = con.getResponseCode();
            if (code == 416 && have > 0) {  // potongan sudah utuh
                if (!part.renameTo(out)) throw new IOException("Gagal memindahkan file model.");
                return;
            }
            if (code != 200 && code != 206) throw new IOException("Server menjawab HTTP " + code);
            boolean resume = code == 206 && have > 0;
            if (!resume) have = 0;
            long len = con.getContentLengthLong();
            long total = len > 0 ? have + len : s.sizeMB * MB;

            long needMB = Math.max(0, (total - have) / MB) + RESERVE_MB, freeMB = freeMB(c);
            if (freeMB < needMB) {
                throw new NoSpace("Penyimpanan tidak cukup: butuh " + fmtMB(needMB) + " (sisa model "
                        + fmtMB(Math.max(0, (total - have) / MB)) + " + cadangan " + fmtMB(RESERVE_MB)
                        + "), tersedia " + fmtMB(freeMB));
            }

            long done = have, lastBytes = have, lastT = SystemClock.elapsedRealtime();
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = con.getInputStream(); FileOutputStream fo = new FileOutputStream(part, resume)) {
                int r;
                while ((r = in.read(buf)) > 0) {
                    if (cancel.get() != 0) break;
                    fo.write(buf, 0, r);
                    done += r;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastT >= 400) {
                        double sp = (done - lastBytes) / (double) MB / ((now - lastT) / 1000.0);
                        cb.onProgress(s.name, (int) Math.min(100, done * 100 / Math.max(1, total)),
                                done / MB, total / MB, Math.round(sp * 10) / 10.0);
                        lastBytes = done;
                        lastT = now;
                    }
                }
            } catch (IOException e) {
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("ENOSPC") || msg.toLowerCase(Locale.ROOT).contains("no space")) {
                    throw new NoSpace("Penyimpanan penuh, unduhan dihentikan. Potongan disimpan, bisa dilanjutkan.");
                }
                throw e;
            }
            int mode = cancel.get();
            if (mode != 0) {
                if (mode == 2) part.delete();
                throw new Canceled();
            }
            if (len > 0 && part.length() != total) throw new IOException("Unduhan tidak lengkap, coba lagi untuk melanjutkan.");
            cb.onProgress(s.name, 100, total / MB, total / MB, 0);
            if (!part.renameTo(out)) throw new IOException("Gagal memindahkan file model.");
        } finally {
            con.disconnect();
        }
    }

    private ModelStore() {}
}
