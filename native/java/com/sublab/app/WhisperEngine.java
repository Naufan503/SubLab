package com.sublab.app;

/** Pembungkus JNI untuk whisper.cpp (lihat native/cpp/sublab_whisper.cpp). */
final class WhisperEngine {
    static {
        System.loadLibrary("sublab_whisper");
    }

    interface Listener {
        void onProgress(int percent);

        /** Waktu dalam milidetik relatif terhadap awal audio yang diproses. Teks UTF-8. */
        void onSegment(long t0Ms, long t1Ms, byte[] text, long[] wordStart, long[] wordEnd, byte[][] words);
    }

    static native long nativeInit(byte[] modelPath, byte[] dtwPreset);

    static native void nativeFree(long handle);

    static native void nativeCancel(boolean cancel);

    static native String nativeInfo();

    /**
     * iv: 0 threads,1 translate,2 beam,3 bestOf,4 maxContext,5 audioCtx,6 maxLen,7 splitOnWord,
     *     8 singleSegment,9 suppressNst,10 vadEnabled,11 vadMinSpeechMs,12 vadMinSilenceMs,13 vadPadMs
     * fv: 0 temperature,1 tempInc,2 entropy,3 logprob,4 noSpeech,5 wordThold,6 vadThreshold
     * sv: 0 language,1 prompt,2 suppressRegex,3 vadModelPath
     */
    static native int nativeRun(long handle, float[] pcm, int[] iv, float[] fv, byte[][] sv, Listener listener);

    private WhisperEngine() {}
}
