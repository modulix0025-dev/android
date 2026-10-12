# Installing and setting up ModuleX Translator

## Install
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk      # or app-release.apk
```
Requirements: Android 10+ (API 29), 64-bit ARM with ARMv8.2 dot-product (most phones from 2019 on;
the app tells you if your CPU is not supported). Plan for about 2 GB of free storage for the
translation and speech packs.

## First start
- Arabic and English text recognition are bundled and verified automatically.
- Open **Language Packs** and install:
  1. **Offline Translation Engine**: required for every feature (~1.1–1.2 GB expected).
  2. **Speech Recognition Engine**: for voice translation and conversations.
  3. Extra **Text recognition** languages as needed.
- Each download shows its size and asks for confirmation; files are verified before use.

### Packs that are "Not yet available for download"
Until the release engineer pins a pack (`tools/pin_assets.py`), the app will not download it.
You can still **Import from file** (e.g. a GGUF you downloaded yourself). The app warns that the file
cannot be verified, records its SHA-256, labels it "Imported by you", and refuses files whose model
architecture does not match.

Sideloading a model during development:
```bash
adb push Hy-MT2-1.8B-Q4_K_M.gguf /sdcard/Download/
# then Language Packs → Offline Translation Engine → Import from file
```

## Offline setup
After the packs are installed, everything except Internet conversations works in airplane mode.
See [OFFLINE_TESTING.md](OFFLINE_TESTING.md) to verify.

## Spoken output
Uses voices already installed on the phone that work offline (Settings → System → Languages →
Text-to-speech → install voice data). Phones without an offline voice for a language show the text only.
Builds made with the optional Piper engine can install voice packs instead.
