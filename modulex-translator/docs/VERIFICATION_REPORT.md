# ModuleX Translator: verification report

Date: 2026-10-12 · Branch: `claude/funny-lamport-qsdrj4` · Location: `modulix0025-dev/android/modulex-translator/`

## Build environment and its limits
The project was built in a cloud container with **no Android SDK/NDK, no attached device, and an
egress policy that blocked `dl.google.com` (Android SDK, NDK and Google Maven, so no AGP, AndroidX
or Compose) and `huggingface.co` (all model weights)**. Maven Central and GitHub sources were
reachable. As a result:

- **No APK (debug or release) has been produced**, and the app has not been built with AGP.
  As a substitute, all 32 app source files were **type-checked with Kotlin 2.2.21 + the Compose
  compiler plugin**. That check used the real WebRTC 150.7871.01, OkHttp, ZXing, `core` and `engines`
  jars, the Android API 36 framework classes (Robolectric `android-all`) and JetBrains Compose 1.9
  desktop artifacts. Hand-written stubs stood in for activity, CameraX, navigation, lifecycle and
  core-ktx (Google Maven blocked). Three compile errors and one start-up race found this way were
  fixed. APIs checked only against stubs remain a risk until a real `./gradlew :app:assembleDebug
  :app:lintDebug`.
- **No real model was loaded.** Hy-MT2, Whisper and Piper weights could not be downloaded, so there
  are no translation quality, speed or memory figures, and the corresponding packs ship *unpinned*.
- **Nothing was run on a phone.** Device validation, offline airplane-mode tests, two-device transport
  tests and battery/thermal runs are procedures in `docs/`, not results.

## What was verified, and how

| Area | Evidence | Where verified |
|---|---|---|
| Wire protocol: validation, strict decoding, size limits | `ProtocolTest` (5 tests) | host JVM |
| Reliable delivery: sequencing, cumulative acks, retries, bounded queue, duplicate drop | `ReliabilityTest` (4) | host JVM |
| Pairing: QR payload round-trip and rejection cases, HMAC proofs, SAS, pairing-code KDF | `PairingTest` (6) | host JVM |
| TLS 1.3 over streams: pinning, client-cert requirement, large/many frames, real loopback TCP | `TlsTransportTest` (5) | host JVM |
| Conversation session: authenticated handshake, both directions, wrong secret rejected, intruder rejected, reconnect replay without duplicates, language updates, BYE | `ConversationSessionTest` (5) | host JVM |
| Protocol latency over loopback TLS/TCP | `LoopbackLatencyTest`: connect 29–37 ms, median ≈1 ms, P95 3–5 ms (2 runs) | host JVM (loopback only) |
| Asset manager: verified install, resume after interruption, corruption rejected and detected, unpinned never downloaded, storage check, in-use protection, import with/without pin, manifest validation, GGUF header parsing | `AssetsTest` (11) | host JVM + local HTTP server |
| Shipped manifest: bundled OCR hashes match the files, every downloadable pack is pinned, registry ↔ OCR packs consistent | `ShippedManifestTest` | host JVM |
| VAD utterance segmentation, transcript overlap merge, non-speech filter | `SpeechTest` (7) | host JVM (synthetic audio) |
| Prompt templates, long-text splitting (Latin/Arabic/CJK, layout-preserving), sanitiser | `TranslationTest` (9) | host JVM |
| Language registry, script detection (pair and unique), readiness rules, thermal/memory policy | `LanguageTest` (6) | host JVM |
| **Native translation pipeline through JNI**: load, model chat template via Jinja, tokenise, generate, stream, cancel, context overflow, truncated/wrong files rejected without crash | `TranslationEngineTest` (3 pass, 1 skipped = real model) with a random-weight `hunyuan-dense` fixture | host x86_64 build of `libmx_ai.so` |
| **Native OCR through JNI**: English CER 0.000; Arabic CER 0.660 raw → 0.000 (words exact); mixed Arabic/English page 4/4 lines, CER 0.043; invalid input rejected | `OcrEngineTest` (5) | host build of `libmx_ocr.so` |
| Native libraries export only `Java_*` symbols and import no socket/DNS functions | `nm -D` on host builds | host |
| Signaling relay: join, relay, room full, invalid room, size/rate limits, peer-left, idle expiry, healthz | `signaling` tests (23); real Netty `installDist` checked with curl (200/400/101/426, TLS) | host JVM |

Totals: **96 passed, 0 failed, 1 skipped** (`docs/test-results/JVM_AND_HOST_NATIVE.md`).

## Implemented but not compiled or run (Android app)
Compose UI for all eight entry points in English and Arabic (RTL, per-language text direction, light/dark
themes); Language Packs (consent, progress, verify, delete, import); text translator (paste, copy, share,
swap, detection, history, retry, input limit, cancel-on-new-request); voice translator (VAD, partials,
measured latency, spoken output); camera translator (CameraX still capture, flash, gallery, OCR
pipeline, translation); conversation setup and live screens (QR show/scan, pairing code, SAS
confirmation, hands-free / push-to-talk / text, delivery status, latency, reconnect, end); history;
settings (Keystore-encrypted TURN credentials); privacy and full third-party notices; debug diagnostics
with an on-device benchmark; foreground service; engine hub (lazy loading, eviction, thermal policy,
CPU check); offline-only speech output; transports (TCP+TLS for LAN and Wi-Fi Direct, BLE L2CAP+TLS,
WebRTC data channel + signaling client).

## Requirement coverage

| Requirement | State |
|---|---|
| Single-device text/voice/camera translation | Implemented. Engine path verified on host. Not run on device. |
| Hy-MT2 runtime integration | Implemented (llama.cpp mainline). Real model not loaded. Q4_K_M compatibility to be confirmed with `tools/check_gguf.py`. |
| Offline speech recognition | Implemented (whisper.cpp). Model not available here; not run. |
| OCR (Arabic/English first) | Implemented and verified on host with bundled data. Known Arabic gaps documented. |
| Offline TTS | System offline voices (default) or Piper (GPL opt-in). Not run. |
| Two-device conversation | Protocol and security verified on host over loopback TCP. Android transports implemented, not run. |
| Bluetooth discovery + transport | Implemented (BLE advertising + L2CAP CoC + TLS). Not run. |
| Local offline network | LAN/hotspot + Wi-Fi Direct implemented. Not run. |
| Internet mode | WebRTC client + relay. Relay verified; client not run. No server is hard-coded. |
| Model manager (consent, SHA-256, resume, atomic, deletion safety) | Verified on host. |
| Security/privacy controls | Implemented. Protocol security verified on host. Device network audit pending. |
| Localization EN/AR, RTL | Implemented (231 strings each; key parity and every referenced id checked by script). Not rendered on device. |
| Debug and release APK | **Not produced** (no Android SDK reachable). |

**Completion estimate:** about 60%, measured as the share of the required deliverables that are
implemented *and* verified in some environment. Source-complete is about 90%. Device-verified is 0%.

## Remaining blockers for a production release

| Blocker | Cause | Impact | Resolution |
|---|---|---|---|
| No AGP build, no APK | Android SDK/NDK and Google Maven blocked here | Sources type-check against substitutes; resource merging, R8, lint, native cross-compile and the APK are unverified | Build with SDK + NDK r28c: `./gradlew :app:assembleDebug :app:lintDebug` and fix the errors. Allow `dl.google.com` in the environment to do it here. |
| Translation model not pinned or validated | `huggingface.co` blocked | No offline translation until a pack is installed. Quality and speed unknown. | Download Hy-MT2-1.8B GGUF Q4_K_M, run `check_gguf.py`, `pin_assets.py`, the corpus test, then on-device benchmarks. |
| Speech model not pinned | same | Voice features need a manual import | `pin_assets.py --pack speech-base ...` |
| STQ low-bit models unsupported | llama.cpp PR #22836 not merged | Cannot use the 440 MB 1.25-bit model | Track upstream. Integrating that patch set would be a separate, reviewed change. |
| No device validation | No hardware attached | Latency, memory, thermal and battery unknown; transports untested on radios | Run `DEVICE_VALIDATION.md` and `OFFLINE_TESTING.md` on two phones (incl. the OPPO Reno). |
| Arabic OCR on real photos | Synthetic tests now pass (per-line height normalisation, word spacing fixed; tessdata_best evaluated and not needed) | Real photos (blur, perspective, glare) unmeasured | Evaluate with device photos; `-Pmodulex.tessdata` switches data for comparison. |
| Model licence confirmation | Model card not retrievable here | Commercial distribution risk | Confirm the Hy-MT2 model-card licence (`LICENSING.md` checklist). |
| GPL voice engine | Piper/espeak-ng are GPL-3.0 | Distributing the Piper build has GPL-3.0 obligations | Ship without Piper (default), or comply with GPL-3.0. |
| Signaling deployment | Needs an operator | Internet mode unavailable until configured | Deploy `signaling/` behind TLS; optional TURN (coturn). |
