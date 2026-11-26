# SubLab — Spesifikasi

Aplikasi Android offline: audio/video → transkrip Whisper → caption berstempel waktu → export `.kf` untuk Keyflow.

## 1. Arsitektur
- UI: satu file HTML (`kf-caption.html`), tanpa dependensi.
- Dibungkus APK dengan Capacitor.
- Mesin: whisper.cpp (JNI) lewat plugin Capacitor `Whisper`. Audio didecode dengan MediaExtractor/MediaCodec ke PCM 16 kHz mono.
- Tahap sekarang: UI + export `.kf` (impor SRT/VTT dan JSON `whisper.cpp -ojf`) dan **plugin native sudah ditulis** (lihat bagian 19). Build APK lewat GitHub Actions; belum pernah dikompilasi/diuji di perangkat saat bagian ini ditulis.

## 2. Alur layar (atas → bawah)
1. Preview: tab **Biasa** (video/ikon musik + teks) dan **Preset .kf** (kanvas 9:16 dengan teks di posisi/ukuran preset).
   Catatan: "Preview kasar, gerakan dan efek tidak ditampilkan. Hanya untuk cek ukuran, panjang, dan posisi teks."
   Preview mengikuti **Gaya teks** (lihat bagian 6): per kata menampilkan satu kata, bertahap menampilkan kata yang sudah diucapkan.
2. Slider durasi (resolusi 10.000 langkah, bisa diklik dan digeser; tidak ditimpa pemutar saat digeser).
3. Satu baris kontrol, tiga kelompok: kiri tombol alat (toggle) berurutan 🗑 hapus, ✂ potong, ⛓ gabung, ⏱ waktu A/B, ikon balok kata = waktu per kata (hijau saat aktif, bagian 13), dan **⧉ copy/duplikat caption** (di paling kanan kelompok alat, bagian 5c); tengah ⏮ awal, ◀ caption sebelumnya, ▶/⏸, ▶ caption berikutnya, ⏭ akhir; kanan ↶ undo, ↷ redo.
4. Daftar caption (gaya Spotify): baris aktif (yang sedang di playhead) disorot dan auto-scroll. Teks mentah, ketuk untuk edit (baris itu otomatis jadi aktif, playhead pindah ke awalnya). Ketuk waktu di kiri baris untuk loncat ke sana.
   Alat 🗑 ✂ ⛓ ⏱ ⧉ adalah toggle (🗑 dan ⧉ dikecualikan dari aturan "hanya baris aktif": lihat bagian 5a & 5c). Saat menyala, tampilannya hanya muncul di baris aktif, dan pindah mengikuti baris aktif berikutnya/sebelumnya; baris lain tampil polos. Alat tetap menyala sampai dimatikan.
   Preset: tiap baris hanya menampilkan satu angka (preset yang dipakai, oranye). Ketuk angka itu untuk membuka pilihan semua preset, ketuk salah satu untuk memilih lalu pilihan menutup.
   Di **pojok kanan bawah** area daftar caption (tepat di atas panel bawah `#bot`), melayang tombol/panel semi-transparan **Aa** (`#bFmt`, bagian 5d) untuk shortcut format teks & cari-ganti.
5. Bawah: impor media/template/SRT/VTT/JSON (tombol Template menampilkan jumlah preset terdeteksi), menu lipat ⚙ Opsi (mode preset, animasi, **gaya teks**, lebar maks, **pecah / auto-crop**, offset, loop, serta tombol merah **Reset transkrip mentah**), info, tombol **Export .kf**, tombol **Lainnya ▾** (berisi export SRT, VTT, ASS, LRC, TXT, JSON per kata, dan **File transkrip mentah**), dan di pojok kanan bawah tombol **Posisi** (ikon + bersegitiga, bagian 5b).

Sebelum ada file, area tengah berisi panduan. Setelah media masuk: manajer model, ditambah tombol **Muat contoh JSON** (tambahan untuk uji) untuk simulasi hasil Whisper per kata langsung di browser tanpa APK. Saat berjalan: panel mirip CLI (bar, %, CPU, waktu, teks hasil, tombol batal).

## 3. Model & kontrak plugin `Whisper`
Daftar: tiny, base, small, medium, large-v2, large-v3, large-v3-turbo, beserta varian q5/q8 (ukuran perkiraan, 31 MB – 2,9 GB).
Tiap model: unduh (dari `huggingface.co/ggerganov/whisper.cpp`), batal, hapus, pakai. Model multibahasa (bukan `.en`). Bahasa: otomatis / id / en.

Kontrak plugin:
`listModels() → {models:[{name,installed}]}`, `downloadModel({name})`, `cancelDownload({name})`, `deleteModel({name})`,
`transcribe({file,model,language})`, `cancel()`.
Event: `progress {percent,cpu}`, `segment {start,end,text,words?:[{w,start,end}]}` (ms), `dlprogress {name,percent}`.

## 4. Struktur data segmen, impor JSON `-ojf`, sinkronisasi kata
Setiap item di `S.segs`:
`{ start, end, text, preset, words: [{ w, start, end }, ...] }` (ms).

Invarian: jumlah `words` sama dengan jumlah kata di `text` (dipisah spasi/whitespace). Bila tidak sama, `words` dibangun ulang proporsional. Waktu kata dijaga di dalam `[start, end]` dan tidak mundur.

**Impor JSON `whisper.cpp -ojf`** (terdeteksi di `parseSub(t)` bila isi diawali `{`/`[`; memakai array `transcription`; input `#iSrt`):
- Tiap segmen: `offsets.from` → `start`, `offsets.to` → `end`, `text`.
- Array `tokens` digabung jadi `words`:
  - token sistem berformat `[_...]` (`[_BEG_]`, `[_TT_...]`) diabaikan;
  - token yang diawali spasi memulai kata baru;
  - token tanpa spasi di awal (subkata, tanda baca) digabung ke kata sebelumnya, dan `end` kata diperbarui ke `offsets.to` token tersebut;
  - token hanya-spasi membuat token berikutnya memulai kata baru.
- Segmen tanpa `tokens` memakai pembagian proporsional.

**Fallback SRT/VTT dan edit teks manual:** `words` dihitung dengan membagi `[start, end]` proporsional terhadap jumlah huruf tiap kata (rumus yang sama dengan `cut`). Mengedit teks baris memakai `remapWords(ow, tk, s)` bila segmen `real` (lihat bawah); bila tidak `real`, `words` dihitung ulang proporsional.

**Edit teks pada segmen `real` (`remapWords`):** data kata transkrip di awal sesi edit disimpan (`x._ow`) dan dipakai sebagai dasar tiap ketukan, jadi hasilnya tidak bergantung pada keadaan sementara saat mengetik. Penanda `real` tetap `true`.
- Jumlah kata sama: timestamp transkrip dipakai per indeks, hanya teks kata diganti (typo di tengah tidak mengubah highlight).
- Jumlah kata beda: kata awal dan akhir yang masih sama (tanpa membedakan huruf besar/kecil) mempertahankan timestamp aslinya (awalan dan akhiran cocok). Kata di bagian tengah yang berubah dibagi proporsional per jumlah huruf di rentang waktu bagian tengah lama. Bila bagian lama kosong (murni penambahan), satu kata tetangga ikut dihitung ulang supaya ada rentang. Penghapusan murni hanya membuang kata, sisanya tetap.
- Hasil dijaga tidak mundur (`start` kata ≥ `end` kata sebelumnya, mulai dari `s.start`).

**Sinkronisasi alat edit:**
- `cut(i, off)`: bila titik potong tepat di batas antar kata, titik pisah `m = words[k-1].end` (dibatasi `s.start+50 ≤ m ≤ s.end−50`); `words` dibagi ke dua bagian. Bila memotong di tengah kata, waktu dibagi proporsional huruf dan `words` kedua bagian dibangun ulang.
- `mg(i)`: `words` kedua segmen digabung.
- `setT` dan slider transisi (mode 1): `words` diskala proporsional ke rentang baru.
- Offset waktu: `words` ikut bergeser.
- Undo/redo ikut menyimpan `words` (bagian dari snapshot `S.segs`).

## 5. Potong, ubah waktu, gabung, pecah / auto-crop
**Potong (✂):** tekan ✂ di baris. Teks tetap tampil seperti biasa, ditambah satu garis vertikal dengan kepala ikon gunting di atasnya (seperti playhead After Effects).
- Geser garis ke titik potong. Pegangan `///` di bawah garis (ukuran sama dengan kepala gunting) dipakai menggeser: jari tetap di pegangan, garis mengikuti dengan jarak relatif yang sama (tidak meloncat ke posisi jari). Menggeser ke bawah/atas pindah antarbaris. Ketuk pegangan = ganti mode. Garis oranye = loncat per kata. Ketuk garis: putih = per huruf. Ketuk lagi: kembali oranye (per kata).
- Ketuk ikon gunting untuk memotong. Bisa dipotong berulang; kedua bagian mewarisi preset.
- Contoh tanpa data kata: 2 s, "aku suka bermain fpv drone | tapi masih di simulator" → ±1,06 s dan ±0,94 s. Bila `words` tersedia dan potongan jatuh di batas kata, waktu pisah memakai timestamp kata.

**Pecah / Auto-crop (⚙ Opsi, tombol Pecah):**
- Memecah segmen yang melebihi **Maks kata** (`#mk` > 0), dan/atau bila **Auto-crop lebar** (`#ac`) dicentang dan lebar teks melebihi **Lebar maks (% layar)** (`#mw`) dalam satu baris. Auto-crop butuh template `.kf` (untuk mengukur lebar) dan memakai layer preset segmen itu.
- Urutan: pecah per Maks kata dulu (dibagi rata), lalu auto-crop per potongan secara greedy sampai tiap bagian muat satu baris. Satu kata yang lebih lebar dari batas tetap berdiri sendiri.
- Memakai timestamp asli `words`: bagian pertama mulai di `start` segmen, bagian terakhir berakhir di `end` segmen; batas dalam memakai `words[k-1].end` dan `words[k].start` (boleh ada jeda kecil antar caption). Durasi minimal 50 ms.
- Bila **Auto-crop lebar** dicentang dan template sudah dimuat, auto-crop juga dijalankan otomatis saat impor SRT/VTT/JSON (tanpa Maks kata).
- Segmen yang sudah berisi `\n` tidak dipecah. Preset dihitung ulang kecuali mode Manual.

**Ubah waktu (⏱, 3 keadaan):** ketuk berulang: mati → mode 1 (kuning) → mode 2 (putih) → mati. Alat bekerja pada baris target, yaitu baris di playhead saat alat dinyalakan. Target berpindah hanya saat kamu mencari posisi manual (slider, ketuk waktu/teks, ◀ ▶), tidak saat video berjalan, supaya loop tidak bergeser sendiri.
- **Mode 1 (kuning), transisi:** dua baris (teks target dan berikutnya) disorot bersamaan dan tetap disorot selama loop, jadi slider bisa diatur sambil mendengarkan. Teks yang sedang diucapkan di playhead berwarna oranye (tetap berpindah antar kedua baris), sehingga terlihat kapan transisi terjadi. Penanda ini hanya mengganti kelas CSS, tidak membangun ulang baris, jadi slider tidak putus saat digeser. Slider antara teks target dan teks berikutnya. Menggeser mengubah sekaligus Selesai teks target dan Mulai teks berikutnya (celah ikut tertutup). Tombol ±20 ms untuk penyesuaian halus. Batas geser: tidak boleh melewati Mulai target / Selesai berikutnya (sisa 50 ms).
- **Mode 2 (putih), per teks:** kolom Mulai dan Selesai (detik, kelipatan 0,05) dengan ⏺ untuk memakai posisi video saat ini. Mulai < Selesai (selisih minimal 50 ms). Caption lain tidak digeser.
- **Loop (kedua mode):** untuk mengecek sinkron dengan audio/video. Aktif saat video diputar; jendela loop dihitung ulang langsung saat nilai berubah. Bila posisi putar di luar jendela, melompat ke awal jendela.
  - Mode 1: panjang total loop = 50% dari (awal teks target sampai akhir teks berikutnya), berpusat di transisi.
  - Mode 2: loop mulai 30% durasi teks sebelum Mulai sampai 30% sesudah Selesai.
  - Nilai bisa diubah di ⚙ Opsi: kotak Loop waktu, M1 dan M2, masing-masing dengan satuan % / s / ms. Mengganti satuan mereset nilai ke default satuan itu (M1: 50% / 2 s / 2000 ms; M2: 30% / 1 s / 1000 ms). Satuan %: M1 dari durasi gabungan dua teks, M2 dari durasi teks target.
  - Jendela dibatasi ke 0 s hingga akhir media.

**Gabung (⛓):** saat toggle menyala, baris aktif menampilkan tombol "Gabung dengan teks berikutnya"; teks disambung, `start` yang pertama, `end` yang kedua, `words` digabung.

Setelah potong atau gabung, preset dihitung ulang kecuali mode Manual.

**Offset waktu:** isi detik (boleh negatif) lalu Geser semua. Semua caption (beserta `words`) bergeser sebesar itu (batas bawah 0), bisa di-undo. Untuk menyinkronkan dengan audio atau menyesuaikan posisi mulai di proyek Keyflow.

**Undo/redo (↶ ↷, di kanan baris kontrol pemutar):** menyimpan snapshot daftar caption sebelum tiap perubahan: potong, gabung, ubah waktu, pecah / auto-crop, ganti preset, edit teks (satu langkah per sesi ketik), ganti mode preset, format teks / cari-ganti, duplikat, hapus, reset ke mentah, dan impor SRT/VTT/JSON. Maks 150 langkah. Hasil transkripsi baru mengosongkan riwayat. Undo/redo tidak menggulung daftar ke caption yang sedang menyala (`S.nsc` menahan auto-scroll di `tick()` selama render ulang; posisi gulir tetap). Riwayat undo/redo (`S.h`, `S.f`) disimpan ke project di IndexedDB dan dipulihkan saat project dibuka (bagian 9).

## 5a. Hapus (🗑)
Toggle paling kiri di baris kontrol, merah saat aktif. Saat aktif, **setiap baris** (bukan hanya yang aktif) menampilkan tombol 🗑 merah di samping waktu, sehingga caption dengan waktu sama bisa dipilih satu per satu. Ketuk 🗑 menghapus caption itu (`delSeg(i)`): satu langkah undo, preset dihitung ulang kecuali mode Manual. Teks waktu dibungkus `.tv` supaya `refT` tidak menghapus tombol.

## 5b. Posisi, skala, rotasi (tombol pojok kanan bawah)
Tombol ikon + dengan segitiga di tiap ujung (`#bPos`). Saat aktif: panel impor dan ⚙ Opsi disembunyikan, panel `#pp` tampil, preview pindah ke tab Preset, ruang daftar caption mengecil mengikuti.
- **Target:** caption di playhead (`S.act`, atau terdekat). Panel diisi ulang tiap target berganti. **Apply to all** menyalin pengaturan target ke semua caption; **Reset** mengembalikan target ke posisi preset.
- **Data:** `seg.tf = {x, y, sc, rot, mw}` (bagian dari snapshot undo; ikut `cut`/`mg` lewat spread). `x`,`y` = selisih dari posisi preset (unit kanvas), `sc` = pengali skala (1 = preset), `rot` = derajat tambahan, `mw` = lebar maks % manual (kosong = auto, memakai `#mw`).
- **UI:** empat penggaris X, Y, Skala, Rotasi. Garis `||||` bergeser di bawah penanda tengah, nilai di tengah (X dan Y sebagai posisi pusat teks di kanvas, misal 540; Skala dalam % absolut layer; Rotasi dalam derajat), nilai bisa diketik. Geser ke kiri = nilai naik. Satuan per piksel: X/Y 2, Skala 0,5, Rotasi 0,5. Satu langkah undo per geseran.
- **Lebar otomatis:** `maxW = (mw ‖ Lebar maks) × lebar kanvas ÷ (scale layer × tf.sc)`, jadi teks membungkus lebih sempit saat skala naik dan tidak melewati layar. Dipakai di preview, `info()`, `place()` dan export.
- **Preview:** kotak teks digeser `(x, y)`, diputar dan diskala di sekitar pusat kotak.
- **Export** (`emit`): `transformX/Y += (x, y)`; `kfPosX/Y` ikut bergeser; `scale` dan semua nilai `kfScale` dikali `sc` (skala template absolut, mis. 0→100); `rotate` dan nilai `kfRotate` ditambah `rot`. Gaya bertahap (`build`/`stay`): posisi tiap kata diputar dan diskala mengelilingi pusat blok kalimat, tiap layer kata diberi rotasi dan skala yang sama.
- **Asumsi belum teruji:** Keyflow memutar dan menskalakan terhadap pusat layer (anchor 0,5). Cek di Keyflow.

## 5c. Duplikat / Copy Caption (⧉)
- Toggle `S.tool.copy` dengan tombol `#tCp` (ikon `⧉`) yang berada di kelompok alat kiri baris kontrol, diposisikan di **paling kanan** dari kelompok alat tersebut (setelah `#tD`, `#tC`, `#tM`, `#tT`, `#tW`). Menyala oranye (`.on`) saat aktif.
- Cara kerja sama dengan tombol Hapus (`🗑`): saat aktif, **setiap baris caption** (bukan hanya baris aktif) menampilkan tombol copy kecil `.cpb` (`⧉`) di area kiri baris (di samping waktu `.tv`, berdampingan dengan `.dl` jika hapus juga aktif).
- Ketuk `.cpb` menjalankan `dupSeg(i)`:
  - Menyimpan snapshot undo (`push()`), lalu menyalin penuh objek segmen `S.segs[i]` (beserta `words`, `real`, `preset`, dan `tf`) tepat di bawahnya (`S.segs.splice(i + 1, 0, clone)`).
  - Bila mode preset bukan Manual (`#mode !== 'man'`), preset dihitung ulang (`assign()`), lalu daftar di-render ulang (`render(true)`) dan tampilkan toast `"Caption diduplikat"`.
  - Karena waktunya identik dengan aslinya, kedua baris otomatis aktif bersamaan saat playhead melewati detik tersebut (tumpang tindih), sehingga mudah diedit teks atau posisinya.

## 5d. Shortcut Format Teks & Cari-Ganti Melayang (`#bFmt` di pojok kanan bawah daftar)
- **Posisi:** tombol `#bFmt` berlabel **`Aa`** melayang di **pojok kanan bawah area daftar caption**, tepat di atas panel bawah `#bot` (`position: fixed`, `bottom` dihitung dari tepi atas `#bot`, mengikuti ukuran panel lewat `ResizeObserver`; `z-index` di bawah `#we`/`#pj`). Hanya tampil saat `S.segs.length > 0`. Panel terbuka ke arah atas tombol.
- **Transparan:** tombol dan panel abu-abu memakai latar semi-transparan sekitar 60% (`rgba(60,64,72,.6)` mode gelap, `rgba(220,224,230,.6)` mode terang), **tanpa blur**, supaya teks caption di belakang tetap samar terbaca dan perubahannya bisa dilihat langsung.
- **Toggle format (bisa digabung):** ketuk `Aa` untuk membuka panel; tiap opsi adalah toggle yang menyala oranye (`.on`) dan belum mengubah teks:
  - Kelompok huruf (saling eksklusif, hanya satu aktif): **`AA`** (`toUpperCase()`), **`aa`** (`toLowerCase()`), **`Aa.`** (huruf awal kalimat: kecil semua, kapital di awal teks dan setelah `. ! ? \n`), **`Aa Bb`** (Title Case).
  - **`−Simbol`**: hapus tanda baca/simbol (`[.,?!;:"“”'‘’()[\]{}…—–]`), rapikan spasi ganda tanpa menghapus `\n`.
  - **`🔍 Ganti`**: toggle; saat menyala menampilkan dua kolom input di dalam panel (**Cari kata**, **Ganti dengan**). Tidak ada kotak centang huruf besar/kecil: pencarian selalu tidak membedakan huruf besar/kecil, dan bentuk huruf hasil diatur dengan toggle kelompok huruf di atas.
- **Eksekusi:** dua tombol di bagian bawah panel, **`Caption ini`** (hanya caption terpilih/aktif, `ppIdx()`) dan **`Semua`** (seluruh caption). Bila tidak ada toggle menyala: toast `"Pilih format teks dulu"`; bila `Ganti` menyala tapi kolom Cari kosong: toast `"Isi kata yang dicari"`.
- Urutan penerapan bila beberapa toggle menyala: Ganti → Simbol → Huruf. Semua dalam satu langkah undo (`push()`), `words` disinkronkan dengan `remapWords` bila `s.real`, lalu `render(true)` dan `draw()`. Panel tetap terbuka setelah dijalankan dan pilihan toggle dipertahankan.

## 5e. Penyimpanan, Export, dan Reset Transkrip Mentah (Konfirmasi 2x)
- **Penyimpanan Snapshot Mentah (`S.rawSegs` & `S.rawFile`):**
  - Setiap kali transkrip awal dimuat—baik dari impor file SRT/VTT/JSON (`#iSrt`), hasil transkripsi native (`run()`), maupun contoh JSON (`loadSample()`)—aplikasi otomatis menyimpan salinan mendalam (*deep clone*) kondisi awal transkrip sebelum diedit ke `S.rawSegs` (beserta isi teks/nama file mentah aslinya ke `S.rawFile` bila berasal dari impor file, atau hasil generate JSON/SRT awal bila dari transkripsi langsung).
  - `S.rawSegs` dan `S.rawFile` ikut disimpan di IndexedDB (`data` store) dan di dalam file backup `.zip` project (`raw_transcript.json` / `raw_source.*`), sehingga tetap aman meskipun browser ditutup atau project dibuka ulang di kemudian hari.
- **Export File Mentah di Menu `Lainnya ▾` (`#exm`):**
  - Di dalam menu pop-up tombol **`Lainnya ▾`** (di sebelah kanan `Export .kf`), tambahkan item **`Transkrip mentah (asli)`** (disabled jika `S.rawSegs` belum ada). Saat diklik, mengunduh file transkrip mentah asli sebelum diedit (mempertahankan format & isi file asli saat diimpor, atau format `.json` mentah hasil transkripsi awal dengan akhiran `_mentah`).
- **Tombol Merah `Reset transkrip mentah` di `⚙ Opsi` dengan Verifikasi 2x:**
  - Di bagian paling bawah dalam menu lipat **`⚙ Opsi`** (`#opts`), tambahkan baris tombol berwarna merah bahaya (`background: var(--er); color: #fff`): **`↺ Reset ke transkrip mentah`**.
  - **Wajib verifikasi 2 kali berturut-turut** menggunakan dialog modal `#md` (`dlg(...)`) agar tidak pernah tidak sengaja tertekan setelah capek mengedit:
    - **Verifikasi ke-1:** Dialog peringatan pertama: *"Reset semua caption ke transkrip mentah awal? Semua potongan, editan teks, dan geseran waktu akan hilang."* (Tombol: `Batal` / `Lanjut…`).
    - **Verifikasi ke-2:** Dialog konfirmasi akhir berwarna merah (`danger: 1`): *"Yakin 100%? Perubahan terakhir memang bisa di-Undo, tetapi seluruh daftar caption akan ditimpa ulang dengan data mentah awal."* (Tombol: `Batal` / `Ya, Reset Sekarang`).
  - Hanya jika kedua dialog dikonfirmasi, simpan snapshot ke stack undo (`push()`), timpa `S.segs` dengan salinan bersih dari `S.rawSegs`, jalankan `assign()` bila mode preset bukan Manual, lalu `render(true)`, `draw()`, dan tampilkan toast `"Transkrip dikembalikan ke mentah"`.

## 6. Preset dari template `.kf` dan Gaya teks
- Layer TEXT bernama `1`, `2`… (juga `text1`, `preset1`, `layer1`) = preset 1, 2…
- Tiap preset memakai **satu layer** (yang pertama di daftar). Layer lain dengan nama preset dibuang dari hasil (hanya contoh).
- Tiap layer hasil = salinan penuh layer preset: efek (`fx`), posisi, skala, rotasi, anchor, warna, stroke, animator, semuanya identik. Hanya berubah:
  - `content.text`, dan `content.width/height` (diukur ulang, dikalibrasi dari lebar asli di template)
  - `transformX/Y` (pusat/tepi sesuai `textAlign` dipertahankan)
  - `inPointMs` / `outPointMs`
  - waktu keyframe (lihat bawah)

**Gaya teks** (⚙ Opsi, `#tstyle`; berlaku di Preview kanvas dan export `KF.generate()`):
1. **`full` — Kalimat utuh (default):** satu caption = satu layer TEXT (`inPointMs = s.start`, `outPointMs = s.end`).
2. **`word` — Per kata (ganti):** tiap item `s.words` = satu layer TEXT (`inPointMs = w.start`, `outPointMs = nextWord ? nextWord.start : Math.max(w.end, w.start + 60)`), menampilkan satu kata per waktu di posisi preset.
3. **`build` — Kalimat bertahap (muncul per kata, hilang bersamaan):** (tidak diubah)
   - Susunan baris kalimat utuh dihitung dulu (`wrap()` + `layout()`, diekspor sebagai `KF.place()`), sehingga posisi X/Y akhir tiap kata tetap dan tidak bergeser saat kata lain muncul.
   - Tiap kata menjadi layer TEXT sendiri di koordinatnya dalam susunan itu (lebar teks sebelumnya + spasi pada baris yang sama, serta offset Y barisnya; perataan `textAlign` per baris dihormati).
   - Waktu: `inPointMs = w.start`, `outPointMs = s.end` (semua kata satu kalimat hilang bersamaan).
   - Keyframe grup **masuk** ditambatkan ke `w.start`, grup **keluar** ke `s.end`.
   - Preview kanvas: saat `t` di dalam `[s.start, s.end]`, hanya kata dengan `t >= w.start` yang digambar, di posisi kalimat utuh.
   - Tanpa `words`, gaya per kata dan bertahap otomatis jatuh ke kalimat utuh.

4. **`stay` — Bertahap, posisi tetap (mode baru):** export dan preview Preset sama dengan `build` (tiap kata satu layer di koordinat kalimat utuh). Bedanya di preview **Biasa**: susunan baris kalimat utuh dihitung dulu, kata yang belum diucapkan disembunyikan tanpa menggeser kata lain. Mode `build` lama tidak diubah.

**Highlight per kata di daftar caption:** segmen punya penanda `real` (true bila `words` berasal dari data nyata: JSON `-ojf` / plugin). Baris aktif dengan `real` menampilkan teks putih, tiap kata jadi oranye saat `t >= w.start` (CSS Custom Highlight, DOM tidak diubah sehingga edit tetap aman). Tanpa `real` (SRT/VTT, potong di tengah kata) baris aktif oranye utuh seperti biasa. Edit teks pada segmen `real` tetap `real`, berapa pun jumlah kata barunya: jumlah sama mempertahankan waktu kata, jumlah beda dihitung lewat `remapWords` (bagian 4). Pemutar memanggil `tick()` tiap frame saat play.

**Caption tumpang tindih:** semua caption yang mencakup playhead aktif bersamaan (`S.actSet`): baris sama-sama menyala, highlight kata jalan di tiap baris, preview Biasa dan Preset menggambar semuanya (tiap dengan preset sendiri).

**Pisah pembicara (`splitSp`, saat impor SRT/VTT/JSON dan segmen dari plugin):** token berdiri sendiri `/` atau `-`/`–`/`—`, atau kata berawalan `-Huruf` di awal segmen / setelah tanda akhir kalimat (`. ! ? …`), memecah segmen jadi beberapa caption dengan `start`/`end` yang **sama** (tumpang tindih; di timeline Keyflow jadi layer bertumpuk). Penanda dibuang. Dengan data kata, tiap bagian memakai timestamp aslinya; tanpa data, kata dibagi proporsional ke seluruh rentang. Tanda hubung di dalam kata (`e-mail`, `5-10`) tidak memecah.

**Keyframe** (semua tabel `kf*`, waktu absolut):
- Keyframe di paruh awal layer = grup **masuk**, ditambatkan ke awal layer hasil.
- Keyframe di paruh akhir layer = grup **keluar**, ditambatkan ke akhir layer hasil.
- Durasi di dalam tiap grup dipertahankan. Bagian tengah tanpa keyframe yang memanjang/memendek mengikuti durasi.
- Mode Anim **Tetap** (default) atau **Ikut durasi** (grup ikut diskala). Pengali ×: kecil = pop-up cepat, besar = lambat.
- Layer lebih pendek dari masuk+keluar: kedua grup dikecilkan proporsional (layer minimal 60 ms).
- Semua `t` dan `v` integer. Handle kurva (`eox` dst) dan `pa/pb/pc` tidak diubah.
- **Nilai `kfPosX` / `kfPosY` = posisi absolut** (sama skema dengan `transformX/Y`: tepi kiri-atas relatif pusat kanvas), bukan offset. Karena `transformX/Y` dihitung ulang untuk ukuran kotak baru, semua nilai `kfPosX/Y` digeser sebesar `(transformX/Y baru − transformX/Y template)`; selisih antar keyframe (gerak geser masuk/keluar) tetap. Tabel `kf*` lain (`kfScale`, `kfOpacity`, dll.) nilainya tidak diubah.

**Text animator** (`content.textAnimators[]`, tipe `PER_CHARACTER`; waktunya di `strengthKfs`, absolut, bukan di tabel `kf*`):
- Tiap animator diperlakukan utuh sebagai satu fase: **masuk** bila rentang keyframe-nya lebih dekat ke awal layer, **keluar** bila lebih dekat ke akhir (bukan per keyframe dengan titik tengah). Keyframe "tahan" di ujung (nilai sama dengan sebelumnya) tidak dihitung sebagai gerakan.
- Jeda antar huruf menyebar selama `S = r × (n−1) × delayMs` (n = jumlah huruf caption, tanpa `\n`). `r` dikalibrasi dari template dengan syarat animator keluar selesai (huruf terakhir hilang penuh) tepat di akhir layer: `S0 = outPoint − keyframe aktif terakhir`. Contoh template: 42 huruf, delay 20 ms, S0 = 450 ms → r ≈ 0,55. Teks penuh muncul di keyframe masuk terakhir + S (±frame 21 pada 60 fps di contoh), keluar mulai di keyframe keluar pertama.
- Caption baru: animator masuk mulai di awal caption (jarak antar keyframe dipertahankan); animator keluar digeser sehingga huruf terakhir hilang penuh tepat di akhir caption (`keyframe aktif terakhir = end − S`). Bagian tengah menahan teks penuh dan memanjang/memendek sesuai durasi.
- Caption pendek: bila masuk + keluar (termasuk S) melebihi durasi, jarak keyframe dan `delayMs` diskala bersama dengan faktor yang sama; `delayMs` dibulatkan.
- Array `*Kfs` lain di animator ikut dipindahkan memakai aturan fase yang sama. `delayKeyframes`, `wiggle*`, dan `from*` non-keyframe disalin apa adanya.
- Asumsi: `S` linear terhadap `(n−1) × delayMs` (hanya divalidasi dari satu template); perlu dicek di Keyflow dengan caption yang jumlah hurufnya berbeda jauh.

Lainnya:
- Tabel non-keyframe disalin per id baru; layer non-preset dibiarkan; id template dihapus dari semua tabel.
- Tidak ada auto-wrap di Keyflow, jadi app menyisipkan `\n` sesuai "Lebar maks (% layar)". Lebar bungkus dalam satuan kotak layer = `Lebar maks × lebar kanvas ÷ (scale layer ÷ 100)`, karena layer ber-scale (misal 55) tampil lebih kecil dari kotaknya. Preview preset juga menggambar teks dengan scale layer. Teks yang sudah berisi `\n` tidak diubah.

## 7. Mode penugasan preset
Berurutan (1,2,3,1,2…), Menurut durasi (pendek→preset 1, panjang→terakhir), Semua sama, Manual (ketuk angka per baris).

## 8. Asumsi yang perlu dicek di Keyflow
- Tinggi layer multi-baris = tinggi 1 baris + (n−1) × lineHeight × ukuran font.
- Lebar dikalibrasi dari rasio template (selisih ±1–3% dari pengukuran font perangkat). Pada gaya bertahap, selisih ini bisa menggeser kata sedikit dibanding Keyflow.
- ZIP hasil memakai deflate; `thumbnail.jpg` dan `manifest.json` disalin apa adanya.
- Gaya per kata/bertahap menghasilkan banyak layer (satu per kata); perlu dicek performa impor di Keyflow untuk video panjang.

## 9. Pembaruan (menggantikan bagian terkait di atas)
- **Skala preset diabaikan:** semua teks default 100% (`KF.lsc()` selalu 1). Lebar bungkus = `Lebar maks × lebar kanvas ÷ tf.sc`. Export menulis `scale = 100 × tf.sc` untuk tiap layer hasil, dan nilai `kfScale` dikali `tf.sc ÷ (skala template ÷ 100)`, sehingga bentuk animasi tetap tetapi ukuran akhir sama dengan preview. Panel Posisi menampilkan skala dasar 100.
- **Ukuran teks semua preset disamakan (`normFont` di `readTemplate`):** `fontSize` semua layer preset diganti ke satu nilai `REF` = ukuran efektif preset bernomor terkecil (`fontSize × skala ÷ 100`, dibulatkan). `content.width/height`, `strokeWidth`, `letterSpacing` dan tabel `kfPosX/Y` template diskala sebanding (`REF ÷ fontSize asli`); pusat layer dipertahankan (`transformX/Y` dihitung ulang). Karena skala preset sudah diabaikan (di atas), hasil export dan preview untuk semua preset berukuran sama, dan hanya panel Posisi (`tf.sc`) yang mengubah ukuran per caption. Contoh: preset dengan fontSize 48/19/32/29 dan skala 55/100 semua menjadi fontSize 29, skala 100.
- **Semua nilai integer di export (Keyflow menolak desimal):** `scale`, `rotate` (`tf.rot` dibulatkan ke derajat bulat), `transformX/Y` (tetap bertipe string bila di template string), `inPointMs/outPointMs`, `content.width/height/fontSize`, `t` dan `v` semua tabel `kf*` (kecuali `kfAnchor*`), `delayMs`, serta `t`/`v`/`timeMs` di semua array `*Kfs` dan `keyframes` text animator dibulatkan di akhir `generate()`. Field bawaan template yang memang desimal (`lineHeight`, `strokeWidth`, handle kurva `eox/pa/pb`, `easeOutTx`, nilai `fx`) tidak diubah.
- **Preview Preset flat:** tanpa stroke teks, tanpa kotak bantu, tanpa garis tepi kanvas. Peringatan "melebihi layar" tetap di baris info.
- **Tombol Posisi:** saat dinyalakan otomatis pindah ke tab Preset .kf; saat dimatikan tab tidak berubah.
- **Export lain** (tombol "Lainnya ▾" di kanan Export .kf, tidak butuh template): `.srt`, `.vtt`, `.ass`, `.lrc`, `.txt`, `.json` per kata (kompatibel dengan impor `-ojf`, plus array `segments` dalam detik), serta **Transkrip mentah (asli)** (bagian 5e).
- **Project** (tombol 📁 di kiri bawah): layar daftar project menutupi editor. Autosave ke IndexedDB tiap ±1,5 detik bila ada perubahan (store `meta`, `data`, `media`). Tiap item: thumbnail, judul, durasi, rasio, sumber (audio/video + nama file), jumlah caption, waktu ubah. Menu ⋮: export project (.zip berisi `project.json`, `media/`, `template.kf`, `transcript.srt`, `transcript.json`, dan transkrip mentah), duplikat, ubah nama, hapus (dengan konfirmasi). Tombol Import (zip) dan ＋ Baru ada di atas. Memuat media baru membuat project baru.
- **Riwayat undo/redo ikut tersimpan:** `saveNow()` menyimpan `h: S.h` dan `f: S.f` di store `data`; `sigNow()` memasukkan `S.h.length` dan `S.f.length` sehingga tiap perubahan riwayat memicu autosave. `openProject(id)` memulihkan `S.h = Array.isArray(d?.h) ? d.h : []` dan `S.f` dengan cara sama, lalu `ub()` mengaktifkan tombol ↶ ↷. Riwayat **tidak** ikut export `.zip` (`exportProject`) dan ikut tersalin pada duplikat project (`dupProject` menyalin `h` dan `f`).
- **Auto-restore project terakhir:** saat halaman dibuka, aplikasi membaca `DB.all('meta')`, mengurutkan menurut `updated` terbaru, lalu memanggil `openProject(terbaru.id)` otomatis. Bila belum ada project atau gagal dibuka, jatuh ke `render()` biasa (layar panduan).
- **Ubah waktu mode 2:** Mulai dan Selesai memakai penggaris geser seperti panel Posisi (kiri = naik, 10 ms per piksel), nilai tetap bisa diketik, ⏺ tetap ada, jendela loop ikut berubah langsung.
- Di APK: ganti `saveFile()` dengan plugin Filesystem/Share, dan tangani tombol back Android untuk menutup layar Project.

## 10. Alat di banyak caption yang menyala bersamaan (tumpang tindih)
- **Potong ✂ dan Gabung ⛓:** muncul di **setiap** baris yang sedang menyala (`S.actSet`), bukan hanya baris pertama. Tiap baris punya penanda potong sendiri (`S.cutSt`: Map indeks → `{mode, off}`), jadi bisa dipotong sendiri-sendiri.
- **Ubah waktu ⏱:** target = semua caption yang mencakup playhead saat dicari/dinyalakan (`S.trs`), tetap sampai pencarian berikutnya.
  - **Mode 1:** tiap pasangan berurutan di grup punya slider sendiri. Contoh 3 caption menyala → 2 slider (1↔2 dan 2↔3). Satu caption saja → slider ke caption berikutnya seperti sebelumnya. Batas geser dihitung langsung dari waktu terkini.
  - **Mode 2:** tiap caption target punya penggaris Mulai/Selesai sendiri.
  - **Loop:** jendela loop tiap pasangan (M1) atau tiap caption (M2) dihitung sendiri, lalu digabung menjadi satu jendela dari awal terkecil sampai akhir terbesar. Teks "Loop x–y s" diperbarui di semua baris saat nilai digeser.

## 11. Penanda tumpang tindih saat menggeser waktu (mode 2)
- Saat penggaris **Mulai** digeser mundur sampai masuk ke caption sebelumnya (atau **Selesai** digeser maju sampai masuk ke caption berikutnya), baris caption yang tertimpa ikut menyala (`.row.ov`) dan bagian teks yang tertimpa berwarna oranye (CSS Custom Highlight `ovl`).
- Yang dihitung hanya area baru di luar batas semula (nilai saat penggaris mulai disentuh), jadi caption yang sudah tumpang tindih sejak awal tidak langsung menyala.
- Posisi huruf dihitung dari timestamp kata dengan interpolasi linear di dalam kata (`ovRange`). Tanpa data kata (SRT/VTT) memakai kata proporsional per huruf, jadi tetap berupa perkiraan.
- Penanda hilang saat jari dilepas. Selama menggeser (`S.setting`), daftar tidak auto-scroll ke baris aktif; berlaku juga untuk slider mode 1.

## 12. Tata letak penggaris (Posisi dan Ubah waktu mode 2)
- Semua penggaris memakai satu susunan: **[nama parameter] [penggaris] [nilai]**. Kotak nilai (angka, bisa diketik) berada di kanan penggaris dan tidak lagi menutupi area geser, jadi seluruh penggaris bisa dipakai untuk menggeser (tinggi 40 px, garis tengah tetap sebagai penanda).
- Berlaku di panel Posisi (X, Y, Skala, Rotasi) dan di Mulai/Selesai pada waktu mode 2 (tombol ⏺ di kanan nilai).
- Panel alat per baris (waktu, potong, gabung) kini selebar baris penuh (di bawah teks), supaya penggaris tidak sempit.

## 13. Editor waktu per kata (tombol sendiri, di antara ⏱ waktu A/B dan ⧉ copy)
Untuk menyelaraskan kata dengan audio bila transkrip ngaco atau teks diubah sehingga animasi per kata tidak sinkron. Tombol `#tW` (ikon balok kata, hijau saat aktif) membuka layar sendiri (`#we`); membukanya mematikan ⏱ A/B supaya loop tidak bentrok. Layar ini yang menutupi daftar caption dan panel bawah, **preview dan baris kontrol tetap terlihat** di atas (untuk melihat hasil per kata di Preview dan memakai tombol utama untuk pindah caption). Target: caption di playhead; **pindah caption lewat tombol utama (⏮ ◀ ▶ ⏭, slider, ketuk waktu di daftar)** otomatis memindahkan editor (`weFollow`). ◀ ▶ di header editor **memindah kata** dalam caption ini. **Selesai** atau menekan tombol hijau lagi menutup editor, daftar dirender ulang.
- **Cocok untuk kata sedikit maupun banyak:** (1) baris chip kata yang membungkus dan bisa di-scroll (ketuk = pilih + loncat, oranye = sudah diucapkan); (2) timeline yang bisa digeser dan di-zoom (30–600 px/detik, tombol Pas = muat satu caption) dengan balok kata, gelombang audio, penggaris waktu (ketuk = loncat) dan playhead merah yang diikuti saat memutar; (3) panel kata terpilih dengan penggaris Mulai/Selesai (sama dengan bagian 12, 10 ms per piksel, nilai bisa diketik, ⏺ = posisi video), ketik ulang kata, ▶ Dengar (putar kata itu saja ±150 ms).
- **Garis geser:** oranye = sambungan dua kata (celah < 60 ms: menggeser akhir kata k dan awal kata k+1 bersama); biru = awal kata, putih = akhir kata (bila ada jeda, bebas). Awal pertama dan akhir terakhir termasuk tepi. Batas: dalam `[start, end]` caption, urut, minimal 20 ms per kata, kelipatan 10 ms. Saat menggeser, video ikut loncat (scrub). Satu langkah undo per geseran.
- **Sinkron dengan ketukan:** pilih kata awal, tekan *Sinkron dengan ketukan*, audio berputar dari sebelum kata itu; tiap ketukan memasang **Mulai** kata berikutnya dan menutup akhir kata sebelumnya; ketukan setelah kata terakhir memasang akhir kata terakhir lalu selesai (atau otomatis berhenti 0,8 detik setelah caption). `Kompensasi` (default 120 ms, dikali kecepatan) mengurangi waktu reaksi. Satu langkah undo per sesi ketuk.
- **Preview slowmo:** tombol 🐢 memutar caption dari awal dengan kecepatan pilihan (0,25×/0,5×/0,75×; bila masih 1× dipakai 0,5×), pitch dipertahankan (`preservesPitch = true`), sambil preview atas menampilkan efek per kata. Loop mengulang; tanpa loop berhenti 0,3 detik setelah caption. Kecepatan yang sama dipakai saat sinkron ketukan.
- **Kontrol:** ▶/⏸, Loop caption (±200 ms di tepi), kecepatan 0,25×/0,5×/0,75×/1×, ↶ ↷ (undo global).
- **Data:** hasil edit ditulis ke `seg.words` dan menandai `seg.real = true` sehingga highlight per kata di daftar dan semua gaya teks (`word`/`build`/`stay`) memakai waktu baru. Mengganti teks kata mengubah `seg.text` pada token yang sama (spasi dan baris baru tetap).
- **Gelombang audio:** `decodeAudioData` seluruh file sekali per media (puncak 100/detik, di memori), dilewati bila file > 80 MB atau durasi > 10 menit (timeline tetap jalan tanpa gelombang). Di APK sebaiknya diganti puncak dari plugin native.
- Tombol **Export .kf** memakai warna hijau yang sama (`--ok`, teks `--okt`).

## 14. Navigasi per caption dan penanda caption terpilih
- **◀ ▶ utama pindah per caption menurut urutan daftar**, tidak peduli waktu atau berapa caption yang menyala bersamaan, jadi caption yang waktunya sama tidak terlewat. Penyorotan baris tetap per waktu (semua caption yang mencakup playhead menyala).
- **Caption terpilih** `S.sel`: diisi oleh ◀ ▶, ketuk waktu di kiri baris, atau fokus ke teks baris. Dipakai sebagai basis ◀ ▶ berikutnya selama playhead masih di dalam caption itu; bila tidak, basis = caption pertama yang menyala (di celah: caption terakhir yang sudah mulai). Slider, ⏮ dan ⏭ mengosongkan pilihan.
- **◀:** bila playhead di dalam caption dan sudah lebih dari 600 ms dari awalnya, kembali ke awal caption itu; selain itu ke caption sebelumnya. **▶:** ke caption berikutnya.
- **Penanda:** saat caption dipilih lewat tombol atau ketukan waktu, teksnya berwarna oranye lalu memudar ke warna normal dalam 400 ms (animasi CSS `flo` pada `.row.fl .x`). Baris tanpa data kata yang sedang menyala sudah oranye, jadi kilatnya baru terlihat pada baris yang teksnya putih. Baris terpilih juga di-scroll ke tengah.
- Panel Posisi, editor kata (tombol hijau) memakai caption terpilih bila ia sedang menyala; editor ikut pindah ke caption terpilih saat ◀ ▶ ditekan.

## 15. Waktu caption mengikuti kata (editor kata)
- Setiap perubahan waktu kata di editor (geser garis, penggaris, ketik nilai, ⏺, sinkron ketukan) memanggil `weSync(s)`: `s.start` = awal kata paling awal, `s.end` = akhir terjauh di antara semua kata (minimal `start + 50 ms`). Dengan begitu penyorotan baris dan preview caption (yang berdasarkan waktu caption) mulai tepat saat kata pertama muncul dan selesai saat kata terakhir selesai.
- Kata pertama boleh digeser keluar dari rentang lama (caption memanjang ke depan), dan kata terakhir keluar ke belakang; batasnya tepi timeline (±400 ms di luar caption) dan 0 hingga durasi media. Setelah geseran selesai timeline dibangun ulang (`weRefit`) sehingga ada ruang baru di kiri dan kanan; posisi gulir dipertahankan.
- Sinkron ketukan memakai aturan yang sama (`weNorm` tidak lagi membatasi kata ke rentang caption lama). Judul editor menampilkan waktu caption terbaru; daftar caption diperbarui saat editor ditutup.
- Hanya perubahan lewat editor kata yang menyinkronkan; waktu caption hasil impor tidak diubah sampai kata di dalamnya diedit.
## 16. Export lengkap: grup, gaya per caption, media, marker, manifest (menggantikan bagian terkait di atas)
Dasar: struktur project Keyflow 1.6.0 (build 40) hasil export `Project 277 (16).kf` (tanpa media; `includesMedia:false`).

**Gaya teks per caption (A/B/C/D)**
- Data: `seg.ts` ∈ `full|word|build|stay`; kosong = ikut default `#tstyle`. Huruf: **A** kalimat utuh, **B** per kata (ganti), **C** bertahap, **D** bertahap posisi tetap. `sty(s) = s.ts || #tstyle`, dipakai di preview Biasa/Preset, `visTxt`, `biasaLines` dan `KF.generate()` (`s.ts || opts.style`). Ikut snapshot undo karena bagian dari `S.segs`.
- UI: tombol **ABCD** (`#bSty`) di kiri tombol **Aa**, panel `#styP` sama gaya dengan Aa (toggle pilih gaya → **Caption ini** / **Semua**; membuka satu panel menutup yang lain). **Semua** menghapus semua `s.ts` dan mengisi `#tstyle`.
- Penanda: huruf kecil `.sl` di kiri angka preset tiap baris. Keterangan A–D ada di ⚙ Opsi di bawah Gaya teks.

**Grouping (gaya D saja; C tidak berubah)**
- Tiap caption gaya D → satu layer `GROUP` (`content:{kind:'GROUP',childIds,collapse:false,cameraPassThrough:false}`), diletakkan tepat sebelum anak-anaknya; anak memakai `groupId`. `inPointMs/outPointMs` grup = min/maks anak. Tabel grup: `transformX/Y '0'`, `scale '100'`, `rotate '0'`, `anchorX/Y '0.5'`, `opacity '1.0'`, `visibility 'true'` (string, sama dengan sampel).
- **Hanya efek (`fx`) yang pindah ke grup** (salinan, `instanceId` baru); anak tidak punya `fx`. Posisi, scale, stroke, animator teks, `kf*`, `motionBlur` tetap di layer teks.
- Template yang preset-nya ada di dalam grup: `fx` grup diwariskan ke layer preset saat `readTemplate`, supaya gaya A/B/C tidak kehilangan efek. Grup template yang anaknya habis dibuang, `childIds` grup lain dibersihkan.
- Opsi `#grp` (default aktif) bisa mematikan grouping.

**Text animator gaya D**
- Animator **keluar** sama dengan preset (`delayMs` tetap) dan **identik di semua kata**: sebaran jeda `S` dihitung dari kata terpanjang, selesai di `s.end`, tidak dikecilkan, dan boleh dimulai sebelum kata itu muncul (animator terpisah, nilainya ditahan). Animator **masuk** dikecilkan (min 15%) agar selesai sebelum animasi keluar mulai. Tabel `kf*` (scale/opacity dst) tetap memakai kompresi lama pada layer pendek.
- Animasi masuk per kata tidak berubah. Ingin masuk dan keluar bertahap per huruf di seluruh kalimat → pakai gaya A (caption utuh).

**Nama layer**: `"<angka preset> <teks>"`, mis. `1 parfum itu wangi ya`. Layer kata (B/C/D) = preset + kata; grup = preset + kalimat. (Nama ini tidak lagi cocok `PRESET_RE`, jadi hasil export tidak terbaca sebagai preset bila dipakai sebagai template.)

**Media di export**
- Layer media diletakkan paling bawah, urutan `VIDEO` lalu `AUDIO` (sama dengan project sampel). Sumber audio saja → hanya `AUDIO`. Sumber video → `VIDEO` + `AUDIO`. Layer media lama di template dibuang.
- `VIDEO`: `name` = nama file, `assetPath:"keyflow:/asset/vid_<ts>.<ext>"`, `originalWidth/Height`, `durationMs`, `startOffsetMs:0`, `endOffsetMs:0`, `outPointMs = durasi − 1`; `transformX/Y = −lebar/2, −tinggi/2`, scale 100. `AUDIO`: `name`/`title` = nama tanpa ekstensi, `outPointMs = durasi`.
- Opsi `#xm`: **Tanpa media** (default; zip kecil, `includesMedia:false`, `manifest.assets[aset] = path sumber` = `File.path` bila ada, selain itu `#mpath` + nama file) atau **Dengan media** (`includesMedia:true`, file di `assets/<nama aset>`, thumbnail video `vidthumb_<ts>.jpg` bila ada, path aset `/data/user/0/com.angkin.keyflow/files/<nama aset>`). File media tidak dikompres di zip.
- **Belum teruji di Keyflow:** (1) letak file media di zip (`assets/`), karena hanya contoh tanpa media yang tersedia; (2) layer AUDIO dari sumber video memakai file video yang sama (Keyflow asli membuat `aud_*.mp3` terpisah).

**Marker**
- Format di `project.json`: `markers` = array berurutan waktu; entri bernama `{"t":ms,"name":"..."}`, entri tanpa nama berupa angka ms. App membuat entri bernama. Opsi `#mkOn` (default mati) dan `#mkMode`: **Per kata** (`t = w.start`, nama = kata) atau **Per caption** (`t = s.start`, nama = teks). Marker template selalu diganti (kosong bila opsi mati). `t` integer, duplikat (waktu+nama sama) dibuang.

**manifest.json**: `device` diganti seluruhnya menjadi `SubLab By Naufan.Imd`; `format`, `app`, `build`, `exportedAt`, `project.fps/ratio/resolution/createdAt` tetap. `project.name` = nama file sumber tanpa ekstensi. `assets`/`includesMedia` mengikuti mode media di atas. `thumbnail.jpg` tetap dari template.

**Pengaturan tersimpan di project**: `grp`, `mkOn`, `mkMode`, `xm`, `mpath` (bagian `SET_V`/`SET_C`).

## 17. Revisi: gaya C kumulatif, grup untuk semua gaya, urutan layer terbalik (menggantikan bagian 6 gaya C dan bagian 16 terkait)
Dasar: `Project_305_1.kf` (contoh gaya C dari Keyflow).
- **Gaya C (`build`) = teks kumulatif.** Tiap kata k membuat satu layer berisi kata 1..k, di posisi default preset (dibungkus dan dipusatkan seperti layer utuh biasa, jadi susunan bergeser saat teks bertambah). Layer berurutan tanpa tumpang tindih: `inPointMs = w[k].start` (layer pertama: awal caption), `outPointMs = w[k+1].start` (layer terakhir: akhir caption). Nama layer = preset + teks kumulatif.
- **Animasi di gaya C:** layer pertama hanya membawa animasi **masuk** (keyframe `kf*` kelompok masuk dan text animator masuk); layer terakhir hanya membawa animasi **keluar**; layer tengah tanpa keduanya (meniru contoh: nilai keyframe di luar rentang layer tertahan). Tabel `kf*` yang jadi kosong dihapus dari layer itu. Preview Preset gaya C menggambar teks kumulatif di posisi default.
- **Gaya D** tidak berubah (tiap kata satu layer di posisi kalimat utuh, keluar serentak).
- **Grup untuk semua gaya (A–D).** Opsi `#grp` (default aktif, ⚙ Opsi): bila aktif, tiap caption → satu `GROUP` (juga gaya A dan B), `fx` preset pindah ke grup, anak tanpa `fx`. Bila mati: layer teks lepas, `fx` tetap di tiap teks.
- **Urutan layer terbalik.** Caption paling awal berada paling bawah (tepat di atas VIDEO/AUDIO), caption terakhir paling atas. Di dalam grup, kata/layer paling awal juga paling bawah (`childIds` ikut terbalik); grup tetap ditulis sebelum anak-anaknya. VIDEO lalu AUDIO tetap paling akhir di daftar.

## 18. Revisi: grup per caption wajib, opsi grup = grup induk (menggantikan opsi `#grp` di bagian 16 dan 17)
- **Grup per caption selalu aktif (wajib), tidak ada opsinya lagi.** Tiap caption (gaya A–D) → satu `GROUP`, `fx` preset pindah ke grup, anak tanpa `fx` (aturan bagian 16/17 tetap).
- **Opsi `#gall` (⚙ Opsi, default aktif): "Satukan semua caption dalam 1 grup induk".** Bila aktif, semua grup caption dibungkus lagi dalam satu `GROUP` bernama `Teks` (`opts.gall`, dibuat di akhir `generate()` setelah urutan layer dibalik). Hasilnya project hanya berisi 3 layer tingkat atas: **Teks** (grup induk), **VIDEO**, **AUDIO**. Untuk membuka grup caption, cukup *ungroup* dari Keyflow.
  - Grup induk: `content:{kind:'GROUP',childIds:[id grup caption, urutan sama dengan daftar layer],collapse:true,cameraPassThrough:false}`, `inPointMs/outPointMs` = min/maks anak, tabel transform standar (`transformX/Y '0'`, `scale '100'`, `rotate '0'`, `anchorX/Y '0.5'`, `opacity '1.0'`, `visibility 'true'`), tanpa `fx`. Tiap grup caption diberi `groupId` = id grup induk. Grup induk ditulis sebelum anak-anaknya.
  - Bila dimatikan: grup caption tampil lepas di daftar layer (perilaku sebelumnya).
- Pengaturan tersimpan di project: kunci `gall` menggantikan `grp` (`SET_C`). Project lama yang menyimpan `grp` mengikuti default `gall` (aktif).
- **Belum teruji di Keyflow:** grup di dalam grup (nested) dan `collapse:true` pada grup induk; cek bahwa import dan ungroup berjalan normal.

## 19. Multiselect caption untuk Aa dan ABCD (menambah bagian 5d dan 16)
- **Tombol "Terpilih (N)"** ditambah di tengah, di antara **Caption ini** dan **Semua**, pada panel `#fmtP` (Aa) dan `#styP` (ABCD). Menerapkan format teks atau gaya A–D hanya ke caption yang dipilih, dalam satu langkah undo. Bila belum ada yang dipilih: toast `"Belum ada caption dipilih"`.
- **Bar pilih** (`.msb`) di bagian atas kedua panel, state dipakai bersama (`S.msel`: Set indeks, `S.msm` mode pilih, `S.msr` mode rentang, `S.msa` jangkar):
  - **☑ Pilih:** toggle mode pilih. Saat menyala, ketuk di mana saja pada baris (teks, waktu, nomor preset) memilih/membatalkan pilihan baris itu; teks tidak bisa diedit (`#mid.msm`, `pointer-events:none` pada `.x .pc .tl`). Tombol 🗑 dan ⧉ per baris tetap berfungsi.
  - **↔ Rentang:** saat menyala, ketukan berikutnya memilih (atau membatalkan) semua caption dari jangkar (ketukan terakhir) sampai baris yang diketuk.
  - **Semua:** pilih seluruh caption dan menyalakan mode pilih. **✕:** kosongkan pilihan. Penghitung "N dipilih" di kanan.
- **Tampilan:** baris terpilih diberi **border biru** (`.row.sx`, `box-shadow: inset 0 0 0 2px #4aa3ff`, opacity penuh), berbeda dari highlight baris aktif (latar abu) dan teks oranye saat diputar. Border tetap terlihat selagi caption itu sedang menyala.
- **Siklus hidup:** pilihan bertahan setelah menerapkan format/gaya, sehingga bisa dirangkai (mis. ganti huruf lalu ganti gaya pada pilihan yang sama). Pilihan dikosongkan otomatis bila jumlah caption berubah (potong, gabung, hapus, duplikat, undo struktural, impor), karena indeks bergeser. Menutup kedua panel (Aa dan ABCD) mematikan mode pilih dan mengosongkan pilihan; berpindah dari Aa ke ABCD tidak.
- Pilihan tidak disimpan ke project (hanya state sesi).

## 20. Video di preview Preset .kf (menambah bagian 2 dan 6)
- Bila sumber adalah **video**, tab **Preset .kf** menggambar frame video di kanvas (di bawah teks) dengan **fit / contain**: `q = min(lebarKanvas ÷ lebarVideo, tinggiKanvas ÷ tinggiVideo)`, dipusatkan, rasio asli dipertahankan (tidak di-stretch). Sisa area (letterbox / pillarbox) tetap warna latar template. Video 16:9 di kanvas 9:16 jadi pita di tengah dengan bar di atas dan bawah.
- Hanya dipakai di preview; tidak mengubah export (layer VIDEO tetap `scale 100`, ukuran asli, bagian 16).
- Frame digambar ulang tiap `tick()` saat play, dan saat `seeked` / `loadeddata` ketika sedang pause (listener capture di `#pv`, hanya bila tab Preset aktif). Frame belum siap (`readyState < 2`) dilewati.
- Saat video tampil dan tidak ada caption di titik itu, teks "(tidak ada caption di titik ini)" tidak digambar supaya tidak menutupi video. Sumber audio tidak berubah (hanya warna latar).

## 21. Multiselect di panel Posisi (menambah bagian 5b dan 19)
- Panel Posisi (`#pp`) memakai bar pilih yang sama dengan Aa/ABCD (`.msb`: ☑ Pilih, ↔ Rentang, Semua, ✕, penghitung), dengan state bersama (`S.msel`, `S.msm`, `S.msr`). Baris terpilih diberi border biru (`.row.sx`).
- Tombol **Apply ke terpilih (N)** (`#ppsel`, di bar pilih): menyalin `seg.tf` (X, Y, skala, rotasi, lebar maks) caption **target** (caption di playhead / terpilih, `ppIdx()`) ke semua caption yang dipilih, satu langkah undo. Bila target tidak punya `tf`, `tf` caption terpilih dihapus (kembali ke posisi preset). **Apply to all** tetap ada. Tanpa pilihan: toast `"Belum ada caption dipilih"`.
- Target tidak berubah saat memilih: selama ☑ Pilih menyala, ketukan di daftar hanya memilih baris (tidak loncat). Untuk pindah target, matikan ☑ Pilih atau pakai ◀ ▶ / slider utama.
- Pilihan dikosongkan dan mode pilih dimatikan hanya bila ketiga panel (Aa, ABCD, Posisi) tertutup; berpindah antarpanel mempertahankan pilihan.

## 22. Export .ass membawa style preset, posisi/skala/rotasi, dan mode teks (menambah bagian 9)
Menu **Lainnya ▾ → Advanced SubStation (.ass)**: bila template `.kf` sudah dimuat, export memakai `toASSkf()`; tanpa template tetap ASS polos lama.
- **Kanvas:** `PlayResX/Y` = kanvas template (mis. 1080×1920), `WrapStyle: 2`, `ScaledBorderAndShadow: yes`.
- **Style per preset** (`Style: P<n>`, dari layer preset pertama): `Fontsize = fontSize × ppu`; warna dari `content.color` (ARGB → `&HAABBGGRR`, alpha dibalik); `Bold` bila `fontWeight ≥ 600`; `Italic` bila `fontStyle = ITALIC`; `Alignment` dari `textAlign` (kiri 4, tengah 5, kanan 6); `Outline = strokeWidth × ppu ÷ 2`; warna outline dari key `content.*stroke|outline|border*colo*` bila ada (default hitam); nama font dari key `fontFamily/fontName/font/typeface` bila ada (path dan ekstensi dibuang), selain itu `Roboto`.
- **Posisi, skala, rotasi per caption** (`seg.tf`): tiap Dialogue memakai `\an` sesuai rata teks, `\pos(x,y)` dari `transformX/Y` template ditambah `tf.x/tf.y` (pusat kanvas + offset), `\fscx/\fscy = 100 × tf.sc`, `\frz = −tf.rot` (ASS memutar berlawanan jarum jam). Untuk rata kiri/kanan, titik jangkar digeser mengikuti skala dan rotasi mengelilingi pusat kotak teks, sama dengan preview Preset. Pembungkus baris memakai Lebar maks (`\N`).
- **Mode teks per caption** (`sty(s)`): **A** satu Dialogue kalimat utuh; **B** satu Dialogue per kata (waktu sama dengan export .kf); **C** Dialogue kumulatif kata 1..k berurutan; **D** satu Dialogue per kata di koordinat susunan kalimat utuh (`KF.place`), semua hilang di akhir kalimat, tiap kata diskala dan diputar di pusatnya sendiri seperti layer di export .kf.
- **Tidak terbawa (batasan ASS):** efek `fx`, animasi masuk/keluar (`kf*`), text animator per huruf, grup, `letterSpacing`.
- **Belum teruji / asumsi:** (1) nama key font dan warna stroke di layer teks Keyflow ditebak dengan pola nama; (2) satuan `strokeWidth` diasumsikan unit font (× `ppu`, lalu ÷ 2 karena stroke tengah vs outline luar); (3) libass/VSFilter menafsirkan `Fontsize` sebagai tinggi sel, sehingga teks bisa tampak sedikit lebih kecil dari preview; (4) font harus terpasang di pemutar. Cek hasilnya di pemutar (mis. VLC, mpv, ffmpeg) dan sesuaikan.

## 23. Placement preset di WebVTT, TTML, dan JSON (menambah bagian 22)
Prinsip: hanya parameter statis (style teks, posisi, skala, rotasi bila didukung, mode teks A–D). Tanpa efek dan animasi. Perhitungan bersama ada di `kfCtx(s)` (konteks per caption), `kfCues(a)` (daftar cue menurut mode A–D: `{n,t0,t1,x,y,al,sc,rot,txt}`, x/y = titik jangkar px kanvas) dan `kfSty(n)` (font, ukuran, warna, stroke, tebal/miring, rata). `toASSkf()` kini memakai `kfCues()` (hasil identik dengan sebelumnya).
- **Aktif hanya bila template `.kf` dimuat.** Tanpa template, VTT dan TTML berisi teks polos.
- **WebVTT (`toVTTkf`):** blok `STYLE` dengan `::cue(.p<n>s<skala%>)` (warna, font, tebal/miring, ukuran = tinggi font ÷ tinggi kanvas ÷ 5% × skala, stroke disimulasikan `text-shadow`), tiap cue punya `line:Y%,center position:X%,<line-left|center|line-right> size:W% align:<start|center|end>`. Lebar `size` dibatasi agar kotak tetap di dalam layar. **Rotasi tidak didukung WebVTT.** Banyak pemutar mengabaikan posisi/gaya VTT; Chrome dan player berbasis web paling lengkap.
- **TTML / IMSC1 (`toTTML`, item baru `.ttml` di Lainnya ▾):** `tts:extent` = kanvas px, satu `<region>` per cue (`origin`/`extent` px, `displayAlign: center`), satu `<style>` per (preset, skala) dengan `fontFamily`, `fontSize` (× skala), `color`, `fontWeight`, `fontStyle`, `textOutline`, `textAlign`. **Rotasi tidak didukung.** Dukungan pemutar bervariasi.
- **JSON per kata:** tiap entri `segments[]` ditambah `preset`, `textStyle` (A–D), `tf` (bila ada) dan `placement` (`canvas`, `x`, `y`, `align`, `scale`, `rotate`, `fontSize`, `fontFamily`, `color`) bila template dimuat. Field tambahan ini diabaikan oleh impor `-ojf`. Export transkrip mentah tidak menyertakannya.
- **Tidak diberi placement:** SRT, LRC, TXT (format tidak punya tempat untuk posisi/skala).
- **Asumsi yang sama dengan bagian 22:** nama key font/stroke di layer Keyflow ditebak, `strokeWidth` dianggap unit font (× `ppu`, lalu ÷ 2).

## 24. Kredit (footer)
- Teks kredit kecil (`.credit`, abu redup, rata tengah): **"SubLab · dibuat oleh Naufan.Imd"** dan **"© 2026 Naufan.Imd · Transkripsi: whisper.cpp & OpenAI Whisper (MIT)"**.
- Ditempatkan di dua tempat: (1) paling bawah isi ⚙ Opsi (di bawah tombol Reset ke transkrip mentah), (2) paling bawah layar Project (di bawah daftar `#pjl`, tidak ikut dirender ulang).
- Catatan lisensi: whisper.cpp dan OpenAI Whisper berlisensi MIT, yang mensyaratkan pemberitahuan hak cipta dan teks lisensi ikut disertakan saat didistribusikan. Untuk APK/repo sertakan file `LICENSE` milik proyek ini dan `THIRD_PARTY_NOTICES` (teks MIT kedua proyek itu).

Catatan nama: aplikasi bernama **SubLab** (sebelumnya tertulis "KF Caption"). Nama file (`kf-caption.html`) dan penanda internal project (`app:'kf-caption'` di export .zip) sengaja tidak diubah agar project lama tetap bisa diimpor.

## 16. Model Whisper: info, unduhan, penyimpanan (mode simulasi di browser)
Seluruh bagian ini bisa dicoba di browser tanpa APK: bila `window.Capacitor.Plugins.Whisper` tidak ada, aplikasi memakai mesin simulasi `SIMW` yang kontraknya sama dengan plugin native (lencana **SIMULASI** tampil). Di APK, kode UI tidak berubah, hanya plugin yang menggantikan `SIMW`.
- **Info model (ⓘ):** lingkaran kecil bertanda *i* di samping nama model membuka gelembung ala chat (ekor menunjuk tombolnya, tutup dengan ketuk di luar atau menggulir). Isinya: ringkasan, ukuran dan jumlah parameter, kecepatan, akurasi bahasa Indonesia (bintang), RAM saat jalan, cocok untuk, dan peringatan khusus (mis. Large v3 bisa mengarang teks di bagian hening, turbo kurang cocok untuk terjemahan). Varian q5/q8 menambah penjelasan kuantisasi. Semua angka perkiraan untuk HP kelas menengah. Termasuk model **Silero VAD** (bukan model transkripsi).
- **Kartu perangkat:** sisa penyimpanan dari total (bar: model, lainnya, bebas), total RAM, jumlah core. Peringatan merah bila sisa < 2 GB.
- **Penolakan unduhan:** unduh butuh `ukuran model − potongan yang sudah ada + cadangan 300 MB` kosong. Bila sisa penyimpanan kurang, tombol unduh jadi ⛔ dan baris menampilkan angka butuh dan sisa; plugin tetap memvalidasi dan menolak dengan kode `NO_SPACE`. Contoh: sisa 1 GB menolak Large (1,1 GB) tetapi masih menerima Medium q5 (514 MB). Bila ruang habis saat mengunduh (< 40 MB), unduhan dihentikan dengan `NO_SPACE` dan potongan disimpan.
- **Tampilan unduhan:** bar progres, `diunduh / total`, persen, kecepatan MB/s, sisa waktu; tombol **Batal**.
- **Batal dan lanjut:** bawaan potongan unduhan **dipertahankan** (baris menampilkan "terpotong X MB (Y%)" dengan tombol **⬇ Lanjut**, melanjutkan dari potongan seperti HTTP Range). Kotak centang *Hapus potongan unduhan saat dibatalkan* (bawaan mati) menghapusnya saat batal; tombol 🧹 per baris menghapus potongan kapan saja.
- **Hapus model:** 🗑 dengan konfirmasi; ruang dikembalikan; tidak bisa saat transkripsi berjalan.
- **Panel simulasi perangkat (hanya demo):** slider sisa penyimpanan (0,2–64 GB) dengan tombol cepat 0,5/1/5/32 GB, kecepatan unduh (1,5/8/40 MB/s), RAM perangkat (3–12 GB), reset simulasi. Peringatan "RAM perangkat mungkin kurang" muncul bila perkiraan RAM model > 60% RAM perangkat.
- **Perbaikan:** model yang dipilih sekarang disimpan di `S.model`. Sebelumnya `S.sel` dipakai ganda (model Whisper dan caption terpilih, bagian 14) sehingga pilihan model tertimpa `-1`.

## 17. Pengaturan transkripsi (UI)
Kotak **Pengaturan transkripsi** di bawah daftar model mengikuti rencana `sublab-whisper-settings.md`:
- **Dasar:** Bahasa (Otomatis, Indonesia, English, Sunda, Jawa, Melayu, dan ±16 lainnya), Terjemahkan ke Inggris, Prompt awal (maks 200 karakter, penghitung), Rentang waktu Mulai/Selesai (detik, ⏺ ambil dari playhead; 0 dan 0 = seluruh media), Preset **Cepat / Seimbang / Akurat** (mengisi beam, best-of, suhu, kenaikan suhu; Akurat menyalakan VAD bila model Silero terpasang; mengubah salah satu nilai Pakar menandai **Kustom**).
- **Lanjutan ▾:** Thread, VAD (ambang suara, bicara minimal, hening minimal, padding), Panjang segmen maks, Pisah di batas kata, Satu segmen, Tekan `[MUSIC]` dst., Normalisasi volume, Pengurangan noise; tombol Reset ke bawaan.
- **Pakar ▾:** Beam, Best of, Suhu, Kenaikan suhu, Ambang entropy / logprob / no-speech / kata, Konteks teks maks, Konteks audio, DTW, GPU, Regex suppress; Reset ke bawaan.
- Peringatan otomatis: turbo + terjemahan, VAD tanpa model, RAM model > RAM perangkat, beam besar + model besar, terjemahan + panjang segmen maks. Ringkasan satu baris di bawah form dan di panel proses.
- **Penyimpanan:** pengaturan ikut project (`getSet().wo`, lewat `putSet`) dan diingat untuk project baru lewat `localStorage` (`sublab.wo`); **prompt dan rentang waktu tidak diwariskan** (`woClear` saat reset editor, kosong saat membuka project).
- Tombol **Mulai** nonaktif bila model terpilih belum terpasang.

## 18. Panel proses transkripsi dan kontrak plugin yang diperluas
- **Panel proses (mirip CLI):** judul + lencana, ringkasan pengaturan, fase (Memuat model ke RAM → Mentranskripsi → Selesai), bar progres, kartu Progres / Berlalu / Sisa / Kecepatan (×real-time), meter **CPU**, **RAM** (dipakai / total, plus puncak) dan **Disk** (bebas dari total), hitungan caption dan kata, log bergaya CLI (`[mulai → selesai] teks`), tombol Batal. Caption muncul bertahap lewat event `segment`; hasil yang berisi `/` atau `-` dipecah (`splitSp`, bagian 12). Batal di tengah menyimpan caption yang sudah masuk.
- **Kontrak plugin `Whisper`:** `listModels() → {models:[{name,installed,partialMB,sizeMB}]}`; `getDeviceInfo() → {storageFreeMB, storageTotalMB, ramTotalMB, ramFreeMB, cpuCores}`; `downloadModel({name})` (melanjutkan potongan; menolak `NO_SPACE`); `cancelDownload({name, deletePartial})`; `clearPartial({name})`; `deleteModel({name})`; `transcribe({file, model, language, options})` dengan `options` sesuai bagian 8 `sublab-whisper-settings.md` (+ `preset`, `gpu`, `normalize`, `denoise`, `wordThold`, `singleSegment`, `suppressRegex`); `cancel()`.
- **Event:** `dlprogress {name, percent, downloadedMB, totalMB, speedMBps}`; `progress {phase:'load'|'run'|'done', percent, cpu, ramMB, ramTotalMB, freeMB, speedX}`; `segment {start, end, text, words[]}` (ms, absolut walau memakai rentang waktu).
- **Simulasi transkripsi:** kecepatan mengikuti keluarga model, preset dan thread (dipadatkan agar demo selesai 7–30 detik), teks contoh Indonesia atau English (bila terjemahan), termasuk baris dialog dengan `/` untuk menguji pemisah pembicara.

## 19. Paket APK (Capacitor + whisper.cpp) dan build di GitHub
Repo siap-GitHub ada di folder `sublab-apk/` (README.md di dalamnya berisi langkah lengkap).
- **Struktur:** `www/index.html` (= `kf-caption.html` + jembatan APK), `native/` (plugin Java, JNI C++, CMake, Gradle), `scripts/apply-native.mjs`, `.github/workflows/build-apk.yml`, `LICENSE`, `THIRD_PARTY_NOTICES.md`.
- **Alur workflow:** `npm install` → `npx cap add android` (proyek Android dibuat saat build, tidak di-commit) → `git clone` whisper.cpp pada tag `WHISPER_TAG` (bawaan `v1.7.6`, bisa diganti saat menjalankan manual) ke `native/cpp/whisper.cpp` → `apply-native.mjs` menempelkan Java, C++, `whisper.gradle`, izin `WAKE_LOCK` dan `largeHeap` → `./gradlew assembleDebug` (+ `assembleRelease` bila secret keystore ada) → unggah artefak APK.
- **Mesin native:** hanya `arm64-v8a`; whisper.cpp dikompilasi `Release` walau varian debug, tanpa OpenMP, `-march=armv8.2-a+fp16` (matikan dengan `-DSUBLAB_ARM_FP16=OFF`), penyelarasan halaman 16 KB. CMake mendeteksi otomatis apakah tag whisper.cpp mendukung VAD dan `suppress_nst`.
- **Plugin `Whisper` (`WhisperPlugin.java`):** memenuhi kontrak bagian 3 dan 18. Metode tambahan khusus APK: `openStream({name}) → {path}` dan `writeChunk({path,data})` (base64) karena File/Blob tidak bisa lewat bridge; UI mengirim media per potongan 3 MB ke cache lewat `nativeMediaPath()`, lalu `transcribe({file: path, ...})`. File sementara dihapus setelah proses.
- **Dekode audio:** `AudioDecoder` (MediaExtractor + MediaCodec) → mono 16 kHz float, mendukung rentang waktu (`offsetMs`, `durationMs`), `normalize` (puncak 0,95) dan `denoise` (high-pass 80 Hz sederhana).
- **Model:** disimpan di `filesDir/models/ggml-<nama>.bin`, unduh dari `huggingface.co/ggerganov/whisper.cpp` (Silero VAD dari `ggml-org/whisper-vad`, file `ggml-silero-v5.1.2.bin`), dapat dilanjutkan (header `Range`, potongan `.part`), menolak dengan kode `NO_SPACE` bila sisa penyimpanan < ukuran + 300 MB, kode `CANCELED` saat dibatalkan.
- **Event:** `dlprogress`, `progress` (`load`/`run`/`done`; CPU dari waktu CPU proses, RAM dari `VmRSS`, disk dari `StatFs`), `segment` (waktu kata dari token whisper, ms absolut).
- **Simpan file:** `saveFile()` di APK menulis ke cache lewat `@capacitor/filesystem` lalu membuka lembar Bagikan (`@capacitor/share`). **Tombol back** (`@capacitor/app`) menutup dialog, menu, layar Project, editor kata atau panel Aa/ABCD; bila tidak ada, aplikasi diminimalkan.
- **Batasan yang diketahui:** transkripsi tidak berjalan sebagai foreground service (hanya `WAKE_LOCK`), jadi Android bisa menghentikannya bila aplikasi lama di latar belakang; audio panjang dimuat ke memori (1 jam ≈ 230 MB float); tanpa akselerasi GPU.
