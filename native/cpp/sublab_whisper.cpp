// SubLab: jembatan JNI antara Java (WhisperEngine) dan whisper.cpp.
// Semua string dikirim sebagai byte[] UTF-8 supaya aman untuk karakter non-BMP (emoji, dst).
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <string>
#include <vector>
#include <cstring>
#include "whisper.h"

#define TAG "SubLabWhisper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::atomic<bool> g_cancel{false};

struct CbCtx {
    JNIEnv* env;
    jobject listener;
    jmethodID onProgress;
    jmethodID onSegment;
    int lastPct;
};

static std::string toStr(JNIEnv* env, jbyteArray a) {
    if (!a) return std::string();
    jsize n = env->GetArrayLength(a);
    std::string s((size_t)n, '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(&s[0]));
    return s;
}

static jbyteArray toBytes(JNIEnv* env, const std::string& s) {
    jbyteArray a = env->NewByteArray((jsize)s.size());
    if (a && !s.empty()) env->SetByteArrayRegion(a, 0, (jsize)s.size(), reinterpret_cast<const jbyte*>(s.data()));
    return a;
}

struct Word {
    std::string text;
    long long t0 = 0, t1 = 0;
};

static void onProgressCb(struct whisper_context*, struct whisper_state*, int progress, void* ud) {
    CbCtx* c = static_cast<CbCtx*>(ud);
    if (progress == c->lastPct) return;
    c->lastPct = progress;
    c->env->CallVoidMethod(c->listener, c->onProgress, (jint)progress);
}

static void onSegmentCb(struct whisper_context* ctx, struct whisper_state*, int n_new, void* ud) {
    CbCtx* c = static_cast<CbCtx*>(ud);
    JNIEnv* env = c->env;
    const int n = whisper_full_n_segments(ctx);
    const whisper_token eot = whisper_token_eot(ctx);
    jclass byteArr = env->FindClass("[B");

    for (int i = n - n_new; i < n; i++) {
        if (i < 0) continue;
        const long long s0 = whisper_full_get_segment_t0(ctx, i) * 10;
        const long long s1 = whisper_full_get_segment_t1(ctx, i) * 10;
        const char* txt = whisper_full_get_segment_text(ctx, i);

        // Gabungkan token menjadi kata: token berawalan spasi = kata baru.
        std::vector<Word> words;
        bool pending = true;
        const int nt = whisper_full_n_tokens(ctx, i);
        for (int j = 0; j < nt; j++) {
            whisper_token_data td = whisper_full_get_token_data(ctx, i, j);
            if (td.id >= eot) continue;  // token khusus / timestamp
            const char* p = whisper_full_get_token_text(ctx, i, j);
            if (!p) continue;
            std::string piece(p);
            size_t k = 0;
            while (k < piece.size() && (piece[k] == ' ' || piece[k] == '\n' || piece[k] == '\t')) { k++; pending = true; }
            piece.erase(0, k);
            if (piece.empty()) continue;
            long long a = td.t0 >= 0 ? td.t0 * 10 : s0;
            long long b = td.t1 >= 0 ? td.t1 * 10 : s1;
            if (b < a) b = a;
            if (pending || words.empty()) {
                Word w; w.text = piece; w.t0 = a; w.t1 = b;
                words.push_back(w);
                pending = false;
            } else {
                words.back().text += piece;
                if (b > words.back().t1) words.back().t1 = b;
            }
        }

        const jint wn = (jint)words.size();
        if (env->PushLocalFrame(wn + 32) != 0) continue;
        jbyteArray jt = toBytes(env, txt ? std::string(txt) : std::string());
        jlongArray ws = env->NewLongArray(wn);
        jlongArray we = env->NewLongArray(wn);
        jobjectArray wt = env->NewObjectArray(wn, byteArr, nullptr);
        std::vector<jlong> a(words.size()), b(words.size());
        for (size_t j = 0; j < words.size(); j++) {
            long long x = words[j].t0 < s0 ? s0 : words[j].t0;
            long long y = words[j].t1 > s1 ? s1 : words[j].t1;
            if (y < x) y = x;
            a[j] = x; b[j] = y;
            jbyteArray jw = toBytes(env, words[j].text);
            env->SetObjectArrayElement(wt, (jsize)j, jw);
        }
        if (wn > 0) {
            env->SetLongArrayRegion(ws, 0, wn, a.data());
            env->SetLongArrayRegion(we, 0, wn, b.data());
        }
        env->CallVoidMethod(c->listener, c->onSegment, (jlong)s0, (jlong)s1, jt, ws, we, wt);
        env->PopLocalFrame(nullptr);
    }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_sublab_app_WhisperEngine_nativeInit(JNIEnv* env, jclass, jbyteArray jpath, jbyteArray jdtw) {
    std::string path = toStr(env, jpath), dtw = toStr(env, jdtw);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = false;
    if (!dtw.empty()) {
        struct M { const char* n; whisper_alignment_heads_preset p; };
        static const M map[] = {
            {"tiny", WHISPER_AHEADS_TINY}, {"base", WHISPER_AHEADS_BASE}, {"small", WHISPER_AHEADS_SMALL},
            {"medium", WHISPER_AHEADS_MEDIUM}, {"large.v3", WHISPER_AHEADS_LARGE_V3}};
        for (const M& m : map) {
            if (dtw == m.n) { cp.dtw_token_timestamps = true; cp.dtw_aheads_preset = m.p; break; }
        }
    }
    whisper_context* ctx = whisper_init_from_file_with_params(path.c_str(), cp);
    if (!ctx) LOGE("gagal memuat model: %s", path.c_str());
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_sublab_app_WhisperEngine_nativeFree(JNIEnv*, jclass, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context*>(handle));
}

JNIEXPORT void JNICALL
Java_com_sublab_app_WhisperEngine_nativeCancel(JNIEnv*, jclass, jboolean v) {
    g_cancel.store(v == JNI_TRUE);
}

JNIEXPORT jstring JNICALL
Java_com_sublab_app_WhisperEngine_nativeInfo(JNIEnv* env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

// iv: 0 threads,1 translate,2 beam,3 bestOf,4 maxContext,5 audioCtx,6 maxLen,7 splitOnWord,
//     8 singleSegment,9 suppressNst,10 vadEnabled,11 vadMinSpeechMs,12 vadMinSilenceMs,13 vadPadMs
// fv: 0 temperature,1 tempInc,2 entropy,3 logprob,4 noSpeech,5 wordThold,6 vadThreshold
// sv: 0 language,1 prompt,2 suppressRegex,3 vadModelPath
JNIEXPORT jint JNICALL
Java_com_sublab_app_WhisperEngine_nativeRun(JNIEnv* env, jclass, jlong handle, jfloatArray jpcm,
                                            jintArray jiv, jfloatArray jfv, jobjectArray jsv, jobject listener) {
    whisper_context* ctx = reinterpret_cast<whisper_context*>(handle);
    if (!ctx) return -100;

    jint iv[16] = {0};
    jfloat fv[8] = {0};
    env->GetIntArrayRegion(jiv, 0, std::min<jsize>(16, env->GetArrayLength(jiv)), iv);
    env->GetFloatArrayRegion(jfv, 0, std::min<jsize>(8, env->GetArrayLength(jfv)), fv);
    std::string sv[4];
    for (jsize i = 0; i < 4 && i < env->GetArrayLength(jsv); i++) {
        jbyteArray a = (jbyteArray)env->GetObjectArrayElement(jsv, i);
        sv[i] = toStr(env, a);
        if (a) env->DeleteLocalRef(a);
    }

    const int beam = iv[2], bestOf = iv[3];
    whisper_full_params p = whisper_full_default_params(beam > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    p.n_threads = iv[0] > 0 ? iv[0] : 4;
    p.translate = iv[1] != 0;
    if (beam > 1) p.beam_search.beam_size = beam;
    p.greedy.best_of = bestOf > 0 ? bestOf : 1;
    if (iv[4] == 0) p.no_context = true;
    else if (iv[4] > 0) p.n_max_text_ctx = iv[4];
    p.audio_ctx = iv[5] > 0 ? iv[5] : 0;
    p.max_len = iv[6] > 0 ? iv[6] : 0;
    p.split_on_word = iv[7] != 0;
    p.single_segment = iv[8] != 0;
#ifdef SUBLAB_HAS_SUPPRESS_NST
    p.suppress_nst = iv[9] != 0;
#else
    p.suppress_non_speech_tokens = iv[9] != 0;
#endif
    p.temperature = fv[0];
    p.temperature_inc = fv[1];
    p.entropy_thold = fv[2];
    p.logprob_thold = fv[3];
    p.no_speech_thold = fv[4];
    p.thold_pt = fv[5];
    p.token_timestamps = true;  // dibutuhkan untuk waktu per kata
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.language = sv[0].empty() ? "auto" : sv[0].c_str();
    p.detect_language = false;
    if (!sv[1].empty()) p.initial_prompt = sv[1].c_str();
    if (!sv[2].empty()) p.suppress_regex = sv[2].c_str();
#ifdef SUBLAB_HAS_VAD
    if (iv[10] != 0 && !sv[3].empty()) {
        p.vad = true;
        p.vad_model_path = sv[3].c_str();
        p.vad_params.threshold = fv[6];
        p.vad_params.min_speech_duration_ms = iv[11];
        p.vad_params.min_silence_duration_ms = iv[12];
        p.vad_params.speech_pad_ms = iv[13];
    }
#endif

    CbCtx cb;
    cb.env = env;
    cb.listener = listener;
    jclass lc = env->GetObjectClass(listener);
    cb.onProgress = env->GetMethodID(lc, "onProgress", "(I)V");
    cb.onSegment = env->GetMethodID(lc, "onSegment", "(JJ[B[J[J[[B)V");
    cb.lastPct = -1;
    env->DeleteLocalRef(lc);
    if (!cb.onProgress || !cb.onSegment) return -101;

    p.progress_callback = onProgressCb;
    p.progress_callback_user_data = &cb;
    p.new_segment_callback = onSegmentCb;
    p.new_segment_callback_user_data = &cb;
    p.abort_callback = [](void*) -> bool { return g_cancel.load(); };
    p.abort_callback_user_data = nullptr;

    g_cancel.store(false);
    jsize n = env->GetArrayLength(jpcm);
    jfloat* pcm = env->GetFloatArrayElements(jpcm, nullptr);
    int ret = whisper_full(ctx, p, pcm, (int)n);
    env->ReleaseFloatArrayElements(jpcm, pcm, JNI_ABORT);
    return ret;
}

}  // extern "C"
