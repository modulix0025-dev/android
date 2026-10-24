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

### Hard terms: quantisation, decoding, prompt and glossary (CI `mt-eval`, run 40081462965)
36 hard items (Arabic currency, religious, food and Egyptian colloquial terms; 6 en->ar), scored by
required English/Arabic keywords; every output is printed in the job log.

| Configuration | Q4_K_M | Q6_K | Q8_0 |
|---|---|---|---|
| plain prompt (greedy or sampled, English or Chinese instruction) | 20-21/36 | 21/36 | 21-22/36 |
| with terminology hints | 30/36 | 29-30/36 | 30/36 |
| mean time per item (4-core runner) | ~1.0 s | ~0.7 s | ~1.5 s |

Conclusion: the failures reported by users (terms copied or transliterated: «جنيه» -> "Gneih",
«الأسانسير» -> "Asansir", «الكشري» -> "Al-Kashri") are knowledge gaps of the 1.8B model, not an
artefact of quantisation, sampling or prompt language. Terminology hints in the authors' documented
format fix them; the app now sends hints from a curated glossary (assets/glossary). Q4_K_M stays.

### Dialect corpora (`assets/tm`, built by CI job `tm-build`, run 40090911200)

Verified pairs per dialect after the corpus rules (>= 5 words, script, no exact or 3-gram-Jaccard
>= 0.8 near duplicates, MSA-looking rows rejected); 10% held out for evaluation and never shipped.
Sources and licences: `docs/LICENSING.md`. The Wiktionary dialect glossary (`glossary/ar-en.wiktionary.tsv`)
has 2,792 single-meaning terms.

| Dialect | Shipped pairs | Held out | By source | With MSA side | Bytes |
|---|---:|---:|---|---:|---:|
| ar-AE | 94 | 12 | tatoeba 14, kaikki 80 | 1 | 25,564 |
| ar-DZ | 623 | 67 | tatoeba 623 | 8 | 140,995 |
| ar-EG | 9826 | 1055 | oldi_seed 5498, smol 3938, tatoeba 350, kaikki 40 | 5 | 3,964,057 |
| ar-IQ | 3 | 1 | tatoeba 3 | 2 | 864 |
| ar-LB | 138 | 12 | tatoeba 12, kaikki 126 | 2 | 37,442 |
| ar-LY | 2029 | 221 | smol 2029 | 0 | 634,324 |
| ar-MA | 5706 | 630 | oldi_seed 5538, tatoeba 10, kaikki 158 | 0 | 2,636,885 |
| ar-PS | 245 | 43 | tatoeba 46, kaikki 199 | 2 | 66,785 |
| ar-SA | 28 | 1 | kaikki 28 | 0 | 7,096 |
| ar-SD | 2012 | 212 | smol 2012 | 0 | 635,563 |
| ar-TN | 2119 | 247 | smol 2068, kaikki 51 | 0 | 682,744 |
| **total** | **22823** | **2501** | | | **8,832,319** |

Not reached: 10,000 shipped pairs per dialect. Egyptian has 10,881 verified pairs in total (9,826 shipped
+ 1,055 held out); every other dialect is below that because no more openly licensed, human-made data was
found (see the excluded sources in `docs/LICENSING.md`). No pairs were generated to fill the gap.
English, Spanish, Portuguese and French regional varieties have no openly licensed sentence pairs in
these sources (Tatoeba does not tag them; Wiktionary has terms only).

Held-out translation results (plain / glossary / glossary + retrieval): run `mt-eval`, recorded below
once measured.

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
