package com.example.autocaption

object WhisperLib {
    init { System.loadLibrary("whisper_jni") }
    external fun init(modelPath: String): Long
    external fun free(handle: Long)
    external fun transcribe(handle: Long, audio: FloatArray, lang: String): ByteArray

    private fun ts(ms: Long): String {
        val h = ms / 3600000; val m = ms / 60000 % 60; val s = ms / 1000 % 60; val x = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, x)
    }

    /** Ubah hasil mentah jadi format SRT */
    fun toSrt(raw: ByteArray): String {
        val sb = StringBuilder()
        var no = 1
        String(raw, Charsets.UTF_8).lines().filter { it.isNotBlank() }.forEach {
            val p = it.split("\t", limit = 3)
            if (p.size == 3) {
                sb.append(no++).append('\n')
                    .append(ts(p[0].toLong())).append(" --> ").append(ts(p[1].toLong())).append('\n')
                    .append(p[2].trim()).append("\n\n")
            }
        }
        return sb.toString()
    }
}
