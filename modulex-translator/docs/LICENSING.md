# Third-party inventory and redistribution notes

The app shows these notices under **Privacy & Third-Party Notices → Third-Party Notices**
(`app/src/main/assets/licenses/`, full license texts copied from the pinned sources).
Ordinary screens use the ModuleX Translator brand and neutral engine names; upstream identities are kept
in the manifest, notices, diagnostics and this file, as attribution requires.

| Component | License | In APK? | Notes |
|-----------|---------|---------|-------|
| llama.cpp / ggml (`f2cc7282`) | MIT | yes (`libmx_ai.so`) | |
| nlohmann/json (via llama.cpp common) | MIT | yes | |
| whisper.cpp (`7e26d3b0`, user fork) | MIT | yes | |
| Tesseract 5.5.3 (`b2544450`, user fork = upstream HEAD) | Apache-2.0 | yes (`libmx_ocr.so`) | |
| Leptonica 1.88.0 | BSD-2-Clause | yes | |
| tessdata_fast (`87416418`) ara, eng | Apache-2.0 | yes (assets) | others downloadable |
| WebRTC `io.github.webrtc-sdk:android` 150.7871.01 | BSD-3-Clause + bundled third-party licenses | yes | full `WEBRTC.md` included |
| Kotlin, kotlinx, AndroidX, Compose, CameraX | Apache-2.0 | yes | |
| ZXing core 3.5.3 | Apache-2.0 | yes | |
| OkHttp 4.12.0 / Okio | Apache-2.0 | yes | |
| **Hy-MT2-1.8B GGUF** (Tencent Hunyuan) | Repository code: Apache-2.0 (`Tencent-Hunyuan/Hy-MT2/LICENSE.txt`) | downloaded pack | **Check the model card license on Hugging Face before commercial redistribution or bundling.** It could not be retrieved from the build environment. |
| Whisper ggml models | MIT (OpenAI weights; ggml conversion) | downloaded pack | |
| **Piper (piper1-gpl)** | **GPL-3.0** | only with `-Pmodulex.withPiper=true` | Makes the distributed APK subject to GPL-3.0 (source offer for the whole app). |
| **eSpeak NG** | **GPL-3.0-or-later** | only with Piper | phoneme data shipped as a pack |
| onnxruntime-android 1.22.0 | MIT | only with Piper | |
| Piper voices | per-voice licenses (see each MODEL_CARD) | downloaded packs | Verify each voice before distribution. |

`cpp-httplib` is an archive dependency of llama.cpp's common library, but none of its code ends up
in `libmx_ai.so` (no socket imports, checked with `nm`). It is therefore not listed in the app.

## Commercial-release checklist
1. Confirm the Hy-MT2 model-card license and any use restrictions; record them in the manifest `license` field.
2. Decide on the voice engine: keep Piper off (system offline voices only), or ship a GPL-3.0 build
   with source availability.
3. Verify every Piper voice's license if voices are offered.
4. Keep `assets/licenses/` in sync when dependencies change (`tools/fetch-native-deps.sh` pins them).
