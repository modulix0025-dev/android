# Prompt for a local session: fetch the gated Hugging Face dialect sources with your own account

Run this on your own computer with Claude Code, in a clone of `modulix0025-dev/android`, because the
CI has no Hugging Face credentials and must never get yours. Copy everything below the line.

Before you start (only you can do these, in your browser):
1. Sign in at https://huggingface.co and create a **read** token (Settings → Access Tokens).
2. Open https://huggingface.co/datasets/openlanguagedata/flores_plus, read the access conditions,
   and accept them only if you agree to them.
3. In the terminal where you start Claude Code, set the token as an environment variable. Do not paste it
   into the chat:
   - Linux/macOS: `export HF_TOKEN=hf_...`
   - Windows PowerShell: `$env:HF_TOKEN = "hf_..."`

---

You are working on **ModuleX Translator** in `modulix0025-dev/android`, directory `modulex-translator/`,
branch `claude/vibrant-cori-drj24g` (`git fetch origin && git checkout claude/vibrant-cori-drj24g && git pull`).
Talk to me in Egyptian Arabic. Write code, commit messages and docs in English.

## Goal

Add the gated Hugging Face dialect sources to the app's dialect corpora, using my token from the
`HF_TOKEN` environment variable. Today that is **FLORES+** (`openlanguagedata/flores_plus`): about 2,000
professionally translated sentences per dialect (arz_Arab, apc_Arab, ajp_Arab, acm_Arab, ary_Arab,
aeb_Arab, ars_Arab, acq_Arab), aligned with MSA (arb_Arab) and English (eng_Latn). Also handle any
other source that `tools/tm_sources_approved.json` marks as needing a Hugging Face token.

## Hard rules

- **Never print, log, commit or write `HF_TOKEN` anywhere.** Read it only from the environment. Do not
  put it in a file, a git config, a URL or a command line that ends up in shell history. If it is not
  set, stop and tell me how to set it (see above). Check it works with
  `python3 -c "from huggingface_hub import HfApi; print(HfApi().whoami()['name'])"`, which prints only
  the user name.
- Never disable TLS checks or route around a proxy.
- **The licence decides, not the download.** Before using any data:
  1. Print the dataset card's licence fields and the full gate text (`extra_gated_prompt`,
     `extra_gated_fields`, `license`, and any terms linked from the card). `tools/tm_sources.py`
     prints these for FLORES+ (function `flores`).
  2. Show me that text verbatim, in a quote, and tell me in Egyptian Arabic what it allows and forbids.
  3. **Stop and ask me** whether the data may be shipped inside the app. Shipping means copying the
     sentences into `app/src/main/assets/tm/` and committing them, which is public redistribution.
     If the terms forbid re-hosting or redistributing the data (FLORES-style benchmarks often ask for this
     to prevent test-set contamination), or forbid commercial use, it must **not** be shipped or committed.
     In that case you may only use it locally for evaluation (a held-out score in `docs/BENCHMARKS.md`
     that says it was measured locally), and the raw files must stay out of git (add their folder to
     `.gitignore`).
- Correctness over volume: every pair goes through `tools/tm_build.py`, the same checks as the other
  sources (length >= 5 words, script, no exact or near duplicates, 90/10 held-out split). Do not hand-edit
  corpus files.
- No fake results. Report the real counts from the tools.

## Steps

1. Read `tools/tm_sources.py`, `tools/tm_build.py`, `tools/tm_sources_approved.json`,
   `docs/LICENSING.md` and the CI job `tm-build` in `.github/workflows/modulex-translator.yml` to see how
   sources are fetched, approved and built. CI builds from its own fetch **plus `data/tm-raw/`**, where
   sources that need a personal token are committed once they are approved.
2. Install the tools: `python3 -m pip install --user "huggingface_hub>=0.25" pyarrow`.
3. Print the licence and gate text (step "The licence decides" above) and wait for my answer.
4. If I approve shipping:
   - Fetch: `python3 tools/tm_sources.py --out data/tm-raw --only flores`. Check the printed counts
     (expect about 2,000 rows per FLORES+ dialect, with MSA and English for each).
   - In `tools/tm_sources_approved.json`, set `flores.approved` to `true`, with `license`, `attribution`
     (FLORES+ / Open Language Data Initiative, the dataset revision printed by the tool), `evidence`
     (a short quote of the licence and gate text you showed me), `share_alike` (CC BY-SA 4.0 applies to
     the corpus files) and `domain` (`news`, from the source articles).
   - Build: `python3 tools/tm_sources.py --out /tmp/tm-raw --only tatoeba,kaikki` and then
     `python3 tools/tm_build.py --raw /tmp/tm-raw --raw data/tm-raw` (on Windows use a folder under
     `%TEMP%`). Read the printed table and the random sample of 200 pairs per dialect yourself. Report
     anything that looks wrong (MSA instead of dialect, wrong translation, wrong dialect) and do not
     ship a dialect whose sample looks wrong; tell me instead.
   - Add `app/src/main/assets/licenses/flores.txt` (licence name and URL, attribution, share-alike note)
     and its entry in `app/src/main/assets/licenses/index.json`, in the same style as `tatoeba.txt` and
     `wiktionary.txt`. Move FLORES+ from "not shipped" to "shipped" in `docs/LICENSING.md`, with the gate
     terms summarised.
   - Run the JVM tests that check the corpora: `./gradlew :core:test` (needs only a JDK 17+, no Android
     SDK). Fix any failure in the tools; never weaken a test.
   - Commit `data/tm-raw/flores/`, `app/src/main/assets/tm/`, `engines/src/test/resources/tm-heldout/`,
     `app/src/main/assets/glossary/ar-en.wiktionary.tsv`, `tools/tm_sources_approved.json`, the licence
     files and the docs. Use a commit message that ends with `[mt-eval]` so CI measures plain / glossary /
     glossary + retrieval on the held-out pairs. Push to `claude/vibrant-cori-drj24g`.
5. If I do not approve shipping: do the local evaluation only (the `DialectHeldOutExperiment` in
   `engines/` runs with `-Pmodulex.mtEvalModels=/path/to/Hy-MT2-1.8B-Q4_K_M.gguf`; the model is at the
   URL in `app/src/main/assets/asset_manifest.json`), keep `data/tm-raw/flores/` out of git, and add the
   measured numbers to `docs/BENCHMARKS.md`, marked as local.
6. Check the CI run of your push yourself (GitHub Actions for the branch). Tell me which jobs are green,
   and the `TMSUMMARY` lines from the `mt-eval` job. If a check fails, fix it and push again.
7. At the end, tell me in Egyptian Arabic: the counts per dialect before and after, what was shipped,
   what was not and why, and the held-out numbers.
