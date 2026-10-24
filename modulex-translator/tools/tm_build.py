#!/usr/bin/env python3
"""Step 3 of the dialect corpus pipeline: build the verified corpora shipped in the APK from the raw
pairs of tools/tm_sources.py, using only the sources approved in tools/tm_sources_approved.json.

Every pair must pass, in this order (the report counts each rejection reason):
  schema        no tabs/newlines in any field, an English (or, for English dialects, Arabic) side present
  length        >= 5 words in the source after normalisation (punctuation-only tokens do not count)
  script        source/standard in the dialect's script, `en` in the target script (>= 80% of letters)
  same-as-std   source identical to its standard-language side (not dialect data)
  exact-dup     normalised source already kept (any source, any split)
  near-dup      word-3-gram Jaccard >= 0.8 with a kept source of the same dialect
Terms: dialect lemmas (Wiktionary, approved) that occur in the source, matched like core Glossary.kt.
Split: a stable 10% (by hash of the normalised source) is held out for evaluation and never shipped
as a reference; it goes to engines/src/test/resources/tm-heldout/.

Usage: tm_build.py --raw DIR [--seed 7]
"""
import argparse
import hashlib
import json
import os
import random
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(__file__))
from tm_inventory import normalise, trigrams  # noqa: E402
from tm_sources import read_rows, write_rows  # noqa: E402

ROOT = os.path.join(os.path.dirname(__file__), "..")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "tm")
HELDOUT = os.path.join(ROOT, "engines", "src", "test", "resources", "tm-heldout")
APPROVED = os.path.join(os.path.dirname(__file__), "tm_sources_approved.json")
HEADER = ["id", "dialect", "source", "standard", "en", "terms", "domain", "provenance", "license"]
SOURCE_ORDER = ["flores", "tatoeba", "doda", "kaikki"]  # earlier sources win exact/near duplicates

AR_PREFIX = r"(?<![^\W\d_])(?:و|ف)?(?:ب|ل|ك)?(?:ال|ل)?"
AR_SUFFIX = r"(?:ات|ان|ين|ون|ا|ي|ه|ها|هم|هن|كم|نا|ك)?(?![^\W\d_])"
_AR_GLOSSARY_MARKS = re.compile("[ؐ-ًؚ-ٰٟۖ-ۭـ]")


def glossary_norm(s):
    """Glossary.normalise for Arabic (core/translation/Glossary.kt)."""
    s = _AR_GLOSSARY_MARKS.sub("", s)
    return s.translate(str.maketrans({"أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ى": "ي"}))


def term_regex(term):
    t = glossary_norm(term)
    body = r"\s+".join(map(re.escape, t.split()))
    bare = t[2:] if t.startswith("ال") and len(t) > 3 else None
    alt = "|" + r"\s+".join(map(re.escape, bare.split())) if bare else ""
    return re.compile(f"{AR_PREFIX}(?:{body}{alt}){AR_SUFFIX}")


def script_share(s, arabic):
    letters = [c for c in s if c.isalpha()]
    if not letters:
        return 0.0
    hit = sum(1 for c in letters if ("ARABIC" in unicodedata.name(c, "")) == arabic)
    return hit / len(letters)


def words(norm):
    return [w for w in norm.split() if any(c.isalnum() for c in w)]


def heldout(norm):
    return int(hashlib.sha1(norm.encode()).hexdigest()[:8], 16) % 10 == 0


def load_terms(raw, dialect, approved):
    """Single- and multi-word dialect lemmas of at least 3 letters from approved Wiktionary data."""
    path = os.path.join(raw, "kaikki", f"{dialect}.terms.tsv")
    if "kaikki" not in approved or not os.path.exists(path):
        return []
    seen = set()
    for row in read_rows(path):
        w = row[0].strip()
        if len(glossary_norm(w).replace(" ", "")) >= 3 and script_share(w, True) >= 0.99:
            seen.add(w)
    return sorted(seen, key=len, reverse=True)


def build_dialect(raw, dialect, approved, domains):
    arabic = dialect.startswith("ar")
    terms = [(t, term_regex(t)) for t in load_terms(raw, dialect, approved)]
    kept, held, reasons = [], [], {}
    seen, index, grams_of = set(), {}, []

    def reject(r):
        reasons[r] = reasons.get(r, 0) + 1

    for source in SOURCE_ORDER:
        path = os.path.join(raw, source, f"{dialect}.tsv")
        if source not in approved or not os.path.exists(path):
            continue
        for row in read_rows(path):
            if len(row) != 6:
                reject("schema"); continue
            sid, src, std, en, prov, lic = (x.strip() for x in row)
            if not src or not en or any("\t" in x or "\n" in x for x in (src, std, en, prov)):
                reject("schema"); continue
            norm = normalise(src, arabic)
            ws = words(norm)
            if len(ws) < 5:
                reject("length"); continue
            if script_share(src, arabic) < 0.8 or (std and script_share(std, arabic) < 0.8) or script_share(en, not arabic) < 0.8:
                reject("script"); continue
            if std and normalise(std, arabic) == norm:
                reject("same-as-std"); continue
            if norm in seen:
                reject("exact-dup"); continue
            g = trigrams(ws)
            cand = set()
            for x in g:
                cand.update(index.get(x, ()))
            if any(len(g & grams_of[i]) / len(g | grams_of[i]) >= 0.8 for i in cand):
                reject("near-dup"); continue
            seen.add(norm)
            for x in g:
                index.setdefault(x, []).append(len(grams_of))
            grams_of.append(g)
            gnorm = glossary_norm(src)
            found = []
            for t, rx in terms:
                if len(found) == 5:
                    break
                if rx.search(gnorm) and not any(t in f for f in found):
                    found.append(t)
            rid = f"{dialect}-{source}-{hashlib.sha1(norm.encode()).hexdigest()[:10]}"
            out = [rid, dialect, src, std, en, ";".join(found), domains.get(source, "general"), prov, lic]
            (held if heldout(norm) else kept).append(out)
    return kept, held, reasons


def write_tsv(path, rows):
    write_rows(path, [HEADER] + rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", required=True)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--sample", type=int, default=200)
    a = ap.parse_args()
    cfg = json.load(open(APPROVED, encoding="utf-8"))
    approved = {k for k, v in cfg["sources"].items() if v.get("approved")}
    domains = {k: v.get("domain", "general") for k, v in cfg["sources"].items()}
    dialects = sorted({f[:-4] for s in approved if os.path.isdir(os.path.join(a.raw, s))
                       for f in os.listdir(os.path.join(a.raw, s)) if f.endswith(".tsv") and not f.endswith(".terms.tsv")})
    manifest = {"schema": 1, "columns": HEADER, "sources": {k: cfg["sources"][k] for k in sorted(approved)}, "corpora": {}}
    rng = random.Random(a.seed)
    print("| dialect | shipped | held out | rejected (reason: count) | bytes |\n|---|---:|---:|---|---:|")
    for d in dialects:
        kept, held, reasons = build_dialect(a.raw, d, approved, domains)
        if not kept:
            continue
        path = os.path.join(ASSETS, f"{d}.tsv")
        write_tsv(path, kept)
        write_tsv(os.path.join(HELDOUT, f"{d}.tsv"), held)
        data = open(path, "rb").read()
        per_source = {}
        for r in kept:
            s = r[0].split("-")[-2]
            per_source[s] = per_source.get(s, 0) + 1
        manifest["corpora"][d] = {"file": f"{d}.tsv", "pairs": len(kept), "heldout": len(held), "bySource": per_source,
                                  "withStandard": sum(1 for r in kept if r[3]), "withTerms": sum(1 for r in kept if r[5]),
                                  "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "rejected": reasons}
        print(f"| {d} | {len(kept)} | {len(held)} | {', '.join(f'{k}: {v}' for k, v in sorted(reasons.items()))} | {len(data)} |")
    json.dump(manifest, open(os.path.join(ASSETS, "manifest.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    for d in manifest["corpora"]:
        rows = read_rows(os.path.join(ASSETS, f"{d}.tsv"))[1:]
        print(f"\n### Random sample for manual review: {d} ({min(a.sample, len(rows))} of {len(rows)})")
        for r in rng.sample(rows, min(a.sample, len(rows))):
            print(f"{r[0]} | {r[2]} | {r[3]} | {r[4]} | terms: {r[5]}")


if __name__ == "__main__":
    main()
