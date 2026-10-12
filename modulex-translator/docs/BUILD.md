# Building ModuleX Translator

## Toolchain (pinned)

| Component | Version | Where |
|-----------|---------|-------|
| Gradle | 8.14.3 (wrapper) | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.13.0 | root `build.gradle.kts` (buildscript) + `gradle/libs.versions.toml` |
| Kotlin | 2.2.21 (+ Compose compiler plugin) | `gradle/libs.versions.toml` |
| JDK | 17+ (tested with 21) | bytecode target 17 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 29 | `app/build.gradle.kts` |
| NDK | r28c (`28.2.13676358`) | `app/build.gradle.kts` |
| CMake | 3.22.1+ | `app/build.gradle.kts` |
| Compose BOM | 2025.10.00 | catalog |
| CameraX | 1.5.1 | catalog |
| WebRTC | `io.github.webrtc-sdk:android:150.7871.01` (this repository's parent project) | catalog |
| OkHttp / ZXing / onnxruntime-android | 4.12.0 / 3.5.3 / 1.22.0 | catalog |

Native sources are pinned by commit in `tools/fetch-native-deps.sh`:
llama.cpp `f2cc7282`, whisper.cpp `7e26d3b0`, tesseract `b2544450` (5.5.3), leptonica `8ad618f1`
(1.88.0), piper1-gpl `35b0f1ec`, espeak-ng `212928b3` (the revision libpiper pins).

### Upgrading a dependency
1. Change the version in `gradle/libs.versions.toml` (or the commit in `fetch-native-deps.sh`).
2. Run the full test set below; for llama.cpp also run `tools/check_gguf.py` against the pinned model
   and the on-device benchmark in the Diagnostics screen.
3. Record the change in `docs/VERIFICATION_REPORT.md`.

## 1. Fetch native sources

```bash
tools/fetch-native-deps.sh            # into ./third_party (git-ignored)
```

## 2. JVM and host-native tests (no Android SDK required)

```bash
./gradlew :core:test        # protocol, pairing, TLS, session, assets, VAD, segmentation, policies
./gradlew :engines:test     # builds native/ for the host with CMake+Ninja, then runs real JNI tests
./gradlew :signaling:test   # signaling relay
```
`:engines:test` needs `cmake`, `ninja`, a C++17 compiler, a JDK with JNI headers, and `python3` with
`numpy` (for the random-weight pipeline fixture). Optional real-model test:
`./gradlew :engines:test -Pmodulex.testModel=/path/to/model.gguf`.

## 3. Android build

Create `local.properties` with `sdk.dir=/path/to/Android/Sdk` (or set `ANDROID_HOME`). Install
platform 36, build-tools, NDK `28.2.13676358` and CMake 3.22.1 with `sdkmanager`.

```bash
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest   # with a device attached
```

The APK targets `arm64-v8a` only. The native code is built for ARMv8.2-A with dot-product + FP16;
the app checks `/proc/cpuinfo` and refuses to load the engines (with a clear message) on CPUs
without them instead of crashing.

### Optional GPL voice engine
`./gradlew :app:assembleDebug -Pmodulex.withPiper=true` additionally builds `libmx_tts.so`
(Piper + espeak-ng) and bundles onnxruntime. **The resulting APK must be distributed under
GPL-3.0-compatible terms** (see `docs/LICENSING.md`). Without it, spoken output uses installed
system voices that work offline.

## 4. Release build

Create `keystore.properties` (never committed; `.gitignore` covers it):
```
storeFile=/secure/path/modulex-release.jks
storePassword=...
keyAlias=modulex
keyPassword=...
```
Then `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (R8-minified,
signed). Without the file the release APK is produced unsigned.

## 5. Language packs before release

`app/src/main/assets/asset_manifest.json` ships OCR packs pinned to `tessdata_fast` at a fixed commit.
The translation model, speech models and voices are listed **unpinned** until verified:

```bash
python3 tools/check_gguf.py third_party/llama.cpp/gguf-py <downloaded.gguf>      # must report "compatible"
python3 tools/pin_assets.py --pack translation-core --repo tencent/Hy-MT2-1.8B-GGUF \
    --file <exact Q4_K_M filename> --as model.gguf
python3 tools/pin_assets.py --pack speech-base --repo ggerganov/whisper.cpp --file ggml-base.bin
./gradlew :core:test   # ShippedManifestTest validates the result
```
`pin_assets.py` resolves the repository commit, downloads each file, and checks the SHA-256 it computes
against the hub's LFS hash before writing an immutable `resolve/<commit>/` URL.

## 6. Signaling relay
See `signaling/README.md` (`./gradlew :signaling:installDist`, or `docker build -f signaling/Dockerfile .`).
