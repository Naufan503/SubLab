package com.sublab.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Plugin Capacitor "Whisper": kontraknya sama dengan mesin simulasi di kf-caption.html (SIMW). */
@CapacitorPlugin(name = "Whisper")
public class WhisperPlugin extends Plugin {
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String, AtomicInteger> downloads = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private volatile boolean cancelRun = false;

    private Context ctx() { return getContext(); }

    private PowerManager.WakeLock lock(String tag) {
        try {
            PowerManager pm = (PowerManager) ctx().getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sublab:" + tag);
            wl.acquire(6L * 60 * 60 * 1000);
            return wl;
        } catch (Exception e) {
            return null;
        }
    }

    private static void unlock(PowerManager.WakeLock wl) {
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) {}
    }

    // ---------- info perangkat & model ----------

    @PluginMethod
    public void getDeviceInfo(PluginCall call) {
        File d = ctx().getFilesDir();
        ActivityManager am = (ActivityManager) ctx().getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        android.os.StatFs st = new android.os.StatFs(d.getPath());
        JSObject r = new JSObject();
        r.put("storageFreeMB", st.getAvailableBytes() / ModelStore.MB);
        r.put("storageTotalMB", st.getTotalBytes() / ModelStore.MB);
        r.put("ramTotalMB", mi.totalMem / ModelStore.MB);
        r.put("ramFreeMB", mi.availMem / ModelStore.MB);
        r.put("cpuCores", Runtime.getRuntime().availableProcessors());
        call.resolve(r);
    }

    @PluginMethod
    public void listModels(PluginCall call) {
        JSArray arr = new JSArray();
        for (ModelStore.Spec s : ModelStore.SPECS) {
            JSObject o = new JSObject();
            o.put("name", s.name);
            o.put("installed", ModelStore.installed(ctx(), s));
            o.put("partialMB", ModelStore.partialMB(ctx(), s));
            o.put("sizeMB", s.sizeMB);
            arr.put(o);
        }
        JSObject r = new JSObject();
        r.put("models", arr);
        call.resolve(r);
    }

    @PluginMethod
    public void downloadModel(PluginCall call) {
        final String name = call.getString("name");
        final ModelStore.Spec s = name == null ? null : ModelStore.find(name);
        if (s == null) { call.reject("Model tidak dikenal", "UNKNOWN"); return; }
        final AtomicInteger mode = new AtomicInteger(0);
        if (downloads.putIfAbsent(name, mode) != null) { call.reject("Sudah diunduh", "BUSY"); return; }
        getBridge().saveCall(call);
        io.execute(() -> {
            PowerManager.WakeLock wl = lock("dl");
            try {
                ModelStore.download(ctx(), s, mode, (n, pct, done, total, sp) -> {
                    JSObject e = new JSObject();
                    e.put("name", n);
                    e.put("percent", pct);
                    e.put("downloadedMB", done);
                    e.put("totalMB", total);
                    e.put("speedMBps", sp);
                    notifyListeners("dlprogress", e);
                });
                JSObject r = new JSObject();
                r.put("name", name);
                call.resolve(r);
            } catch (ModelStore.Canceled e) {
                call.reject("Unduhan dibatalkan", "CANCELED");
            } catch (ModelStore.NoSpace e) {
                call.reject(e.getMessage(), "NO_SPACE");
            } catch (Exception e) {
                call.reject("Unduhan gagal: " + e.getMessage(), "DOWNLOAD");
            } finally {
                downloads.remove(name);
                unlock(wl);
                getBridge().releaseCall(call);
            }
        });
    }

    @PluginMethod
    public void cancelDownload(PluginCall call) {
        String name = call.getString("name");
        boolean del = Boolean.TRUE.equals(call.getBoolean("deletePartial", false));
        AtomicInteger m = name == null ? null : downloads.get(name);
        if (m != null) m.set(del ? 2 : 1);
        else if (del && name != null) {
            ModelStore.Spec s = ModelStore.find(name);
            if (s != null) ModelStore.part(ctx(), s).delete();
        }
        call.resolve();
    }

    @PluginMethod
    public void clearPartial(PluginCall call) {
        String name = call.getString("name");
        ModelStore.Spec s = name == null ? null : ModelStore.find(name);
        if (s != null && !downloads.containsKey(name)) ModelStore.part(ctx(), s).delete();
        call.resolve();
    }

    @PluginMethod
    public void deleteModel(PluginCall call) {
        String name = call.getString("name");
        ModelStore.Spec s = name == null ? null : ModelStore.find(name);
        if (s != null) {
            ModelStore.file(ctx(), s).delete();
            ModelStore.part(ctx(), s).delete();
        }
        call.resolve();
    }

    // ---------- unggah media dari WebView ke cache (File/Blob tidak bisa lewat bridge) ----------

    private File mediaDir() {
        File d = new File(ctx().getCacheDir(), "media_in");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    @PluginMethod
    public void openStream(PluginCall call) {
        File d = mediaDir();
        File[] old = d.listFiles();
        if (old != null) for (File f : old) f.delete();
        String name = call.getString("name", "media");
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).replaceAll("[^A-Za-z0-9.]", "") : "";
        File f = new File(d, "in_" + System.currentTimeMillis() + ext);
        try {
            if (!f.createNewFile()) throw new Exception("tidak bisa membuat file");
        } catch (Exception e) {
            call.reject("Gagal membuat file sementara: " + e.getMessage());
            return;
        }
        JSObject r = new JSObject();
        r.put("path", f.getAbsolutePath());
        call.resolve(r);
    }

    @PluginMethod
    public void writeChunk(PluginCall call) {
        try {
            File f = new File(call.getString("path", "")).getCanonicalFile();
            if (!f.getPath().startsWith(mediaDir().getCanonicalPath())) { call.reject("Path tidak valid"); return; }
            byte[] b = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
            try (FileOutputStream fo = new FileOutputStream(f, true)) { fo.write(b); }
            call.resolve();
        } catch (Exception e) {
            call.reject("Gagal menulis file sementara: " + e.getMessage(), "NO_SPACE".equals(e.getMessage()) ? "NO_SPACE" : null);
        }
    }

    // ---------- transkripsi ----------

    @PluginMethod
    public void cancel(PluginCall call) {
        cancelRun = true;
        WhisperEngine.nativeCancel(true);
        call.resolve();
    }

    @PluginMethod
    public void transcribe(PluginCall call) {
        final String file = call.getString("file");
        final String model = call.getString("model");
        final String lang = call.getString("language", "auto");
        final JSObject o = call.getObject("options", new JSObject());
        final ModelStore.Spec spec = model == null ? null : ModelStore.find(model);
        if (file == null || !new File(file).exists()) { call.reject("File media tidak ditemukan", "NO_FILE"); return; }
        if (spec == null || !ModelStore.installed(ctx(), spec)) { call.reject("Model belum terpasang", "NO_MODEL"); return; }
        if (running) { call.reject("Transkripsi lain masih berjalan", "BUSY"); return; }
        running = true;
        cancelRun = false;
        WhisperEngine.nativeCancel(false);
        getBridge().saveCall(call);
        worker.execute(() -> {
            PowerManager.WakeLock wl = lock("run");
            long handle = 0;
            try {
                runTranscribe(call, file, spec, lang, o);
            } catch (Throwable t) {
                call.reject("Transkripsi gagal: " + t.getMessage(), "RUN");
            } finally {
                new File(file).delete();
                unlock(wl);
                running = false;
                getBridge().releaseCall(call);
            }
        });
    }

    private long rssMB() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/status"))) {
            String l;
            while ((l = br.readLine()) != null) {
                if (l.startsWith("VmRSS:")) return Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private void emitProgress(String phase, int pct, int cpu, double speedX) {
        ActivityManager am = (ActivityManager) ctx().getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        JSObject p = new JSObject();
        p.put("phase", phase);
        p.put("percent", pct);
        p.put("cpu", cpu);
        p.put("ramMB", rssMB());
        p.put("ramTotalMB", mi.totalMem / ModelStore.MB);
        p.put("freeMB", ModelStore.freeMB(ctx()));
        if (speedX > 0) p.put("speedX", Math.round(speedX * 10) / 10.0);
        notifyListeners("progress", p);
    }

    private static byte[] b(String s) { return (s == null ? "" : s).getBytes(StandardCharsets.UTF_8); }

    private void runTranscribe(PluginCall call, String file, ModelStore.Spec spec, String lang, JSObject o) throws Exception {
        final long offsetMs = o.optLong("offsetMs", 0), durationMs = o.optLong("durationMs", 0);
        emitProgress("load", 0, 0, 0);

        float[] pcm = AudioDecoder.decode(file, offsetMs, durationMs, () -> cancelRun);
        if (cancelRun) { finish(call, 0); return; }
        if (pcm.length < AudioDecoder.TARGET_RATE / 10) { call.reject("Audio terlalu pendek atau kosong", "EMPTY"); return; }
        AudioDecoder.post(pcm, o.optBoolean("normalize", false), o.optBoolean("denoise", false));
        final double audioSec = pcm.length / (double) AudioDecoder.TARGET_RATE;

        emitProgress("load", 0, 0, 0);
        String dtw = o.optString("dtw", "");
        long handle = WhisperEngine.nativeInit(b(ModelStore.file(ctx(), spec).getAbsolutePath()), b(dtw == null || "null".equals(dtw) ? "" : dtw));
        if (handle == 0) {
            call.reject("Gagal memuat model. RAM mungkin kurang atau file rusak; coba model lebih kecil atau unduh ulang.", "LOAD");
            return;
        }
        try {
            JSONObject vad = o.optJSONObject("vad");
            boolean vadOn = vad != null && vad.optBoolean("enabled", false);
            ModelStore.Spec vs = ModelStore.find(ModelStore.VAD_NAME);
            File vadFile = ModelStore.file(ctx(), vs);
            if (vadOn && !vadFile.exists()) vadOn = false;

            int[] iv = new int[16];
            iv[0] = Math.max(1, o.optInt("threads", 4));
            iv[1] = o.optBoolean("translate", false) ? 1 : 0;
            iv[2] = o.optInt("beamSize", 5);
            iv[3] = o.optInt("bestOf", 5);
            iv[4] = o.optInt("maxContext", -1);
            iv[5] = o.optInt("audioCtx", 0);
            iv[6] = o.optInt("maxLen", 0);
            iv[7] = o.optBoolean("splitOnWord", false) ? 1 : 0;
            iv[8] = o.optBoolean("singleSegment", false) ? 1 : 0;
            iv[9] = o.optBoolean("suppressNst", true) ? 1 : 0;
            iv[10] = vadOn ? 1 : 0;
            iv[11] = vad == null ? 250 : vad.optInt("minSpeechMs", 250);
            iv[12] = vad == null ? 100 : vad.optInt("minSilenceMs", 100);
            iv[13] = vad == null ? 30 : vad.optInt("padMs", 30);
            float[] fv = {
                    (float) o.optDouble("temperature", 0), (float) o.optDouble("temperatureInc", 0.2),
                    (float) o.optDouble("entropyThold", 2.4), (float) o.optDouble("logprobThold", -1),
                    (float) o.optDouble("noSpeechThold", 0.6), (float) o.optDouble("wordThold", 0.01),
                    vad == null ? 0.5f : (float) vad.optDouble("threshold", 0.5)};
            byte[][] sv = {b(lang), b(o.optString("prompt", "")), b(o.optString("suppressRegex", "")),
                    b(vadFile.getAbsolutePath())};

            final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            final long runStart = SystemClock.elapsedRealtime();
            final long[] prev = {Process.getElapsedCpuTime(), runStart};
            final int[] segCount = {0};

            WhisperEngine.Listener l = new WhisperEngine.Listener() {
                @Override
                public void onProgress(int percent) {
                    long cpuNow = Process.getElapsedCpuTime(), wallNow = SystemClock.elapsedRealtime();
                    int cpu = (int) Math.max(0, Math.min(100, Math.round(100.0 * (cpuNow - prev[0]) / Math.max(1, wallNow - prev[1]) / cores)));
                    prev[0] = cpuNow;
                    prev[1] = wallNow;
                    double el = (wallNow - runStart) / 1000.0;
                    double sx = percent > 2 && el > 0 ? audioSec * percent / 100.0 / el : 0;
                    emitProgress("run", percent, cpu, sx);
                }

                @Override
                public void onSegment(long t0, long t1, byte[] text, long[] ws, long[] we, byte[][] words) {
                    String t = new String(text, StandardCharsets.UTF_8).trim();
                    if (t.isEmpty()) return;
                    JSObject s = new JSObject();
                    s.put("start", t0 + offsetMs);
                    s.put("end", t1 + offsetMs);
                    s.put("text", t);
                    JSArray wa = new JSArray();
                    for (int i = 0; i < words.length; i++) {
                        String w = new String(words[i], StandardCharsets.UTF_8).trim();
                        if (w.isEmpty()) continue;
                        JSObject wo = new JSObject();
                        wo.put("w", w);
                        wo.put("start", ws[i] + offsetMs);
                        wo.put("end", we[i] + offsetMs);
                        wa.put(wo);
                    }
                    s.put("words", wa);
                    segCount[0]++;
                    notifyListeners("segment", s);
                }
            };

            emitProgress("run", 0, 0, 0);
            int ret = cancelRun ? 0 : WhisperEngine.nativeRun(handle, pcm, iv, fv, sv, l);
            if (ret != 0 && !cancelRun && segCount[0] == 0) {
                call.reject("whisper.cpp gagal (kode " + ret + ")", "RUN");
                return;
            }
            finish(call, segCount[0]);
        } finally {
            WhisperEngine.nativeFree(handle);
        }
    }

    private void finish(PluginCall call, int segments) {
        emitProgress("done", cancelRun ? 0 : 100, 0, 0);
        JSObject r = new JSObject();
        r.put("segments", segments);
        r.put("canceled", cancelRun);
        call.resolve(r);
    }

    @Override
    protected void handleOnDestroy() {
        cancelRun = true;
        WhisperEngine.nativeCancel(true);
        io.shutdownNow();
        worker.shutdownNow();
    }
}
