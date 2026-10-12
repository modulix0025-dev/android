# ModuleX Translator

Offline AI translator and live bilingual conversation app for Android.

- **Text, voice and camera translation on the phone**: a local translation model (llama.cpp
  runtime), local speech recognition (whisper.cpp), local text recognition (Tesseract) and offline
  voices (installed system voices, or optional Piper voices).
- **Two-phone conversations**: each person speaks their own language; the speaker's phone
  recognises and translates locally and sends the translated text to the other phone over
  **LAN/hotspot, Wi-Fi Direct, Bluetooth LE** (all work without internet) or **Internet**
  (WebRTC data channel + a small signaling relay you host).
- **Privacy**: no cloud inference, no analytics, encrypted and mutually authenticated peer links,
  verified (SHA-256 pinned) language packs, local-only history.

> **Status:** see [docs/VERIFICATION_REPORT.md](docs/VERIFICATION_REPORT.md) for exactly what has
> been built and verified, what is verified only on a host JVM, and what still needs a device.

## Repository layout

| Path | What |
|------|------|
| `app/` | Android application (Kotlin, Jetpack Compose, CameraX, foreground conversation service) |
| `core/` | Platform-independent logic, JVM-tested: languages, wire protocol, pairing/auth, TLS-over-streams, conversation session, asset manager, VAD, segmentation, resource policy |
| `engines/` | Kotlin side of the native engines (JNI bindings, OCR pipeline), shared by the app and host tests |
| `native/` | C++ JNI libraries: `mx_ai` (llama.cpp + whisper.cpp, one shared ggml), `mx_ocr` (Tesseract + Leptonica), optional `mx_tts` (Piper + espeak-ng, GPL) |
| `signaling/` | WebRTC signaling relay (Ktor) for Internet mode, with Dockerfile |
| `tools/` | Pinned native dependency fetcher, asset manifest generator/pinner, GGUF compatibility checker, pipeline fixture generator |
| `docs/` | Build, architecture, protocol, security, licensing, testing and device-validation guides |

## Quick start

```bash
tools/fetch-native-deps.sh                 # pinned llama.cpp, whisper.cpp, tesseract, leptonica, piper, espeak-ng
./gradlew :core:test :engines:test :signaling:test   # JVM + host-native tests (no Android SDK needed)
# With an Android SDK + NDK r28c configured (local.properties sdk.dir=...):
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Full instructions: [docs/BUILD.md](docs/BUILD.md). Install and first-run setup: [docs/INSTALL.md](docs/INSTALL.md).

## Documentation

- [docs/BUILD.md](docs/BUILD.md): toolchain versions, build commands, release signing
- [docs/INSTALL.md](docs/INSTALL.md): installing, language packs, offline setup
- [docs/PAIRING.md](docs/PAIRING.md): connecting two phones
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): components, engine choices, model compatibility matrix
- [docs/PROTOCOL.md](docs/PROTOCOL.md): conversation protocol and pairing
- [docs/SECURITY.md](docs/SECURITY.md): threat model, privacy and network behaviour
- [docs/LICENSING.md](docs/LICENSING.md): third-party inventory and redistribution notes
- [docs/OFFLINE_TESTING.md](docs/OFFLINE_TESTING.md): airplane-mode verification procedure
- [docs/DEVICE_VALIDATION.md](docs/DEVICE_VALIDATION.md): hardware discovery, benchmarks, battery/thermal tests
- [docs/BENCHMARKS.md](docs/BENCHMARKS.md): measured results (and what is not measured yet)
- [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)
- [signaling/README.md](signaling/README.md): relay protocol and deployment
