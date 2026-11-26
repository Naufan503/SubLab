# SubLab (APK)

> English README: [README.md](README.md)

Aplikasi Android offline: audio/video → transkrip Whisper → caption berstempel waktu → export `.kf` untuk Keyflow.
Antarmuka = satu file HTML (`www/index.html`), dibungkus Capacitor, mesin = whisper.cpp lewat plugin native `Whisper`.

> **Status jujur:** semua kode di sini ditulis tanpa bisa dikompilasi atau dijalankan di perangkat. Sintaks C++ dan Java sudah diperiksa, tetapi build pertama di GitHub bisa saja menemukan kesalahan kecil. Kalau gagal, salin log yang merah dari tab Actions untuk diperbaiki.

## Cara build di GitHub
1. Buat repositori baru di GitHub, lalu unggah **seluruh isi folder ini** (termasuk folder `.github`).
2. Buka tab **Actions** → workflow **Build APK** → **Run workflow** (atau cukup push ke cabang `main`).
3. Setelah hijau, buka run-nya dan unduh artefak **SubLab-apk** di bagian bawah. Di dalamnya ada `app-debug.apk`.
4. Pindahkan ke HP, izinkan pemasangan dari sumber tak dikenal, lalu pasang.

Build pertama sekitar 15–30 menit karena whisper.cpp dikompilasi dari nol.

### Mengganti versi whisper.cpp
Saat **Run workflow**, isi kolom *Tag whisper.cpp* (bawaan `v1.7.6`). Bila tag tidak ada, langkah `git clone` akan gagal dengan pesan jelas. Cek daftar tag di https://github.com/ggml-org/whisper.cpp/tags. Opsi VAD otomatis dimatikan bila tag yang dipakai belum mendukungnya.

### APK bertanda tangan (opsional)
APK debug sudah bisa dipasang, tetapi tiap build memakai kunci debug yang bisa berbeda. Untuk APK release dengan kunci tetap, tambahkan *Repository secrets*:
`ANDROID_KEYSTORE_BASE64` (isi keystore dalam base64), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, dan opsional `ANDROID_KEY_PASSWORD`. Workflow lalu ikut membuat `app-release.apk`.

Membuat keystore dan mengubahnya ke base64:
```
keytool -genkeypair -v -keystore release.keystore -alias sublab -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore
```

## Ikon aplikasi
Ikon dibuat otomatis saat build oleh `@capacitor/assets` dari folder `assets/`:
`icon-only.png` (ikon biasa, 1024×1024), `icon-foreground.png` (lapisan depan transparan, isi di zona aman tengah ±66%) dan `icon-background.png` (lapisan belakang). Untuk mengganti ikon cukup timpa ketiga file itu (PNG persegi 1024×1024) lalu push; tidak perlu mengubah kode.

## Isi repositori
| Path | Fungsi |
|---|---|
| `www/index.html` | UI SubLab (kf-caption.html + jembatan APK: unggah media, simpan file, tombol back) |
| `native/java/…` | Plugin Capacitor `Whisper`, dekoder audio, pengelola model, `MainActivity` |
| `native/cpp/` | Jembatan JNI (`sublab_whisper.cpp`) dan `CMakeLists.txt` |
| `native/whisper.gradle` | Konfigurasi NDK/CMake yang ditempel ke `app/build.gradle` |
| `scripts/apply-native.mjs` | Menempelkan semua file native ke proyek `android/` hasil `cap add` |
| `assets/` | Sumber ikon aplikasi (3 PNG 1024×1024) |
| `.github/workflows/build-apk.yml` | Build otomatis |
| `docs/kf-caption-spec.md` | Spesifikasi lengkap (bagian 19 = paket APK) |
| `LICENSE`, `THIRD_PARTY_NOTICES.md` | Lisensi proyek dan pemberitahuan MIT pihak ketiga (wajib ikut disertakan) |

Folder `android/` sengaja tidak di-commit: dibuat ulang tiap build oleh `npx cap add android`, jadi pembaruan Capacitor tidak bentrok dengan kode kita.

## Hal yang perlu dicek di HP setelah pasang
- Badge **SIMULASI** di kotak Model Whisper harus **hilang** (artinya plugin native terdeteksi).
- Unduh model **tiny q5** (31 MB) dulu untuk uji cepat, lalu coba transkripsi klip pendek.
- Ekspor `.kf`: harus muncul lembar Bagikan Android.
- Tombol back: menutup dialog/layar Project dulu, baru meminimalkan aplikasi.

## Catatan teknis
- Hanya `arm64-v8a` (hampir semua HP modern). Ukuran APK kecil karena model tidak dibundel; model diunduh dari Hugging Face dan disimpan di penyimpanan internal aplikasi.
- Transkripsi memakai `WAKE_LOCK`, bukan foreground service. Biarkan aplikasi terbuka saat memproses model besar.
- Media dikirim ke cache native per potongan 3 MB (File/Blob tidak bisa lewat bridge Capacitor), lalu dihapus setelah selesai.
- Untuk CPU lama tanpa fp16 ganti argumen CMake `-DSUBLAB_ARM_FP16=OFF` di `native/whisper.gradle`.
