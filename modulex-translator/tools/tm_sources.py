#!/usr/bin/env python3
"""Step 2 of the dialect corpus pipeline: fetch every candidate source, print the licence evidence
from the source itself, and extract raw dialect sentence pairs for review. Runs in CI.

Nothing here decides that a source is allowed: the licence text is printed so a person can check it,
and only sources marked approved in tools/tm_sources_approved.json are built into the app
(tools/tm_build.py). Sources whose licence forbids commercial redistribution are not fetched at all.

Output: <out>/<source>/<dialect>.tsv, one pair per line, tab-separated, no quoting:
  source_id, source, standard, en, provenance, license    (empty field = no such translation)
plus <out>/kaikki/<dialect>.terms.tsv (word, pos, gloss, tags) and <out>/report.json.

Usage: tm_sources.py --out DIR [--only tatoeba,kaikki,...]
"""
import argparse
import bz2
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
    "ar-MA": {"tatoeba": "ary", "flores": "ary_Arab", "kaikki": "Moroccan Arabic"},
    "ar-TN": {"tatoeba": "aeb", "flores": "aeb_Arab", "kaikki": "Tunisian Arabic"},
    "ar-DZ": {"tatoeba": "arq"},
    "ar-LY": {"tatoeba": "ayl"},
    "ar-SD": {"tatoeba": "apd"},
    "ar-SA": {"tatoeba": "acw", "kaikki": "Hijazi Arabic"},
    "ar-SA-najd": {"tatoeba": "ars", "flores": "ars_Arab"},
    "ar-AE": {"tatoeba": "afb", "kaikki": "Gulf Arabic"},
    "ar-YE": {"flores": "acq_Arab"},
}

# Pages whose licence wording is printed for review.
LICENCE_PAGES = {
    "tatoeba": ["https://tatoeba.org/en/terms_of_use", "https://tatoeba.org/en/downloads"],
    "kaikki": ["https://en.wiktionary.org/wiki/Wiktionary:Copyrights", "https://kaikki.org/dictionary/rawdata.html"],
    "oldi_seed": ["https://huggingface.co/datasets/openlanguagedata/oldi_seed/raw/main/README.md"],
    "smol": ["https://huggingface.co/datasets/google/smol/raw/main/README.md"],
    "flores": [],  # gated on Hugging Face: the dataset card fields (licence, gate prompt) are printed by flores()
    "doda": ["https://raw.githubusercontent.com/darija-open-dataset/dataset/main/LICENSE"],
    "arzen-multigenre": ["https://data.mendeley.com/datasets/6k97jty9xg/4"],
    "padic": ["https://smart.loria.fr/?p=330", "https://sourceforge.net/projects/padic/"],
    "lisan": ["https://sina.birzeit.edu/currasat/about-en.html"],
}
LICENCE_WORDS = re.compile(r"licen[cs]e|commercial|creative commons|cc[- ]by|\bmit\b|apache|gpl|copyright|"
                           r"attribution|research (?:purposes|use)|terms of use|permission|redistribut|share ?alike", re.I)


def clean(field):
    return " ".join(str(field or "").split())


def write_rows(path, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        for r in rows:
            fh.write("\t".join(clean(x) for x in r) + "\n")


def read_rows(path):
    with open(path, encoding="utf-8") as fh:
        return [line.rstrip("\n").split("\t") for line in fh if line.strip()]


def get(url, cache, retries=4, timeout=300):
    name = os.path.join(cache, hashlib.sha256(url.encode()).hexdigest())
    if os.path.exists(name):
        return open(name, "rb").read()
    err = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                data = r.read()
            open(name, "wb").write(data)
            return data
        except urllib.error.HTTPError as e:
            if e.code in (401, 403, 404):
                raise RuntimeError(f"{url}: HTTP {e.code}")
            err = e
        except (urllib.error.URLError, TimeoutError) as e:
            err = e
        if attempt + 1 < retries:
            time.sleep(2 ** (attempt + 1) * (8 if isinstance(err, urllib.error.HTTPError) and err.code == 429 else 1))
    raise RuntimeError(f"{url}: {err}")


def print_licence_evidence(source, cache):
    print(f"\n=== LICENCE EVIDENCE: {source} ===")
    for url in LICENCE_PAGES.get(source, []):
        try:
            text = get(url, cache, retries=2, timeout=40).decode("utf-8", "replace")
        except Exception as e:
            print(f"[{url}] not reachable: {e}")
            continue
        if "<html" in text[:2000].lower():
            text = html.unescape(re.sub(r"<script.*?</script>|<style.*?</style>|<[^>]+>", "\n", text, flags=re.S | re.I))
        lines = [" ".join(l.split()) for l in re.split(r"[\r\n]+", text)]
        hits = [l for l in lines if l and LICENCE_WORDS.search(l)]
        print(f"[{url}] {len(text)} chars, {len(hits)} licence-related lines:")
        for l in hits[:40]:
            print("   " + l[:400])
    print(f"=== END {source} ===", flush=True)


def words(s):
    return [w for w in normalise(s, True).split() if any(c.isalnum() for c in w)]


# ---------------------------------------------------------------------------------------------- FLORES+
def flores(out, cache):
    """Gated dataset: prints its licence and gate prompt; fetches only with an HF_TOKEN whose account accepted it."""
    from huggingface_hub import HfApi, hf_hub_download
    import pyarrow.parquet as pq
    repo = "openlanguagedata/flores_plus"
    token = os.environ.get("HF_TOKEN") or None
    api = HfApi()
    info = api.dataset_info(repo, token=token)
    card = info.card_data.to_dict() if info.card_data else {}
    print(f"flores_plus revision {info.sha} gated={getattr(info, 'gated', None)} license={card.get('license')}")
    for k, v in card.items():
        if k.startswith("extra_gated") or k in ("license", "license_name", "license_link"):
            print(f"   card {k}: {json.dumps(v, ensure_ascii=False)[:2000]}")
    if not token:
        raise RuntimeError("gated dataset: needs an HF_TOKEN secret of an account that accepted the gate terms above")
    files = [f for f in api.list_repo_files(repo, repo_type="dataset", revision=info.sha, token=token) if f.endswith(".parquet")]

    def load(code):
        rows = {}
        for f in files:
            if os.path.basename(f).startswith(code):
                p = hf_hub_download(repo, f, repo_type="dataset", revision=info.sha, token=token, cache_dir=os.path.join(cache, "hf"))
                for r in pq.read_table(p).to_pylist():
                    rows[f"{f.split('/')[0]}:{r['id']}"] = r["text"]
        return rows

    eng, arb = load("eng_Latn"), load("arb_Arab")
    counts = {}
    for tag, codes in DIALECTS.items():
        if "flores" in codes:
            rows = load(codes["flores"])
            write_rows(f"{out}/flores/{tag}.tsv", [[k, t, arb.get(k, ""), eng.get(k, ""), f"FLORES+ {info.sha[:12]} {k}",
                                                    "CC-BY-SA-4.0"] for k, t in sorted(rows.items())])
            counts[tag] = len(rows)
    return {"revision": info.sha, "counts": counts}


# ---------------------------------------------------------------------------------------------- public Hugging Face sets
def hf_files(repo, token=None):
    from huggingface_hub import HfApi
    info = HfApi().dataset_info(repo, token=token)
    files = HfApi().list_repo_files(repo, repo_type="dataset", revision=info.sha, token=token)
    card = info.card_data.to_dict() if info.card_data else {}
    print(f"{repo} revision {info.sha} gated={getattr(info, 'gated', None)} license={card.get('license')}")
    return info.sha, files


def hf_rows(repo, path, sha, cache):
    """Rows of one parquet / jsonl / tsv file as dicts."""
    from huggingface_hub import hf_hub_download
    local = hf_hub_download(repo, path, repo_type="dataset", revision=sha, cache_dir=os.path.join(cache, "hf"))
    if path.endswith(".parquet"):
        import pyarrow.parquet as pq
        return pq.read_table(local).to_pylist()
    text = open(local, encoding="utf-8").read()
    if path.endswith(".jsonl"):
        return [json.loads(l) for l in text.splitlines() if l.strip()]
    if path.endswith(".json"):
        d = json.loads(text)
        return d if isinstance(d, list) else [d]
    lines = [l.split("\t") for l in text.splitlines() if l.strip()]
    return [dict(zip(lines[0], l)) for l in lines[1:]] if lines else []


def show(repo, path, rows):
    print(f"   {path}: {len(rows)} rows, keys={list(rows[0].keys()) if rows else []}")
    for r in rows[:2]:
        print("      " + json.dumps(r, ensure_ascii=False)[:400])


def code_in(path, code):
    return re.search(rf"(?<![a-z]){code}(?![a-z])", os.path.basename(path).lower()) is not None


def oldi_seed(out, cache):
    """OLDI Seed (expert translations of English Wikipedia sentences); aligned to English by id."""
    repo = "openlanguagedata/oldi_seed"
    sha, files = hf_files(repo)
    data = [f for f in files if f.endswith((".parquet", ".jsonl", ".tsv")) and not f.startswith("dataset_cards")]
    print(f"   data files: {data[:12]} ... ({len(data)})")

    def load(code):
        rows = {}
        for f in data:
            if code in f:
                rs = hf_rows(repo, f, sha, cache)
                show(repo, f, rs)
                for i, r in enumerate(rs):
                    rows[str(r.get("id", i))] = r.get("text") or r.get("sentence") or ""
        return rows

    eng = load("eng_Latn")
    counts = {}
    for tag, code in (("ar-EG", "arz_Arab"), ("ar-MA", "ary_Arab")):
        rows = load(code)
        write_rows(f"{out}/oldi_seed/{tag}.tsv", [[k, t, "", eng.get(k, ""), f"OLDI Seed {sha[:12]} id {k}", "CC-BY-SA-4.0"]
                                                  for k, t in sorted(rows.items())])
        counts[tag] = {"rows": len(rows), "with_en": sum(1 for k in rows if eng.get(k))}
    return {"revision": sha, "counts": counts}


SMOL_CODES = {"arz": "ar-EG", "apc": "ar-LB", "aeb": "ar-TN", "apd": "ar-SD", "ayl": "ar-LY"}


def smol(out, cache):
    """Google SMOL: SmolSent / SmolDoc human translations from English, and the GATITOS lexicon."""
    repo = "google/smol"
    sha, files = hf_files(repo)
    pairs, terms, counts = {}, {}, {}
    for f in files:
        code = next((c for c in SMOL_CODES if code_in(f, c)), None)
        if not code or not f.endswith((".jsonl", ".parquet", ".tsv", ".json")):
            continue
        tag = SMOL_CODES[code]
        rows = hf_rows(repo, f, sha, cache)
        show(repo, f, rows)
        part = f.split("/")[0]
        for i, r in enumerate(rows):
            src, trg = r.get("src"), r.get("trg")
            if isinstance(r.get("srcs"), list) and isinstance(r.get("trgs"), list) and len(r["srcs"]) == len(r["trgs"]):
                items = list(zip(r["srcs"], r["trgs"]))
            elif isinstance(src, str) and isinstance(trg, str):
                items = [(src, trg)]
            else:
                continue
            for j, (en, dia) in enumerate(items):
                # SMOL translates from English: src is English, trg the dialect (check the printed samples).
                row = [f"{part}:{r.get('id', i)}:{j}", dia, "", en, f"Google SMOL {part} ({sha[:12]}) {r.get('id', i)}", "CC-BY-4.0"]
                (terms if part == "gatitos" else pairs).setdefault(tag, []).append(row)
    for tag in SMOL_CODES.values():
        write_rows(f"{out}/smol/{tag}.tsv", pairs.get(tag, []))
        write_rows(f"{out}/smol/{tag}.lexicon.tsv", [[r[1], r[3]] for r in terms.get(tag, [])])
        counts[tag] = {"pairs": len(pairs.get(tag, [])), "lexicon": len(terms.get(tag, []))}
    return {"revision": sha, "counts": counts}


def review_only_hf(out, cache):
    """Datasets whose provenance must be seen before approval: list files and print samples, write nothing."""
    for repo, pattern in (("lahga/arabic-dialects", r"examples|entries|dialects"),
                          ("jonathanmutal/Medical-Questionnaire-Multilingual-Translation", r"translations")):
        try:
            sha, files = hf_files(repo)
            for f in files:
                if re.search(pattern, f) and f.endswith((".jsonl", ".tsv", ".parquet")):
                    rows = hf_rows(repo, f, sha, cache)
                    show(repo, f, rows)
                    for r in rows[len(rows) // 2: len(rows) // 2 + 6]:
                        print("      " + json.dumps(r, ensure_ascii=False)[:400])
            card = get(f"https://huggingface.co/datasets/{repo}/raw/main/README.md", cache, retries=2, timeout=40)
            for l in (card or b"").decode("utf-8", "replace").splitlines():
                if re.search(r"source|provenance|licen|translat|annotat|generated|native", l, re.I):
                    print("   | " + " ".join(l.split())[:300])
        except Exception as e:
            print(f"{repo}: {type(e).__name__}: {e}")
    return {}


# ---------------------------------------------------------------------------------------------- Tatoeba
TATOEBA = "https://downloads.tatoeba.org/exports/per_language"


def tatoeba_detailed(code, cache):
    """id -> (text, username); the username is the attribution CC-BY requires."""
    data = bz2.decompress(get(f"{TATOEBA}/{code}/{code}_sentences_detailed.tsv.bz2", cache)).decode("utf-8")
    out = {}
    for line in data.splitlines():
        f = line.split("\t")
        if len(f) >= 4:
            out[f[0]] = (f[2], f[3] if f[3] != "\\N" else "")
    return out


def tatoeba_links(code, other, cache):
    try:
        data = bz2.decompress(get(f"{TATOEBA}/{code}/{code}-{other}_links.tsv.bz2", cache)).decode("utf-8")
    except RuntimeError:
        return {}
    links = {}
    for line in data.splitlines():
        f = line.split("\t")
        if len(f) >= 2:
            links.setdefault(f[0], []).append(f[1])
    return links


def tatoeba(out, cache):
    eng, ara = tatoeba_detailed("eng", cache), tatoeba_detailed("ara", cache)
    # MSA word frequencies: tm_build drops "dialect terms" that are ordinary MSA words (or names like Tom).
    freq = {}
    for text, _ in ara.values():
        for w in set(normalise(text, True).split()):
            freq[w] = freq.get(w, 0) + 1
    write_rows(f"{out}/tatoeba/ara.words.tsv", sorted(([w, str(n)] for w, n in freq.items()), key=lambda r: -int(r[1])))
    counts = {}
    for tag, codes in DIALECTS.items():
        code = codes.get("tatoeba")
        if not code:
            continue
        try:
            rows = tatoeba_detailed(code, cache)
        except RuntimeError:
            continue
        to_en, to_ar = tatoeba_links(code, "eng", cache), tatoeba_links(code, "ara", cache)
        res = []
        for sid, (text, user) in rows.items():
            en_id = next((i for i in to_en.get(sid, []) if i in eng), None)
            ar_id = next((i for i in to_ar.get(sid, []) if i in ara), None)
            authors = [f"#{sid} by {user or 'unknown'}"]
            if en_id:
                authors.append(f"en #{en_id} by {eng[en_id][1] or 'unknown'}")
            if ar_id:
                authors.append(f"ar #{ar_id} by {ara[ar_id][1] or 'unknown'}")
            res.append([sid, text, ara[ar_id][0] if ar_id else "", eng[en_id][0] if en_id else "",
                        "Tatoeba (tatoeba.org) " + ", ".join(authors), "CC-BY-2.0-FR"])
        write_rows(f"{out}/tatoeba/{tag}.tsv", res)
        counts[tag] = len(res)
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
        pairs, terms, n = [], [], 0
        for line in data.decode("utf-8").splitlines():
            e = json.loads(line)
            n += 1
            word = e.get("word", "")
            for i, sense in enumerate(e.get("senses", [])):
                glosses = sense.get("glosses") or []
                if glosses:
                    terms.append([word, e.get("pos", ""), glosses[-1], ";".join(sense.get("tags", []))])
                if set(sense.get("tags", [])) & UNWANTED_SENSES:
                    continue
                for j, ex in enumerate(sense.get("examples", [])):
                    en = ex.get("english") or ex.get("translation") or ""
                    # Only examples written by Wiktionary editors are CC BY-SA. Quotations of books, songs or news
                    # (type "quotation", or any example with a "ref") stay under their authors' copyright.
                    if ex.get("type") == "quotation" or ex.get("ref"):
                        continue
                    if ex.get("text") and en:
                        pairs.append([f"{word}#{i}.{j}", ex["text"], "", en,
                                      f"Wiktionary contributors, entry «{word}» (en.wiktionary.org, via kaikki.org)", "CC-BY-SA-4.0"])
        write_rows(f"{out}/kaikki/{tag}.tsv", pairs)
        write_rows(f"{out}/kaikki/{tag}.terms.tsv", terms)
        counts[tag] = {"entries": n, "examples_with_en": len(pairs), "senses": len(terms)}
    return {"counts": counts}


UNWANTED_SENSES = {"vulgar", "offensive", "derogatory", "slur", "ethnic", "sexual"}

FETCHERS = {"flores": flores, "tatoeba": tatoeba, "kaikki": kaikki, "oldi_seed": oldi_seed, "smol": smol,
            "hf_review": review_only_hf}
# Licence evidence printed, data not fetched: DODa is CC BY-NC 4.0; the others are unresolved.
REVIEW_ONLY = ["doda", "arzen-multigenre", "padic", "lisan"]


def summarise(out):
    """Per source and dialect: rows, rows with >= 5 words and an English side, and a few samples."""
    summary = {}
    for source in sorted(os.listdir(out)):
        d = os.path.join(out, source)
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            if not f.endswith(".tsv") or f.count(".") != 1:
                continue
            rows = [r for r in read_rows(os.path.join(d, f)) if len(r) == 6]
            ok = [r for r in rows if len(words(r[1])) >= 5 and r[3]]
            summary[f"{source}/{f[:-4]}"] = {"rows": len(rows), "five_words_with_en": len(ok),
                                             "with_standard": sum(1 for r in ok if r[2])}
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
                print(f"{source} NOT FETCHED: {e}", flush=True)
    report["summary"] = summarise(a.out)
    report["failures"] = failures
    json.dump(report, open(os.path.join(a.out, "report.json"), "w"), ensure_ascii=False, indent=1)
    print("\n| source/dialect | rows | >= 5 words with EN | of those with standard |\n|---|---:|---:|---:|")
    for k, v in report["summary"].items():
        print(f"| {k} | {v['rows']} | {v['five_words_with_en']} | {v['with_standard']} |")
    if failures:
        print("\nNot fetched:\n" + "\n".join(failures))


if __name__ == "__main__":
    main()
