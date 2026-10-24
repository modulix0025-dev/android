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
| English, 2 lines | CER 0.000, conf 96.4 | CER 0.000 | ~70 ms |
| Arabic, 2 lines | **CER 0.660** (second line dropped) | **CER 0.000, words exact** | ~250 ms |
| Mixed Arabic/English signage, 4 lines | — | **CER 0.043, 4/4 lines** (one stray diacritic, one trailing ".") | ~500 ms |

How the gaps were closed (each step measured with the C API and the JNI tests):
1. Tesseract's layout analysis dropped an entire large Arabic line (PSM 3, 4 and 6). Single-line mode or
   a smaller scale read it. Hence the multi-candidate pipeline.
2. The line "صيدلية مفتوحة" was unreadable at 25–45 px glyph height in every mode, with both
   `tessdata_fast` and `tessdata_best` and in two typefaces (DejaVu Sans, FreeSerif). The **same fast
   model read it perfectly at ~11–16 px**. The pipeline therefore recognises every detected line at
   several normalised glyph heights (12/18/26 px) and keeps the best reading. Mixed page: 3/4 → 4/4
   lines, CER 0.261 → 0.043.
3. `preserve_interword_spaces=1` removed spaces between Arabic words ("فيالمطعم"). Removing that
   setting restored correct word boundaries.
4. `tessdata_best` Arabic (12.6 MB vs 1.4 MB, commit `e12c65a9`) brought no further gain once (1)–(3)
   were in place and was ~3× slower, so the app keeps `tessdata_fast`. Re-evaluate on real photos:
   `./gradlew :engines:test -Pmodulex.tessdata=<dir>`.

These are synthetic renders; real camera photos (blur, perspective, glare) still need device evaluation.

### Translation pipeline (`TranslationEngineTest`, random-weight fixture)
Verifies the native path only (load, Jinja chat template, tokenise, prefill, decode, streaming,
cancellation, context-overflow error).

### Real Hy-MT2 and Whisper models (GitHub Actions `ubuntu-24.04` x86_64 runner, 4 threads)
Measured in CI run 40074325034 with the pinned files (hashes verified against the hub):
`Hy-MT2-1.8B-Q4_K_M.gguf` (1,133,080,448 bytes) and Whisper `ggml-base.bin` (147,951,465 bytes).
These are **server CPU** figures, not phone figures.

| Measurement | Result |
|---|---|
| Translation model load (mmap) | 930 ms |
| Corpus (`corpus.tsv`, 16 sentences, greedy) latency | median 1,644 ms, P95 5,340 ms (longest: 3-sentence paragraph, 83 tokens) |
| chrF vs. reference (automatic, single reference) | ar→en 87.5, en→ar 71.3 |
| Whisper base load | 88 ms |
| Whisper base, 11 s English sample (`jfk.wav`) | 1,188 ms, transcript exact; language auto-detected as `en` (p = 0.96) |

Observations from the corpus output (human review still required): meaning was preserved in all
16 sentences. Lower chrF in en→ar mostly comes from valid wording differences (e.g. «المنصة» vs
«الرصيف», Western vs Arabic-Indic digits) rather than errors. One grammatical slip in ar→en:
"a engineer". Egyptian colloquial input («انا عايز اروح المطار بسرعه») was translated correctly.

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
| Translation quality on device | — | same model and decoding as above; device timing not measured |
