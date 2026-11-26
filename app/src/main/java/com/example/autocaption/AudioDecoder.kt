package com.example.autocaption

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Decode audio/video apa pun jadi PCM float mono 16 kHz (format yang dibutuhkan Whisper). */
object AudioDecoder {
    private class FloatBuf {
        var a = FloatArray(1 shl 20); var n = 0
        fun add(v: Float) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun result() = a.copyOf(n)
    }

    fun decode(ctx: Context, uri: Uri): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var idx = -1; var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")) { idx = i; fmt = f; break }
        }
        require(idx >= 0 && fmt != null) { "File tidak punya track audio" }
        ex.selectTrack(idx)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()

        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val out = FloatBuf()
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        var n = 0L; var nextOut = 0L

        while (!outDone) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10000)
                if (i >= 0) {
                    val b = codec.getInputBuffer(i)!!
                    val sz = ex.readSampleData(b, 0)
                    if (sz < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, sz, ex.sampleTime, 0); ex.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10000)
            if (o >= 0) {
                val b = codec.getOutputBuffer(o)!!
                b.position(info.offset); b.limit(info.offset + info.size)
                val sb = b.order(ByteOrder.nativeOrder()).asShortBuffer()
                val step = rate / 16000.0
                while (sb.remaining() >= ch) {
                    var sum = 0f
                    for (c in 0 until ch) sum += sb.get() / 32768f
                    val s = sum / ch
                    while (nextOut * step <= n) { out.add(s); nextOut++ }
                    n++
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val nf = codec.outputFormat
                rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }
        codec.stop(); codec.release(); ex.release()
        return out.result()
    }
}
