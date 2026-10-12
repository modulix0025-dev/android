# Benchmarks

Only measured numbers are reported. Each row names the hardware it was measured on.
**No measurement on an Android device has been made yet** (no device was attached to the build
environment). Use `DEVICE_VALIDATION.md` to fill in the device rows.

## Host measurements (2026-10-12)
Hardware: cloud container, Intel Xeon @ 2.80 GHz, 4 vCPU, 15 GB RAM, Linux x86_64, OpenJDK 21.
These measure software overhead and correctness, not phone performance.

### Conversation protocol over loopback TCP (`LoopbackLatencyTest`)
Full stack: TLS 1.3 handshake with certificate pinning, HMAC-authenticated join, then 200 sequential
utterances with acknowledgements.

| Metric | Run 1 | Run 2 |
|---|---|---|
| Connect + TLS 1.3 + mutual authentication | 29.1 ms | 36.8 ms |
| Delivery latency, median | 0.95 ms | 1.06 ms |
| Delivery latency, P95 | 3.25 ms | 5.03 ms |
| Delivery latency, max | 5.80 ms | 19.35 ms |

Two runs on a shared 4-vCPU container: the tail varies with host load.
This is the lower bound added by the protocol and crypto. Wi-Fi/Bluetooth radio latency comes on top
and must be measured on devices.

### Text recognition through JNI (`OcrEngineTest`, bundled tessdata_fast)
Synthetic images rendered with DejaVu Sans 44 px (HarfBuzz shaping), 1400 px wide.
CER = character error rate ignoring whitespace.

| Test | Raw Tesseract (auto layout) | ModuleX OCR pipeline | Time (pipeline) |
|---|---|---|---|
| English, 2 lines | CER 0.000, conf 96.4 | CER 0.000 | ~70–90 ms |
| Arabic, 2 lines | **CER 0.660** (second line dropped entirely) | **CER 0.000** (half-scale candidate won) | ~130–175 ms (3 passes) |
| Mixed Arabic/English signage, 4 lines | — | CER 0.261: both English lines and the first Arabic line read, but **"صيدلية مفتوحة" not recognised** in any mode | ~260–400 ms |

Findings:
1. Tesseract's layout analysis dropped an entire large Arabic line (reproduced in the C API with PSM 3,
   4 and 6). Single-line mode or half scale read it perfectly. The pipeline's multi-candidate strategy
   fixes this case.
2. The fast Arabic model returns nothing for the line "صيدلية مفتوحة" in DejaVu Sans at any scale or
   mode. This is a data/model limitation. Candidate remedies: `tessdata_best` Arabic (larger, slower), or
   a different OCR engine for Arabic. Needs evaluation on real photos.
3. Arabic output sometimes merges two words (missing space: "فيالمطعم"). The translation model usually
   copes, but it is visible in the recognised text.

### Translation pipeline (`TranslationEngineTest`, random-weight fixture)
Verifies the native path only (load, Jinja chat template, tokenise, prefill, decode, streaming,
cancellation, context-overflow error). **No translation quality or speed figures exist yet: the real
Hy-MT2 GGUF could not be downloaded in the build environment.**

## Device measurements: not yet available
| Measurement | Target (planning) | Result |
|---|---|---|
| Cold model load (translation) | — | not measured |
| Short-text translation, warm | < 1 s | not measured |
| Long text (paragraph) | — | not measured |
| Peak RSS/PSS, text / speech / combined | 2–4 / 3–6 / 4–7 GB | not measured |
| Phrase end → translated text | < 2 s | not measured |
| Translation → spoken output start | — | not measured |
| Transport latency LAN / Wi-Fi Direct / BLE / Internet | < 300 ms | not measured (loopback lower bound above) |
| Reconnection time | — | not measured |
| 15–30 min battery/thermal per scenario | — | not measured |
| Translation quality (corpus.tsv, human review + chrF) | — | not measured |
