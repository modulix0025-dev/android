# Prompt for the next session: what is still open after the dialect-corpus session (2026-10-24)

Copy everything below the line into a new Claude Code session with access to `modulix0025-dev/android`.
For the parts that need your Hugging Face account, use `docs/LOCAL_HF_SOURCES_PROMPT.md` on your own
computer instead (CI must never get your token).

---

You are continuing work on **ModuleX Translator** in `modulix0025-dev/android`, directory
`modulex-translator/`, branch `claude/vibrant-cori-drj24g` (commit and push there; no pull request unless
I ask). Talk to me in Egyptian Arabic only. Write code, commit messages and docs in English.

Read first: `docs/NEXT_SESSION_PROMPT_DIALECT_CORPUS.md` (the original goal and rules),
`docs/BENCHMARKS.md`, `docs/LICENSING.md`, `tools/tm_sources_approved.json`, and the git log of this
branch since 2026-10-24. The same ground rules apply: no fake data, counts or measurements; correctness
over volume; only sources whose licence allows redistribution in a commercial app, each licence read from
the source itself; keep CI green; ask me only for decisions that are mine.

## State at the end of the last session (verify before relying on it)

- Dialect corpora in `app/src/main/assets/tm/` (built by CI job `tm-build` from Tatoeba, Wiktionary,
  OLDI Seed and Google SMOL): about 22,800 shipped pairs plus a 10% held-out split in
  `engines/src/test/resources/tm-heldout/`. Egyptian is the only dialect above 10,000 verified pairs.
- Glossaries: curated `ar-en.tsv`/`en-ar.tsv`, Wiktionary dialect terms `ar-en.wiktionary.tsv`,
  Islamic expressions `ar-<lang>.islamic.tsv` (no Mandarin: Wiktionary mixes scripts there).
- Translation uses an exact match from the corpora, otherwise up to 3 retrieved references plus glossary
  hints (`LocalTranslationEngine`, `TranslationMemory`).
- Voice screen: finishing keeps and translates what was said, saves the session WAV, has a
  stop-generation button. Text screen: stop-generation button.
- Camera OCR: illumination flattening, inversion, noise-line filter, quality capture mode.

## What is still open

1. **Measure, then keep or remove.** Read the `TMSUMMARY` lines of the latest `mt-eval` run
   (plain / glossary / glossary + retrieval per dialect, chrF and keyword recall). If the run is missing,
   push a commit with `[mt-eval]`. Record the numbers in `docs/BENCHMARKS.md`. Where retrieval or the
   Wiktionary/Islamic glossaries make held-out results worse, turn that part off for that language or
   dialect (with a test) and say so. A feature that does not improve held-out results is not shipped.
2. **10,000 verified pairs per dialect is reached only for Egyptian.** Keep searching for openly
   licensed, human-made dialect data (run `tools/tm_discover.py` via `[tm-discover]` again; new datasets
   appear on Hugging Face every month). Pending checks: `lahga/arabic-dialects` and
   `jonathanmutal/Medical-Questionnaire-Multilingual-Translation` (samples are printed by `[tm-sources]`;
   approve only if their rows' provenance is clear). Also ask me whether to contact the DODa authors for
   a commercial licence (Moroccan, ~150,000 entries) and whether to get a legal opinion on
   ArzEn-MultiGenre (copyrighted songs/novels/subtitles). Never generate pairs to fill the gap.
3. **Other languages' regional varieties** (en-US/en-GB, es-MX/es-ES/es-AR, pt-BR/pt-PT, fr-FR/fr-CA):
   no openly licensed sentence pairs were found. Wiktionary has regional terms with translations: build
   `<lang>-ar` / `<lang>-en` glossaries from single-meaning regional entries (same rules as
   `ar-en.wiktionary.tsv`), with regression tests for ambiguous words, and measure them.
4. **Islamic expressions for Mandarin and Traditional Chinese**: only add them with a reliable way to
   tell simplified from traditional forms (e.g. Wiktionary's own zh entries, which give both).
5. **OCR beyond preprocessing**: compare `tessdata_best` (Apache-2.0, larger, slower) with the bundled
   `tessdata_fast` on the OCR tests and on real phone photos (`-Pmodulex.tessdata=/dir`); if it is clearly
   better, offer it as a downloadable pack pinned in `asset_manifest.json`. Add a perspective/deskew step
   for photos taken at an angle, with tests.
6. **Voice on a real phone**: the graceful finish, the fallback transcription and the saved recording
   are tested only through the build and the emulator. Ask me to try it on my phone and report; fix what
   I find.
7. **FLORES+ evaluation**: done only on my computer with `docs/LOCAL_HF_SOURCES_PROMPT.md`; add its
   numbers to `docs/BENCHMARKS.md` when I send them.
8. **Ship**: when everything is green, push with `[mt-eval] [release-apk] [screenshots]`, check every job
   yourself, and give me the direct download link only after checking that the URL answers.
