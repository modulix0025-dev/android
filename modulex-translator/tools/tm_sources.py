#!/usr/bin/env python3
"""Step 2 of the dialect corpus pipeline: fetch every candidate source, print the licence evidence
from the source itself, and extract raw dialect sentence pairs for review. Runs in CI.

Nothing here decides that a source is allowed: the licence text is printed so a person can check it,
and only sources recorded as approved in tools/tm_sources_approved.json are later built into the app.

Output: <out>/<source>/<dialect>.tsv with columns source_id, source, standard, en, provenance, license
(empty fields when the source has no such translation), plus <out>/report.json.

Usage: tm_sources.py --out DIR [--only flores,tatoeba,...]
"""
import argparse
import bz2
import csv
import hashlib
import html
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
from tm_inventory import normalise  # noqa: E402  (same normal form as the inventory)

UA = "ModuleX-Translator-corpus-builder/1 (CI; github.com/modulix0025-dev/android)"

# dialect tag -> codes per source
DIALECTS = {
    "ar-EG": {"tatoeba": "arz", "flores": "arz_Arab", "kaikki": "Egyptian Arabic"},
    "ar-LB": {"tatoeba": "apc", "flores": "apc_Arab", "kaikki": "North Levantine Arabic"},
    "ar-PS": {"tatoeba": "ajp", "flores": "ajp_Arab", "kaikki": "South Levantine Arabic"},
    "ar-IQ": {"tatoeba": "acm", "flores": "acm_Arab", "kaikki": "Iraqi Arabic"},
    "ar-MA": {"tatoeba": "ary", "flores": "ary_Arab", "kaikki": "Moroccan Arabic", "doda": True},
    "ar-TN": {"tatoeba": "aeb", "flores": "aeb_Arab", "kaikki": "Tunisian Arabic"},
    "ar-DZ": {"tatoeba": "arq", "kaikki": "Algerian Arabic"},
    "ar-LY": {"tatoeba": "ayl", "kaikki": "Libyan Arabic"},
    "ar-SD": {"tatoeba": "apd", "kaikki": "Sudanese Arabic"},
    "ar-SA": {"tatoeba": "acw", "kaikki": "Hijazi Arabic"},
    "ar-SA-najd": {"tatoeba": "ars", "flores": "ars_Arab", "kaikki": "Najdi Arabic"},
    "ar-AE": {"tatoeba": "afb", "kaikki": "Gulf Arabic"},
    "ar-YE": {"flores": "acq_Arab", "kaikki": "Yemeni Arabic"},
}

# Pages whose licence wording is printed for review (sources without a machine-readable licence).
LICENCE_PAGES = {
    "flores": ["https://huggingface.co/datasets/openlanguagedata/flores_plus/raw/main/README.md"],
    "tatoeba": ["https://tatoeba.org/en/terms_of_use", "https://tatoeba.org/en/downloads"],
    "kaikki": ["https://kaikki.org/dictionary/rawdata.html", "https://kaikki.org/index.html"],
    "doda": ["https://raw.githubusercontent.com/darija-open-dataset/dataset/main/LICENSE",
             "https://raw.githubusercontent.com/darija-open-dataset/dataset/main/README.md"],
    "arzen-multigenre": ["https://data.mendeley.com/public-api/datasets/6k97jty9xg/versions/4",
                         "https://data.mendeley.com/datasets/6k97jty9xg/4"],
    "padic": ["https://smart.loria.fr/?p=330", "https://sourceforge.net/projects/padic/"],
    "lisan": ["https://sina.birzeit.edu/currasat/about-en.html"],
}
LICENCE_WORDS = re.compile(r"licen[cs]e|commercial|creative commons|cc[- ]by|\bmit\b|apache|gpl|copyright|"
                           r"attribution|research (?:purposes|use)|terms of use|permission|redistribut", re.I)


def get(url, cache, retries=4, headers=None):
    name = os.path.join(cache, hashlib.sha256(url.encode()).hexdigest())
    if os.path.exists(name):
        return open(name, "rb").read()
    err = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
            with urllib.request.urlopen(req, timeout=300) as r:
                data = r.read()
            open(name, "wb").write(data)
            return data
        except urllib.error.HTTPError as e:
            if e.code in (401, 403, 404):
                raise RuntimeError(f"{url}: HTTP {e.code}")
            err = e
        except (urllib.error.URLError, TimeoutError) as e:
            err = e
        time.sleep(2 ** (attempt + 1) * (8 if isinstance(err, urllib.error.HTTPError) and err.code == 429 else 1))
    raise RuntimeError(f"{url}: {err}")


def print_licence_evidence(source, cache):
    print(f"\n=== LICENCE EVIDENCE: {source} ===")
    for url in LICENCE_PAGES.get(source, []):
        try:
            text = get(url, cache).decode("utf-8", "replace")
        except Exception as e:
            print(f"[{url}] not reachable: {e}")
            continue
        if "<html" in text[:2000].lower():
            text = html.unescape(re.sub(r"<script.*?</script>|<style.*?</style>|<[^>]+>", " ", text, flags=re.S | re.I))
        lines = [" ".join(l.split()) for l in re.split(r"[\r\n]+|(?<=[.!?])\s{2,}", text)]
        hits = [l for l in lines if l and LICENCE_WORDS.search(l)]
        print(f"[{url}] {len(text)} chars, {len(hits)} licence-related lines:")
        for l in hits[:40]:
            print("   " + l[:400])
    print(f"=== END {source} ===", flush=True)


def words(s, arabic=True):
    return [w for w in normalise(s, arabic).split() if any(c.isalnum() for c in w)]


# ---------------------------------------------------------------------------------------------- FLORES+
def flores(out, cache):
    from huggingface_hub import HfApi, hf_hub_download
    import pyarrow.parquet as pq
    repo = "openlanguagedata/flores_plus"
    token = os.environ.get("HF_TOKEN") or None
    info = HfApi().dataset_info(repo, token=token)
    print(f"flores_plus revision {info.sha} gated={getattr(info, 'gated', None)} card_license="
          f"{(info.card_data or {}).get('license') if info.card_data else None}")
    files = [f for f in HfApi().list_repo_files(repo, repo_type="dataset", revision=info.sha, token=token)
             if f.endswith(".parquet")]
    print(f"{len(files)} parquet files, e.g. {files[:4]}")

    def load(code):
        rows = {}
        for f in files:
            if os.path.basename(f).startswith(code):
                p = hf_hub_download(repo, f, repo_type="dataset", revision=info.sha, token=token,
                                    cache_dir=os.path.join(cache, "hf"))
                split = f.split("/")[0]
                for r in pq.read_table(p).to_pylist():
                    rows[f"{split}:{r['id']}"] = r["text"]
        return rows

    eng, arb = load("eng_Latn"), load("arb_Arab")
    counts = {}
    for tag, codes in DIALECTS.items():
        if "flores" not in codes:
            continue
        rows = load(codes["flores"])
        os.makedirs(f"{out}/flores", exist_ok=True)
        with open(f"{out}/flores/{tag}.tsv", "w", encoding="utf-8", newline="") as fh:
            w = csv.writer(fh, delimiter="\t", lineterminator="\n")
            for k, text in sorted(rows.items()):
                w.writerow([k, text, arb.get(k, ""), eng.get(k, ""),
                            f"FLORES+ {info.sha[:12]} {k}", "CC-BY-SA-4.0"])
        counts[tag] = len(rows)
    return {"revision": info.sha, "counts": counts}


# ---------------------------------------------------------------------------------------------- Tatoeba
TATOEBA = "https://downloads.tatoeba.org/exports/per_language"


def tatoeba_rows(code, cache):
    data = get(f"{TATOEBA}/{code}/{code}_sentences.tsv.bz2", cache)
    return {r[0]: r[2] for r in csv.reader(io.StringIO(bz2.decompress(data).decode("utf-8")), delimiter="\t",
                                           quoting=csv.QUOTE_NONE) if len(r) >= 3}


def tatoeba_links(code, other, cache):
    try:
        data = get(f"{TATOEBA}/{code}/{code}-{other}_links.tsv.bz2", cache)
    except RuntimeError:
        return {}
    links = {}
    for r in csv.reader(io.StringIO(bz2.decompress(data).decode("utf-8")), delimiter="\t", quoting=csv.QUOTE_NONE):
        if len(r) >= 2:
            links.setdefault(r[0], r[1])
    return links


def tatoeba(out, cache):
    eng, ara = tatoeba_rows("eng", cache), tatoeba_rows("ara", cache)
    counts = {}
    for tag, codes in DIALECTS.items():
        code = codes.get("tatoeba")
        if not code:
            continue
        try:
            rows = tatoeba_rows(code, cache)
        except RuntimeError:
            continue
        to_en, to_ar = tatoeba_links(code, "eng", cache), tatoeba_links(code, "ara", cache)
        os.makedirs(f"{out}/tatoeba", exist_ok=True)
        with open(f"{out}/tatoeba/{tag}.tsv", "w", encoding="utf-8", newline="") as fh:
            w = csv.writer(fh, delimiter="\t", lineterminator="\n")
            for sid, text in rows.items():
                w.writerow([sid, text, ara.get(to_ar.get(sid), ""), eng.get(to_en.get(sid), ""),
                            f"Tatoeba sentence #{sid} https://tatoeba.org/sentences/show/{sid}", "CC-BY-2.0-FR"])
        counts[tag] = len(rows)
    return {"counts": counts}


# ---------------------------------------------------------------------------------------------- Wiktionary (kaikki.org)
def kaikki(out, cache):
    counts = {}
    for tag, codes in DIALECTS.items():
        name = codes.get("kaikki")
        if not name:
            continue
        url = f"https://kaikki.org/dictionary/{name.replace(' ', '%20')}/kaikki.org-dictionary-{name.replace(' ', '')}.jsonl"
        try:
            data = get(url, cache)
        except RuntimeError as e:
            print(f"kaikki {tag}: {e}")
            continue
        n_entries, n_examples = 0, 0
        os.makedirs(f"{out}/kaikki", exist_ok=True)
        with open(f"{out}/kaikki/{tag}.tsv", "w", encoding="utf-8", newline="") as fh, \
                open(f"{out}/kaikki/{tag}.terms.tsv", "w", encoding="utf-8", newline="") as th:
            w = csv.writer(fh, delimiter="\t", lineterminator="\n")
            t = csv.writer(th, delimiter="\t", lineterminator="\n")
            for line in data.decode("utf-8").splitlines():
                e = json.loads(line)
                n_entries += 1
                for i, sense in enumerate(e.get("senses", [])):
                    glosses = sense.get("glosses") or []
                    if glosses:
                        t.writerow([e.get("word", ""), e.get("pos", ""), glosses[-1], ";".join(sense.get("tags", []))])
                    for j, ex in enumerate(sense.get("examples", [])):
                        en = ex.get("english") or ex.get("translation") or ""
                        if ex.get("text") and en:
                            n_examples += 1
                            w.writerow([f"{e.get('word')}#{i}.{j}", ex["text"], "", en,
                                        f"Wiktionary entry «{e.get('word')}» via kaikki.org", "CC-BY-SA-4.0"])
        counts[tag] = {"entries": n_entries, "examples_with_en": n_examples}
    return {"counts": counts}


# ---------------------------------------------------------------------------------------------- DODa
def doda(out, cache):
    base = "https://raw.githubusercontent.com/darija-open-dataset/dataset/main"
    data = get(f"{base}/sentences.csv", cache).decode("utf-8")
    rows = list(csv.DictReader(io.StringIO(data)))
    print(f"DODa sentences.csv columns: {list(rows[0].keys()) if rows else []}")
    os.makedirs(f"{out}/doda", exist_ok=True)
    with open(f"{out}/doda/ar-MA.tsv", "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh, delimiter="\t", lineterminator="\n")
        for i, r in enumerate(rows):
            src = r.get("darija_ar") or ""
            if src:
                w.writerow([str(i), src, "", r.get("eng", ""), f"Darija Open Dataset sentences.csv row {i}", "MIT"])
    return {"counts": {"ar-MA": len(rows)}}


FETCHERS = {"flores": flores, "tatoeba": tatoeba, "kaikki": kaikki, "doda": doda}
REVIEW_ONLY = ["arzen-multigenre", "padic", "lisan"]  # licence evidence printed, data not fetched yet


def summarise(out):
    """Per source and dialect: rows, rows that pass length (>= 5 words) and have an English side, and samples."""
    summary = {}
    for source in sorted(os.listdir(out)):
        d = os.path.join(out, source)
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            if not f.endswith(".tsv") or f.endswith(".terms.tsv"):
                continue
            rows = list(csv.reader(open(os.path.join(d, f), encoding="utf-8"), delimiter="\t", quoting=csv.QUOTE_NONE))
            ok = [r for r in rows if len(words(r[1])) >= 5 and r[3].strip()]
            summary[f"{source}/{f[:-4]}"] = {"rows": len(rows), "five_words_with_en": len(ok),
                                             "with_standard": sum(1 for r in ok if r[2].strip())}
            print(f"\n--- {source}/{f[:-4]}: {len(rows)} rows, {len(ok)} with >= 5 words and English ---")
            for r in ok[:: max(1, len(ok) // 8)][:8]:
                print(f"   {r[1][:160]}  ||  {r[2][:120]}  ||  {r[3][:160]}")
    return summary


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--only", default="")
    ap.add_argument("--cache", default=os.path.join(os.environ.get("RUNNER_TEMP", "/tmp"), "tm-src-cache"))
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    os.makedirs(a.cache, exist_ok=True)
    only = set(filter(None, a.only.split(",")))
    report, failures = {}, []
    for source in list(FETCHERS) + REVIEW_ONLY:
        if only and source not in only:
            continue
        print_licence_evidence(source, a.cache)
        if source in FETCHERS:
            try:
                report[source] = FETCHERS[source](a.out, a.cache)
                print(f"{source}: {json.dumps(report[source], ensure_ascii=False)}", flush=True)
            except Exception as e:  # report and continue with the other sources
                failures.append(f"{source}: {type(e).__name__}: {e}")
                print(f"{source} FAILED: {e}", flush=True)
    report["summary"] = summarise(a.out)
    report["failures"] = failures
    json.dump(report, open(os.path.join(a.out, "report.json"), "w"), ensure_ascii=False, indent=1)
    print("\n| source/dialect | rows | >= 5 words with EN | of those with standard |\n|---|---:|---:|---:|")
    for k, v in report["summary"].items():
        print(f"| {k} | {v['rows']} | {v['five_words_with_en']} | {v['with_standard']} |")
    if failures:
        print("\nFailures:\n" + "\n".join(failures))
        sys.exit(1)


if __name__ == "__main__":
    main()
