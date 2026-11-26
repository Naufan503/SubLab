#include <jni.h>
#include <string>
#include <algorithm>
#include <thread>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_autocaption_WhisperLib_init(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_com_example_autocaption_WhisperLib_free(JNIEnv *, jobject, jlong h) {
    if (h) whisper_free((whisper_context *) h);
}

// Hasil: baris "t0_ms \t t1_ms \t teks \n" dalam bytes UTF-8
JNIEXPORT jbyteArray JNICALL
Java_com_example_autocaption_WhisperLib_transcribe(JNIEnv *env, jobject, jlong h,
                                                   jfloatArray audio, jstring lang) {
    auto *ctx = (whisper_context *) h;
    jsize n = env->GetArrayLength(audio);
    jfloat *data = env->GetFloatArrayElements(audio, nullptr);
    const char *l = env->GetStringUTFChars(lang, nullptr);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.translate = false;
    wp.language = l;
    wp.n_threads = std::max(2, std::min(4, (int) std::thread::hardware_concurrency()));

    std::string out;
    if (whisper_full(ctx, wp, data, n) == 0) {
        int cnt = whisper_full_n_segments(ctx);
        for (int i = 0; i < cnt; i++) {
            std::string t = whisper_full_get_segment_text(ctx, i);
            std::replace(t.begin(), t.end(), '\n', ' ');
            out += std::to_string(whisper_full_get_segment_t0(ctx, i) * 10) + "\t" +
                   std::to_string(whisper_full_get_segment_t1(ctx, i) * 10) + "\t" + t + "\n";
        }
    }
    env->ReleaseStringUTFChars(lang, l);
    env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);

    jbyteArray res = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(res, 0, (jsize) out.size(), (const jbyte *) out.data());
    return res;
}

}
