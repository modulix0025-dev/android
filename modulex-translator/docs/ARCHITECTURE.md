# Architecture

```
┌──────────────────────────── app (Android, Compose) ────────────────────────────┐
│ ui/  home · text · voice · camera · conversation · packs · history · settings │
│      privacy/notices · diagnostics (debug builds)                              │
│ engine/EngineHub ── loads engines on demand, thermal/memory policy, CPU check  │
│ audio/ AudioCapture (16 kHz mono) → VoicePipeline (VAD, partial+final ASR)     │
│ tts/SpeechOutput ── Piper voice pack (optional) or offline system voice only   │
│ assets/PackManager ── consented downloads, verified import, tessdata view     │
│ conversation/ ConversationController + ConversationService (foreground)       │
│   transport/ TCP+TLS (LAN, Wi-Fi Direct) · BLE L2CAP+TLS · WebRTC (Internet)   │
│ data/ settings (secrets via Keystore AES-GCM) · history (SQLite, local only)   │
│ security/ DeviceIdentity (Keystore EC P-256, self-signed cert)                 │
└────────────────────────────────────────────────────────────────────────────────┘
          │ uses                                   │ uses
┌──────── engines (JVM) ───────┐      ┌────────────── core (JVM) ──────────────┐
│ LocalTranslationEngine       │      │ language registry, script detection    │
│ LocalSpeechRecognizer        │      │ protocol: WireMessage, validation,     │
│ LocalTextRecognizer + OcrPipeline   │   reliable queue, sequencer             │
│ LocalVoice (Piper)           │      │ pairing: QR payload, HMAC proofs, SAS  │
│ JNI declarations             │      │ TLS 1.3 over any stream, framing       │
└──────────────┬───────────────┘      │ ConversationSession (auth, acks,       │
               │ JNI                  │   retries, reconnect replay, ping)     │
┌──────────────┴─────────── native ───┐ assets: manifest, GGUF reader, verified│
│ libmx_ai.so  llama.cpp + whisper.cpp│   resumable atomic installs            │
│              (one ggml, CPU)        │ speech: VAD segmenter, overlap merge   │
│ libmx_ocr.so Tesseract + Leptonica  │ translation: prompts, safe splitting   │
│ libmx_tts.so Piper + espeak-ng (GPL,│ resources: thermal budget, eviction    │
│              optional)              └────────────────────────────────────────┘
└─────────────────────────────────────┘
```

`core` and `engines` contain no Android APIs, so the protocol, crypto, installer and native bindings are
tested on a JVM (`./gradlew :core:test :engines:test`).

## Engines

### Translation: Hy-MT2-1.8B on llama.cpp
- Runtime: mainline llama.cpp at `f2cc7282`, CPU backend, `mmap` loading, context 4096, batch 512,
  threads from the thermal budget (4 on 8-core phones, fewer when hot or in battery saver).
- Chat formatting uses the model's own template from GGUF metadata via llama.cpp's Jinja engine
  (`common_chat_templates_apply`, thinking disabled); falls back to the built-in template table.
- Prompts follow the model authors' documented templates (English, or Chinese when Chinese is involved).
- Decoding uses the published recommendation: temperature 0.7, top-p 0.6, top-k 20, repetition
  penalty 1.05. Diagnostics can also run greedy decoding.
- Long input is split only at paragraph → sentence (incl. Arabic `؟ ، ؛ ۔` and CJK punctuation) → clause
  → whitespace boundaries, max 1,000 characters per piece; separators are restored verbatim.
- One request at a time: a new request cancels the running one (between tokens, and during prefill
  through the llama.cpp abort callback). There are no interleaved results.
- Before the native load the GGUF header is read in Kotlin and the architecture is compared with the
  manifest (`expected_architecture`); the wrong file is rejected without touching native code.

#### Model compatibility matrix

| Candidate | Format | Runtime | Status |
|-----------|--------|---------|--------|
| `tencent/Hy-MT2-1.8B` | BF16 safetensors | transformers / vLLM / SGLang | Server-class only. Not for phones. |
| `tencent/Hy-MT2-1.8B-FP8` | FP8 safetensors | vLLM / SGLang | Not supported by llama.cpp CPU. |
| `tencent/Hy-MT2-1.8B-GGUF` (Q4_K_M or similar standard k-quants) | GGUF | llama.cpp mainline: `hunyuan-dense` architecture, tokenizer pre-type and chat template present at `f2cc7282` | **Primary candidate, ~1.1–1.2 GB expected for Q4_K_M.** The exact filename, size and hash are not verified (Hugging Face was unreachable from the build environment). Verify with `tools/check_gguf.py`, then pin with `tools/pin_assets.py`. |
| `tencent/Hy-MT2-1.8B-2bit-GGUF` | GGUF, vendor low-bit | Upstream README: "depends on our STQ kernel" (llama.cpp PR #22836, not merged) | Excluded until the STQ types are in mainline. `check_gguf.py` reports unknown tensor types. |
| `tencent/Hy-MT2-1.8B-1.25bit-GGUF` (~440 MB) | GGUF, AngelSlim 1.25-bit | Requires STQ kernel | Excluded for now (same reason). The most attractive mobile option once supported. |

The upstream README places its STQ warning above the generic llama.cpp instructions. It is not
certain whether it applies to the standard GGUF too. `check_gguf.py` answers this from the actual
file: any tensor type unknown to the pinned ggml makes it fail. A pipeline fixture with the same
architecture (random weights) passes load → template → generation → cancellation through JNI on the
host (`engines` tests).

### Speech recognition: whisper.cpp
- Compiled from source against **llama.cpp's ggml** (same 0.26 line), so the app carries one ggml.
- Default pack: multilingual `ggml-base.bin` (~142 MB), with an optional `ggml-small-q5_1.bin` for
  higher accuracy. Both are unpinned until verified.
- Phrase-level pipeline: 30 ms frames → energy VAD with adaptive noise floor (start after 90 ms of
  voice, end after 700 ms of silence, utterances bounded to 25 s) → final transcription of each
  utterance exactly once. Partial previews re-decode the growing utterance (last 10 s) only when the
  engine is idle, and replace each other rather than being appended. Long speech that is force-split
  is merged by removing duplicated boundary words. Non-speech tokens and high no-speech probability
  segments are dropped.
- Language is forced to the selected source language. "Detect" uses Whisper's language ID only in
  the single-phone voice translator; conversations always use each speaker's chosen language.
- This is phrase-level translation, not simultaneous word-by-word translation.

### Text recognition: Tesseract 5.5.3 (LSTM) + Leptonica
- Arabic and English `tessdata_fast` data are bundled (verified at first start); 36 more languages are
  downloadable packs pinned to `tessdata_fast` at commit `87416418`.
- The OCR pipeline produces three candidates and keeps the highest confidence-weighted result:
  automatic layout at normalised scale (≤ 2000 px), automatic layout at half scale, and
  projection-based line bands recognised in single-line mode. Why: a test showed Tesseract's layout
  analysis dropping an entire Arabic line at 1.0× that it read perfectly in single-line mode
  (see `docs/BENCHMARKS.md`). Invisible bidi marks are removed; boxes are mapped back to the image.
- Leptonica is built without image codecs (the app passes decoded pixels), and Tesseract without
  curl, archive or TIFF. The ScrollView debug viewer is compiled out, so `libmx_ocr.so` imports no
  socket functions (checked with `nm`).
- Still capture or gallery import, not continuous frame OCR. The recognised text is shown and
  translated. Translated text is **not** overlaid on the image.

### Speech output
1. Piper voice packs (GPL build only: `-Pmodulex.withPiper=true`) with espeak-ng phoneme data as a
   separate verified pack.
2. Otherwise Android `TextToSpeech`, restricted to voices with `isNetworkConnectionRequired == false`
   and without the network-synthesis or not-installed features.
3. Otherwise the app says that spoken output is unavailable for that language. It never silently
   uses an online voice.

## Conversation transports

| Mode | Discovery | Data path | Internet needed | Security |
|------|-----------|-----------|-----------------|----------|
| Same Wi-Fi / hotspot (LAN) | QR (IP:port) | TCP | No | TLS 1.3, host cert pinned from QR, client cert + HMAC join proof |
| Wi-Fi Direct | QR (group name + passphrase) | Autonomous P2P group, TCP to the group owner | No | Same as LAN |
| Bluetooth LE | QR, then a BLE advertisement with an 8-byte session tag + L2CAP PSM | LE L2CAP connection-oriented channel (no system pairing) | No | Same TLS + proofs on top of L2CAP |
| Internet | QR or 10-character pairing code | WebRTC data channel (ordered, reliable) | Yes | DTLS + HMAC proofs bound to both DTLS fingerprints; relay sees only SDP/ICE |

- Bluetooth is a real data transport here, not only discovery. Framing is a 4-byte length prefix with
  a 64 KiB maximum frame. Reconnection rescans and reconnects.
- Wi-Fi Direct / hotspot / LAN do not imply internet access: they only need the two phones to share a
  link. Bluetooth and Wi-Fi Direct have short range; long distance needs Internet mode.
- Reconnection: the guest retries with exponential backoff (0.5 s to 10 s); the host keeps listening.
  Unacknowledged messages are replayed in order and duplicates are dropped by sequence number.
- The speaking phone translates into the listener's language, so the receiver only displays (and
  speaks) the result. If languages changed mid-flight and the original was shared, the receiver
  re-translates locally.
- Audio is never sent between phones. The microphone is paused while a received translation is spoken.

See [PROTOCOL.md](PROTOCOL.md) and [SECURITY.md](SECURITY.md).

## Resource management
- Engines load on first use; heavy engines are never all loaded up front.
- Before loading, `ResourcePolicy.evictionsFor` frees other engines when available memory minus a
  reserve (10% of RAM, minimum 512 MB) is insufficient. Translation is kept resident longest.
- `onTrimMemory` releases OCR/speech, and translation too unless "keep in memory" is on.
- One native context per engine (mutex); at most two heavy jobs run at once across engines.
- Thermal: MODERATE reduces threads; SEVERE halves threads and disables hands-free listening;
  CRITICAL and above refuses new inference with a user message.
- Packs are protected from deletion while loaded. Installs are atomic: the record is written last and
  renamed into place.
- Audio buffers are bounded (64 frames, drop-oldest; utterances ≤ 25 s; partial decode ≤ 10 s).
  Camera images are capped at 2400 px and released on retake.
