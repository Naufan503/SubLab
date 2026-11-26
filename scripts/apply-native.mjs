// Menempelkan plugin native SubLab ke proyek android/ yang dibuat `npx cap add android`.
// Jalankan SETELAH: npx cap add android  &&  whisper.cpp di-clone ke native/cpp/whisper.cpp
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const app = path.join(root, 'android', 'app');
const must = (p, msg) => { if (!fs.existsSync(p)) { console.error('GAGAL: ' + msg); process.exit(1); } };

must(app, 'folder android/app tidak ada. Jalankan "npx cap add android" dulu.');
must(path.join(root, 'native', 'cpp', 'whisper.cpp', 'CMakeLists.txt'),
  'whisper.cpp belum di-clone. Jalankan: git clone --depth 1 --branch <tag> https://github.com/ggml-org/whisper.cpp native/cpp/whisper.cpp');

const copyDir = (from, to) => {
  fs.mkdirSync(to, { recursive: true });
  for (const e of fs.readdirSync(from, { withFileTypes: true })) {
    if (e.name === '.git') continue;
    const s = path.join(from, e.name), d = path.join(to, e.name);
    if (e.isDirectory()) copyDir(s, d); else fs.copyFileSync(s, d);
  }
};

// 1) Java
const javaDst = path.join(app, 'src/main/java/com/sublab/app');
copyDir(path.join(root, 'native/java/com/sublab/app'), javaDst);

// 2) C++ + whisper.cpp
copyDir(path.join(root, 'native/cpp'), path.join(app, 'src/main/cpp'));

// 3) Gradle
fs.copyFileSync(path.join(root, 'native/whisper.gradle'), path.join(app, 'whisper.gradle'));
const bg = path.join(app, 'build.gradle');
let g = fs.readFileSync(bg, 'utf8');
if (!g.includes('whisper.gradle')) {
  g += '\n// SubLab: whisper.cpp (JNI)\napply from: "whisper.gradle"\n';
  fs.writeFileSync(bg, g);
}

// 4) AndroidManifest: izin dan heap besar
const mf = path.join(app, 'src/main/AndroidManifest.xml');
let m = fs.readFileSync(mf, 'utf8');
if (!m.includes('android.permission.WAKE_LOCK')) {
  m = m.replace('</manifest>', '    <uses-permission android:name="android.permission.WAKE_LOCK" />\n</manifest>');
}
if (!m.includes('android.permission.INTERNET')) {
  m = m.replace('</manifest>', '    <uses-permission android:name="android.permission.INTERNET" />\n</manifest>');
}
if (!m.includes('android:largeHeap')) {
  m = m.replace('<application', '<application\n        android:largeHeap="true"');
}
fs.writeFileSync(mf, m);

console.log('OK: plugin native SubLab terpasang di android/app');
