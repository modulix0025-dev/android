# Prompt for a local session: use the gated Hugging Face dialect data with your own account

Run this on your own computer with Claude Code, in a clone of `modulix0025-dev/android`. CI has no Hugging
Face credentials and must never get yours.

Before you start (only you can do these, in your browser):
1. Sign in at https://huggingface.co and create a **read** token (Settings → Access Tokens).
2. Open https://huggingface.co/datasets/openlanguagedata/flores_plus and accept the access conditions,
   only if you agree to them. Read in CI on 2026-10-24, they are:
   - "I agree not to re-host FLORES+ in places where it could be picked up by web crawlers"
   - "If I evaluate using FLORES+, I will ensure that its contents are not in the training data"
3. In the terminal where you start Claude Code, set the token as an environment variable. Do not paste it
   into the chat:
   - Linux/macOS: `export HF_TOKEN=hf_...`
   - Windows PowerShell: `$env:HF_TOKEN = "hf_..."`

What this gives you: an honest measurement, on about 2,000 professionally translated sentences per
dialect, of whether the app's dialect corpora and glossaries help. FLORES+ does **not** go into the app:
a public APK or a public GitHub repository would re-host it where crawlers can reach it, which the gate
forbids.

---

You are working on **ModuleX Translator** in `modulix0025-dev/android`, directory `modulex-translator/`,
branch `claude/vibrant-cori-drj24g` (`git fetch origin && git checkout claude/vibrant-cori-drj24g && git pull`).
Talk to me in Egyptian Arabic. Write code, commit messages and docs in English.

## Goal

Measure the dialect features on FLORES+ (`openlanguagedata/flores_plus`) with my Hugging Face account:
dialect → English for arz_Arab (ar-EG), apc_Arab (ar-LB), ajp_Arab (ar-PS), acm_Arab (ar-IQ),
ary_Arab (ar-MA), aeb_Arab (ar-TN), ars_Arab (ar-SA-najd), acq_Arab (ar-YE), each three ways: plain,
glossary only, glossary + retrieval from the shipped corpora. Record the numbers in `docs/BENCHMARKS.md`.

## Hard rules

- **Never print, log, commit or write `HF_TOKEN` anywhere.** Read it only from the environment. Do not
  put it in a file, a git config, a URL or a command line that ends up in shell history. If it is not set,
  stop and tell me how to set it (see above). Check it with
  `python3 -c "from huggingface_hub import HfApi; print(HfApi().whoami()['name'])"` (prints only the user name).
- **FLORES+ data is never committed, pushed, uploaded or shipped.** It stays in `data/tm-raw/flores/`,
  which is in `.gitignore`. Before every commit, run `git status` and confirm nothing from
  `data/tm-raw/flores/` and no FLORES+ sentence (in any file, log or doc) is staged. Benchmark tables may
  contain scores and counts, never sentences.
- FLORES+ is never used as a reference or a glossary source: it is evaluation-only, so the measurement
  stays honest and the gate's "not in training data" condition is kept.
- Before downloading, print the dataset card's current licence and gate fields (`tools/tm_sources.py`,
  function `flores`, prints `license` and every `extra_gated_*` field). If they differ from the two
  conditions quoted above, stop and show me the new text before going on.
- Never disable TLS checks or route around a proxy. No fake results: report what the tools print.

## Steps

1. Read `tools/tm_sources.py`, `tools/tm_build.py`, `tools/tm_sources_approved.json`,
   `engines/src/test/kotlin/com/modulex/translator/engines/DialectHeldOutExperiment.kt`, `docs/BENCHMARKS.md`
   and `docs/LICENSING.md`.
2. Install the tools: `python3 -m pip install --user "huggingface_hub>=0.25" pyarrow`. You need a JDK 17+,
   CMake, Ninja and a C++ compiler for the native engine (see `docs/BUILD.md`; `tools/fetch-native-deps.sh`
   fetches the pinned sources).
3. Print the licence and gate fields (rule above). Then fetch:
   `python3 tools/tm_sources.py --out data/tm-raw --only flores` and check the counts (expect about
   2,000 rows per dialect, each with MSA and English).
4. Get the shipped model: `python3 tools/ci_fetch_gguf.py --file Hy-MT2-1.8B-Q4_K_M.gguf --dest <dir>`
   (it verifies the SHA-256; it prints the local path).
5. Run the experiment (about 40 sentences per dialect and configuration by default; raise
   `-Pmodulex.tmEvalPerDialect` if time allows):
   ```
   ./gradlew :engines:test --console=plain --tests '*DialectHeldOutExperiment*' \
     -Pmodulex.mtEvalModels=<path to the .gguf> -Pmodulex.tmEvalExtra=$(pwd)/data/tm-raw/flores
   ```
   It prints `TMSUMMARY` lines per dialect (`flores:<dialect>` for FLORES+, `<dialect>` for the
   shipped corpora's own held-out split): chrF, keyword recall and how many items got references.
6. Add a section "Dialect corpora on FLORES+ (measured locally)" to `docs/BENCHMARKS.md`: the date, the
   model file and its SHA-256, the FLORES+ revision printed by the tool, the number of sentences per
   dialect, and the three configurations' scores per dialect. Say which configuration is best per dialect
   and whether retrieval helps. Scores only, no sentences.
7. If retrieval does not improve the held-out results for a dialect, say so plainly. Then propose (do not
   apply without asking me) turning retrieval off for that dialect in `LocalTranslationEngine`; the rule
   is that a feature that does not improve held-out results is not shipped.
8. Commit only `docs/BENCHMARKS.md` (and code fixes, if any were needed), after checking `git status` as
   the rules say, and push to `claude/vibrant-cori-drj24g`. Check the CI run of your push and tell me
   which jobs are green.
9. At the end, tell me in Egyptian Arabic: the scores per dialect, what helps and what does not, and
   what you would change.

## Other gated or token-only sources

If `tools/tm_sources_approved.json` later lists another source that needs a Hugging Face token, apply
the same procedure: print its licence and gate text, show it to me verbatim, and ask me before using
it. Ship it (fetch into `data/tm-raw/<source>/`, mark it approved with the evidence, build with
`tools/tm_build.py --raw <ci-style fetch> --raw data/tm-raw`, update `assets/licenses/` and
`docs/LICENSING.md`, run `./gradlew :core:test`, commit) only if its terms allow redistribution inside a
commercial app. Otherwise use it for local evaluation only, like FLORES+.
