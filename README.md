<div align="center">

<img src="assets/icon-only.png" alt="SubLab icon" width="120">

# SubLab

**Offline subtitle & caption studio for Android.**
Turn any audio or video into word-timed captions with Whisper, fine-tune them on your phone, and export to Keyflow (`.kf`), SRT, VTT, ASS, TTML, LRC, TXT or JSON.

`whisper.cpp` · Capacitor · single-file HTML UI · no cloud, no account, no tracking

</div>

> **Status:** early stage. The UI and export logic are implemented and exercised in a browser. The native Android layer (JNI + Capacitor plugin) was written without being compiled or run on a device, so the first GitHub Actions build may surface small errors. If the build fails, open the failing step in the **Actions** tab and fix or report the log. See [Known limitations](#known-limitations).

---

## What is SubLab?

SubLab is a caption workflow in one app. You pick a video or audio file, run **OpenAI Whisper** on the phone itself (through **whisper.cpp**), get a transcript with **per-word timestamps**, and then edit it with tools built for touch screens: cut, merge, retime, position, restyle and export.

It was built first for creators who edit in **Keyflow** (a video editor). SubLab reads a Keyflow `.kf` project as a *template*, copies the look of its text layers (font, colour, stroke, effects, animation) onto every caption, and writes a new `.kf` project with all captions already placed, animated and timed. It also exports the standard subtitle formats, so it is useful with any other editor that accepts SRT, VTT, ASS, TTML, LRC or JSON.

Everything runs on the device. The only network use is downloading Whisper models once from Hugging Face.

## Highlights

- **Offline transcription** with whisper.cpp (JNI, `arm64-v8a`). Models from Tiny to Large v3 Turbo, including quantised q5/q8 variants (about 31 MB to 2.9 GB), downloaded on demand and stored privately in the app.
- **Word-level timing** from Whisper tokens, used for per-word highlighting, per-word animation and precise cutting.
- **Touch-first caption editor** with cut, merge, split / auto-crop, retime, delete, duplicate, undo/redo (150 steps) and an offset tool.
- **Per-word timing editor** with a zoomable timeline, waveform, tap-to-sync and slow-motion preview, for fixing transcripts that drift or were reworded.
- **Keyflow template presets**: text layers named `1`, `2`, `3`… become caption presets that keep fonts, colours, strokes, effects and animations.
- **Four text modes per caption**: full sentence, word by word, progressive build-up, and progressive build-up with fixed position.
- **Rich export**: `.kf` with groups, keyframes, text animators, media layer and markers, plus SRT, VTT, ASS, TTML, LRC, TXT, per-word JSON and the raw (unedited) transcript.
- **Projects with autosave**, thumbnails, duplicate/rename/delete, and portable `.zip` backup/import (media, transcript and template included).
- **Works in a desktop browser too** with a simulated Whisper engine, so the whole editor can be tried without an APK.

## How it works

```
 audio / video ──► decode to 16 kHz mono PCM ──► whisper.cpp ──► segments + word timestamps
   (MediaExtractor / MediaCodec)                 (optional VAD)            │
                                                                           ▼
   Keyflow template (.kf) ──► presets ──► caption editor (cut, retime, style, position)
                                                                           │
                                  ┌────────────────────────────────────────┴───────────┐
                                  ▼                                                    ▼
                          Keyflow project (.kf)                    SRT / VTT / ASS / TTML / LRC / TXT / JSON
```

## Features in detail

### Transcription (native)

| Area | Details |
|---|---|
| Engine | whisper.cpp built from source in CI (pinned tag, default `v1.7.6`), compiled `Release` for `arm64-v8a`, ARMv8.2 fp16 optimised (can be turned off for older CPUs). |
| Audio decoding | Android `MediaExtractor` + `MediaCodec` → mono 16 kHz float PCM. Supports a start/end range, volume normalisation and a simple 80 Hz high-pass denoise. |
| Models | tiny, base, small, medium, large-v2, large-v3, large-v3-turbo and q5/q8 variants. Downloaded from `huggingface.co/ggerganov/whisper.cpp`, resumable, with free-space checks and an info bubble (ⓘ) per model (size, parameters, speed, language accuracy, RAM). |
| Languages | Auto-detect, Indonesian, English, Sundanese, Javanese, Malay and about 16 more; optional translate-to-English. |
| Settings | **Basic:** language, initial prompt, time range, Fast / Balanced / Accurate presets. **Advanced:** threads, Silero VAD (threshold, minimum speech/silence, padding), max segment length, split on word, single segment, suppress `[MUSIC]`-style tags, normalise, denoise. **Expert:** beam size, best-of, temperature and increment, entropy / log-prob / no-speech / word thresholds, context size, DTW, GPU, suppress regex. |
| Progress panel | CLI-style view with phase, progress bar, elapsed / remaining time, real-time speed factor, CPU, RAM (with peak) and disk meters, live segment log and cancel. |
| Imports instead of transcribing | SRT, VTT and whisper.cpp `-ojf` JSON (tokens are merged into words). Speaker changes marked with `/` or `-` are split into separate captions. |

### Caption editor

- **Caption list** (Spotify-style): the active line is highlighted and auto-scrolls; words highlight as they are spoken; overlapping captions (several speakers) light up together.
- **Cut ✂**: drag a playhead-style marker to a word boundary or a single letter and split a caption in two; timing is taken from word timestamps when available.
- **Split / auto-crop**: break captions by maximum word count and/or so that each piece fits one line at a chosen screen width (needs a template to measure text).
- **Merge ⛓, delete 🗑, duplicate ⧉**, with presets recomputed automatically.
- **Time tools ⏱**: mode 1 moves the boundary between two captions, mode 2 sets start and end of one caption with scroll rulers; both loop the audio so you can check sync by ear. Overlap with neighbouring captions is highlighted while you drag.
- **Per-word timing editor**: timeline with waveform, draggable word edges (joined or independent), word chips for quick selection, tap-to-sync while the audio plays, 0.25× to 0.75× slow-motion preview, and caption times that follow the words.
- **Text tools (Aa)**: UPPER, lower, Sentence case, Title Case, strip symbols, and case-insensitive find and replace, for the current caption, a selection or all captions. Word timings are remapped so edits do not break highlighting.
- **Multi-select** with range selection, shared by the text, style and position panels.
- **Offset**: shift every caption (and word) by a signed number of seconds.
- **Raw transcript safety net**: the original transcript is stored with the project, exportable, and restorable through a two-step confirmation.

### Styling, position and presets

- **Presets** come from the text layers of a Keyflow template (`1`, `2`, `3`, also `text1`, `preset1`…). Assign them in sequence (1, 2, 3, 1, 2…), by caption duration (short → preset 1, long → last preset), the same preset for all, or manually per caption.
- **Text mode per caption (A–D)**: A full sentence, B word by word (replace), C progressive build-up (words appear and the sentence leaves together), D build-up with fixed word positions.
- **Position, scale and rotation** per caption with scroll rulers and *apply to all / apply to selection*. Preview, wrapping and export use the same maths.
- Preset text scale is ignored on purpose, so every caption starts at the same default size and preview matches export.
- **Preview** with a normal tab (video or audio + text) and a *Preset .kf* canvas that shows the video letter-boxed under the text at the template's aspect ratio. Motion and effects are intentionally not previewed.

### Export

| Format | Notes |
|---|---|
| **Keyflow `.kf`** | New project built from your template: one layer per caption (or per word, depending on mode), `GROUP` layers for build-up modes, copied effects, animation keyframes re-anchored to each caption's in/out time, per-character text animators re-timed, optional media layer and markers. |
| **SRT / VTT / LRC / TXT** | Standard text formats. |
| **ASS** | With a template loaded: one style per preset (font, size, colour, bold/italic, alignment), per-caption `\pos`, scale and rotation, and the A–D text modes. |
| **WebVTT / TTML (IMSC1)** | Preset placement, scale and style when a template is loaded. |
| **JSON (per word)** | whisper.cpp-compatible `transcription[]` plus a `segments[]` array with words in seconds and placement data. Re-importable. |
| **Raw transcript** | The untouched original, in its original format. |

On Android, exports are written to the app cache and opened with the system Share sheet.

### Projects

- Automatic saving (IndexedDB) after every change: transcript, settings, template, raw transcript and media.
- Project screen with thumbnail, title, duration, aspect ratio and source file; menu for **export ZIP**, **duplicate**, **rename** and **delete** (with confirmation); **import ZIP** at the top.
- The Android Back button closes dialogs, menus, the project screen and editors before minimising the app.

## Architecture

| Layer | Technology |
|---|---|
| UI | One dependency-free HTML file (`www/index.html`): editor, Keyflow reader/writer (ZIP + JSON), export formats, project storage. |
| Shell | [Capacitor](https://capacitorjs.com/) 6 with `@capacitor/app`, `@capacitor/filesystem` and `@capacitor/share`. |
| Native plugin | Java `Whisper` Capacitor plugin: model store (download, resume, delete), audio decoder, engine wrapper, chunked media upload (files cannot cross the Capacitor bridge). |
| Engine | [whisper.cpp](https://github.com/ggml-org/whisper.cpp) via a small JNI bridge (`sublab_whisper.cpp`) and CMake/NDK. |
| CI | GitHub Actions builds the APK from scratch, including the Android project (`npx cap add android` is run on every build, so `android/` is not committed). |

The plugin contract (`listModels`, `getDeviceInfo`, `downloadModel`, `transcribe`, `cancel`, events `progress` / `segment` / `dlprogress`) is identical to the browser simulation, so the same UI code runs in both.

## Build the APK with GitHub Actions

1. Create a repository and upload **everything in this folder**, including `.github/`.
2. Open **Actions → Build APK → Run workflow** (or push to `main`).
3. When the run is green, download the **SubLab-apk** artifact and install `app-debug.apk` (allow installs from unknown sources).

The first build takes roughly 15–30 minutes because whisper.cpp is compiled from scratch.

**Change the whisper.cpp version:** fill in *Tag whisper.cpp* when running the workflow (default `v1.7.6`; see the [tag list](https://github.com/ggml-org/whisper.cpp/tags)).

**Signed release APK (optional):** add these repository secrets and the workflow also produces `app-release.apk`: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and, optionally, `ANDROID_KEY_PASSWORD`.

```bash
keytool -genkeypair -v -keystore release.keystore -alias sublab -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore
```

## App icon

The launcher icon is generated during the build by [`@capacitor/assets`](https://github.com/ionic-team/capacitor-assets) from the `assets/` folder:

| File | Purpose |
|---|---|
| `assets/icon-only.png` | Legacy/square icon, 1024×1024 PNG. |
| `assets/icon-foreground.png` | Adaptive-icon foreground, 1024×1024 transparent PNG. Keep the artwork inside the central ~66 % (Android crops the rest). |
| `assets/icon-background.png` | Adaptive-icon background, 1024×1024 PNG. |

To use your own icon, replace those three files (keep the names and size) and push. No code changes are needed. To regenerate locally after `npx cap add android`, run `npm run icons`. The app name shown under the icon comes from `appName` in `capacitor.config.json`.

## Repository layout

| Path | Purpose |
|---|---|
| `www/index.html` | The whole UI, plus the APK bridge (media upload, file saving, Back button). |
| `assets/` | Source images for the app icon. |
| `native/java/…` | Capacitor `Whisper` plugin, audio decoder, model store, `MainActivity`. |
| `native/cpp/` | JNI bridge and `CMakeLists.txt` (whisper.cpp is cloned here during CI). |
| `native/whisper.gradle` | NDK/CMake configuration appended to `app/build.gradle`. |
| `scripts/apply-native.mjs` | Copies the native code into the generated `android/` project and patches the manifest. |
| `.github/workflows/build-apk.yml` | The build pipeline. |
| `docs/kf-caption-spec.md` | Full functional specification (in Indonesian). |
| `README.id.md` | Indonesian README. |
| `LICENSE`, `THIRD_PARTY_NOTICES.md` | Project licence and required third-party notices. |

## Checklist after installing

- The **SIMULATION** badge in the Whisper model box should be **gone** (it means the native plugin was detected).
- Download **Tiny q5** (31 MB) first and transcribe a short clip.
- Exporting a `.kf` should open the Android Share sheet.
- Back should close dialogs and the Project screen before minimising the app.

## Known limitations

- The native layer has not yet been verified on a device; expect to iterate on the first builds.
- Only `arm64-v8a` is built (nearly all modern phones). For CPUs without fp16, set `-DSUBLAB_ARM_FP16=OFF` in `native/whisper.gradle`.
- Transcription holds a `WAKE_LOCK` but is not a foreground service, so Android may stop it if the app stays in the background for long. Keep the app open for large models. Long audio is decoded into memory (about 230 MB per hour).
- Some Keyflow behaviours are assumptions that still need checking inside Keyflow itself (rotation/scale pivot, font and stroke key names, stroke units). ASS and WebVTT/TTML styling is an approximation; effects and animations cannot be expressed in those formats.
- Model download size and speed depend on Hugging Face and your connection; large models are slow and can overheat a phone.

## Privacy

Audio, video, transcripts and projects never leave the device. There is no account, analytics or advertising. The app connects to the internet only to download Whisper models and the optional Silero VAD model from Hugging Face when you ask it to.

## Credits and licences

SubLab © 2026 Naufan.Imd. See [`LICENSE`](LICENSE).

Built on [whisper.cpp](https://github.com/ggml-org/whisper.cpp) and [OpenAI Whisper](https://github.com/openai/whisper) (MIT), [Silero VAD](https://github.com/snakers4/silero-vad) (MIT) and [Capacitor](https://github.com/ionic-team/capacitor) (MIT). The MIT notices must be shipped with any distribution, see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). "Keyflow" is a product of its respective owner; SubLab is an independent tool and is not affiliated with it.
