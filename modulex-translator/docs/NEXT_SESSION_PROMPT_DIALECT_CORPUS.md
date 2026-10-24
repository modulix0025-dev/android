# Prompt for the next session: dialect and slang corpora for ModuleX Translator

Copy everything below the line into a new Claude Code session that has access to the repository
`modulix0025-dev/android`.

---

You are continuing work on **ModuleX Translator**, an offline Android translator in
`modulix0025-dev/android`, directory `modulex-translator/`. Work on branch
`claude/funny-lamport-qsdrj4` (create it from the latest default branch only if it does not exist);
commit and push there. Do not open a pull request unless I ask.

## Talk to me in Egyptian Arabic only. Write code, commit messages and docs in English.

## Current state (read before changing anything)

- Translation runs **fully offline** with `tencent/Hy-MT2-1.8B-GGUF` (Q4_K_M, pinned in
  `app/src/main/assets/asset_manifest.json`) through llama.cpp (`native/`, `engines/`).
  My phone cannot run the 7B model; stay on 1.8B.
- `core/.../translation/Glossary.kt` matches terms in the source text (Arabic-aware: diacritics,
  alef/yaa variants, clitic prefixes و ف ب ك ل ال, common suffixes; never inside another word) and
  `PromptBuilder` sends them to the model in the authors' documented terminology form
  ("Reference the following translations: X translates to Y ..."). Lists live in
  `app/src/main/assets/glossary/<from>-<to>.tsv` (`ar-en.tsv`, `en-ar.tsv`).
- Measured on CI (job `mt-eval`, `engines/.../TranslationQualityExperiment.kt`, results in
  `docs/BENCHMARKS.md`): quantisation, sampling and instruction language barely matter (20-22/36
  hard items); terminology hints raise it to 29-30/36. The model's gaps are vocabulary, especially
  dialect and culture terms. That is what this task fixes at scale.
- CI (`.github/workflows/modulex-translator.yml`) builds and tests everything. Commit-message tags:
  `[mt-eval]` runs the quality experiment, `[release-apk]` publishes the full APK (all models inside)
  to `https://github.com/modulix0025-dev/android/releases/download/modulex-translator-debug/ModuleX-Translator-debug.apk`,
  `[screenshots]` commits emulator screenshots. This container cannot download from Hugging Face or
  GitHub artifacts; CI can. Never disable TLS checks or route around the network policy.

## Goal

For **every language the app supports** (`core/.../language/Language.kt`, LanguageRegistry), build a
dialect and slang corpus, plus a list of unusual terms (idioms, culture, religion, food, money,
administration, internet slang), and use them in translation so dialect input is understood and
culture terms are rendered correctly instead of being copied or transliterated.

### Hard requirements per dialect

1. **At least 10,000 sentence pairs per dialect.** Each source sentence has **at least 5 words**
   (count whitespace-separated tokens after normalisation; punctuation-only tokens do not count).
2. **No duplicates, ever:** no two source sentences may be equal after normalisation (case-fold,
   Unicode NFKC, strip diacritics/tatweel, unify alef/yaa/hamza forms for Arabic, collapse
   whitespace and punctuation). Also reject near-duplicates: word-3-gram Jaccard similarity >= 0.8
   with any existing sentence in the same dialect. Enforce this with a test, not by eye.
3. Every pair has: `id`, `dialect` (BCP-47 style, e.g. `ar-EG`, `ar-SA`, `ar-LB`, `ar-MA`, `ar-IQ`,
   `ar-SD`, `en-US`, `en-GB`, `es-MX`, `es-ES`, `es-AR`, `pt-BR`, `pt-PT`, `fr-FR`, `fr-CA`, ...),
   `source` (the dialect sentence), `standard` (same meaning in the standard language: MSA for
   Arabic), `en` (English translation; for English dialects: Arabic), `terms` (the slang/culture
   terms the sentence exercises, `;`-separated), `domain`, `provenance`, `license`.
4. **Correctness over volume.** A wrong pair is worse than a missing one. Never invent slang. A
   generated pair only counts after automatic checks pass (below). If 10,000 verified pairs cannot be
   reached honestly for a dialect, stop, report the real number and the reason, and propose
   how to close the gap. Do not pad, paraphrase-spam or template-fill to hit the count: template
   variants ("I bought X", "I bought Y") count as near-duplicates and are rejected by rule 2.
5. **Licensing.** Use only sources whose license allows redistribution inside a commercial app
   (e.g. CC0, CC-BY, CC-BY-SA with attribution recorded, Apache-2.0, MIT). Check each source's
   license text yourself and record it per pair. Research-only or non-commercial corpora (many dialect
   corpora such as MADAR/NADI) are not allowed; list them as excluded with the reason.
   Add every source to `docs/LICENSING.md` and `app/src/main/assets/licenses/`.

### Unusual-terms lists

For each language pair the app translates most (start with `ar<->en`), extend the glossary TSVs with
unambiguous idioms and culture terms. Rules already used in this repo apply: a word with several
everyday meanings does not go in (`وقف` stop/endowment, `قرش` piastre/shark, `مية` 100/water,
`عيش` bread/living, `أذان/آذان`). Add a regression test for every ambiguous word you exclude.

## How to build it (pipeline, in this order)

1. **Inventory:** list supported languages and the dialects you will cover, with an estimate of
   available openly-licensed data per dialect. Show me the table before generating anything large.
2. **Collect openly-licensed data first** (in CI, since this container cannot reach most hosts):
   e.g. Tatoeba (CC-BY 2.0 FR, has `arz`, `apc`, `ary`, ...), Wiktionary/Wikidata lexemes
   (CC-BY-SA / CC0) for slang terms and idioms, other corpora only after checking their license.
3. **Generate to fill gaps only where needed**, in batches by domain (daily life, shopping, transport,
   food, family, work, health, religion and holidays, money, phone/internet, emotions, jokes and
   idioms), each sentence built around listed dialect terms. Generation happens in a CI job or a
   script you commit; never paste unverifiable bulk data from memory.
4. **Automatic verification for every pair** (a script under `tools/`, run in CI):
   - schema, length (>= 5 words), language/script identification of each field;
   - uniqueness and near-duplicate rules (normalised hash + 3-gram Jaccard);
   - every listed `term` actually occurs in `source` (use the same matcher as `Glossary.kt`);
   - round-trip agreement: translate `source` -> English with the on-device model plus the new
     references and compare with `en` (chrF), and `standard` <-> `source` consistency; flag low
     scores for removal or review instead of keeping them;
   - a random sample of 200 pairs per dialect printed in the CI log for my manual review.
5. **Storage format:** `app/src/main/assets/tm/<dialect>.tsv` (UTF-8, header line, one pair per
   line), plus `tm/manifest.json` with counts, sources, licenses and SHA-256 of each file. Keep the
   APK size impact visible (report bytes per dialect).
6. **Use it in translation** (core + engines, with unit tests):
   - **Exact match:** if the normalised input sentence equals a verified `source`, return the stored
     translation for that target (or use it as the only reference).
   - **Retrieval:** otherwise retrieve the top 3-5 most similar pairs (character n-gram TF-IDF or
     MinHash over the normalised text; must run on the phone in < 30 ms for 10k pairs) and send them
     as references in the model's documented form, together with the glossary hints. Keep the
     prompt within the context budget (measure tokens; cap references).
   - Detect the dialect (cheap lexical classifier from the corpus terms) only to pick which corpus
     to search; fall back to all corpora of that language.
7. **Measure honestly:** split each corpus 90/10 before any prompt engineering; the 10% held-out
   part is never used as a reference. Extend `mt-eval` to report, per dialect, chrF and keyword
   accuracy for: plain, glossary only, glossary + retrieval. Record numbers in
   `docs/BENCHMARKS.md`. A feature that does not improve held-out results is not shipped.
8. **Ship:** commit with `[mt-eval] [release-apk] [screenshots]`, check the CI results yourself,
   fix failures, and give me the direct download link once the release step has actually published
   the APK (verify the URL answers).

## Ground rules

- No fake results, counts or measurements. Report what was verified and what was not.
- Keep the app offline at runtime: corpora ship inside the APK; nothing is fetched at runtime
  except packs the user explicitly downloads.
- Match the existing code style; add tests next to the existing ones; keep CI green.
- Ask me only for decisions that are truly mine (e.g. which dialects first if time runs short,
  any source whose license is unclear). Start with `ar-EG` (Egyptian), then the other Arabic
  dialects, then the rest.
