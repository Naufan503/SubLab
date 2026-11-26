package com.example.autocaption

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var pick: Button
    private lateinit var save: Button
    private lateinit var lang: Spinner
    private var srt = ""
    private val langs = listOf("auto", "id", "en", "ja", "ko", "zh", "ar", "es", "fr", "de")
    private val modelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) process(uri)
    }
    private val saver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { it.write(srt.toByteArray()) }
            toast("Tersimpan")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        root.addView(TextView(this).apply { text = "Auto Caption (Whisper)"; textSize = 22f })
        lang = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, langs) }
        root.addView(TextView(this).apply { text = "Bahasa audio (auto = deteksi otomatis):" })
        root.addView(lang)
        pick = Button(this).apply { text = "Pilih file audio / video"; setOnClickListener { picker.launch(arrayOf("audio/*", "video/*")) } }
        root.addView(pick)
        status = TextView(this).apply { text = "Siap. Model diunduh otomatis saat pertama kali (~140 MB)."; setPadding(0, pad, 0, pad) }
        root.addView(status)
        save = Button(this).apply { text = "Simpan sebagai .srt"; isEnabled = false; setOnClickListener { saver.launch("caption.srt") } }
        root.addView(save)
        result = TextView(this).apply { setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(result) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun process(uri: Uri) {
        pick.isEnabled = false; save.isEnabled = false; result.text = ""
        val l = langs[lang.selectedItemPosition]
        Thread {
            try {
                val model = File(filesDir, "ggml-base.bin")
                if (!model.exists()) download(model)
                setStatus("Membaca audio...")
                val audio = AudioDecoder.decode(this, uri)
                setStatus("Transkripsi berjalan (${audio.size / 16000} detik audio), mohon tunggu...")
                val h = WhisperLib.init(model.absolutePath)
                if (h == 0L) throw RuntimeException("Gagal memuat model")
                val raw = WhisperLib.transcribe(h, audio, l)
                WhisperLib.free(h)
                srt = WhisperLib.toSrt(raw)
                runOnUiThread {
                    result.text = srt.ifBlank { "(tidak ada ucapan terdeteksi)" }
                    save.isEnabled = srt.isNotBlank()
                }
                setStatus("Selesai.")
            } catch (e: Throwable) {
                setStatus("Error: ${e.message}")
            } finally {
                runOnUiThread { pick.isEnabled = true }
            }
        }.start()
    }

    private fun download(dest: File) {
        val tmp = File(dest.path + ".tmp")
        val c = URL(modelUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        val total = c.contentLengthLong
        c.inputStream.use { inp ->
            tmp.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L; var last = 0L
                while (true) {
                    val r = inp.read(buf); if (r < 0) break
                    o.write(buf, 0, r); done += r
                    if (done - last > 2_000_000) { last = done; setStatus("Mengunduh model... ${done / 1_000_000} / ${total / 1_000_000} MB") }
                }
            }
        }
        tmp.renameTo(dest)
    }
}
