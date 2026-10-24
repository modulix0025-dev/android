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
| **Hy-MT2-1.8B GGUF** (Tencent Hunyuan) | Apache-2.0: declared in the `tencent/Hy-MT2-1.8B-GGUF` model card metadata (read in CI at commit `a0c709d9`); repository code also Apache-2.0 | downloaded pack | Read the full model card text for any additional use notes before commercial redistribution. |
| Whisper ggml models | MIT (OpenAI weights; ggml conversion; declared in the `ggerganov/whisper.cpp` model card metadata) | downloaded pack | |
| **Piper (piper1-gpl)** | **GPL-3.0** | only with `-Pmodulex.withPiper=true` | Makes the distributed APK subject to GPL-3.0 (source offer for the whole app). |
| **eSpeak NG** | **GPL-3.0-or-later** | only with Piper | phoneme data shipped as a pack |
| onnxruntime-android 1.22.0 | MIT | only with Piper | |
| Piper voices | per-voice licenses (see each MODEL_CARD) | downloaded packs | Verify each voice before distribution. |

`cpp-httplib` is an archive dependency of llama.cpp's common library, but none of its code ends up
in `libmx_ai.so` (no socket imports, checked with `nm`). It is therefore not listed in the app.

## Dialect corpora (`app/src/main/assets/tm/`)

Built by `tools/tm_build.py` from the sources approved in `tools/tm_sources_approved.json`. Each
licence was read from the source itself in CI (job `tm-sources`, which prints the licence wording of
every candidate). Every pair records its provenance and licence in its own row.

| Source | Licence | Status | Notes |
|--------|---------|--------|-------|
| Tatoeba (tatoeba.org), dialect sentences and linked translations | CC BY 2.0 FR | **shipped** | Attribution per pair: sentence ids and contributor usernames in `provenance`. |
| Wiktionary usage examples (via kaikki.org / wiktextract) | CC BY-SA 4.0 (+ GFDL) | **shipped** | Share-alike applies to the corpus files that contain these rows (not to app code). |
| FLORES+ (`openlanguagedata/flores_plus`) | CC BY-SA 4.0 (dataset card) | not shipped | Gated on Hugging Face; needs an accepted gate and an `HF_TOKEN` secret; gate terms to be read first. |
| Darija Open Dataset (DODa) | **CC BY-NC 4.0** (LICENSE file) | excluded | Non-commercial; the README asks commercial users to contact the authors. |
| ArzEn-MultiGenre (Mendeley Data) | CC BY 4.0 (dataset page) | not shipped | Segments are taken from published song lyrics, novels and subtitles whose copyright is not the dataset authors'. Owner decision. |
| PADIC | unknown | not shipped | Licence page not reachable from CI. |
| Lisan (Birzeit) | CC BY 4.0 (reported) | not shipped | Licence page not reachable from CI; word glosses only, no sentence translations. |
| MADAR, NADI | research / non-commercial | excluded | |
| UFAL Parallel Corpus of North Levantine | CC BY-NC-SA 4.0 | excluded | Non-commercial. |

## Commercial-release checklist
1. Hy-MT2 model-card metadata declares Apache-2.0 (recorded in the manifest). Read the full card text for any use notes.
2. Decide on the voice engine: keep Piper off (system offline voices only), or ship a GPL-3.0 build
   with source availability.
3. Verify every Piper voice's license if voices are offered.
4. Keep `assets/licenses/` in sync when dependencies change (`tools/fetch-native-deps.sh` pins them).
