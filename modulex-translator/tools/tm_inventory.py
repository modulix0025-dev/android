#!/usr/bin/env python3
"""Step 1 of the dialect corpus pipeline: measure how much openly-licensed data exists per dialect
before anything is generated. Runs in CI (the dev container cannot reach these hosts).

For every dialect code it downloads the Tatoeba per-language export and counts, after the corpus
rules (>= 5 words, no exact duplicates after normalisation, no word-3-gram Jaccard >= 0.8
near-duplicates), how many sentences have a direct human translation into English and into the
standard language. It also counts Wiktionary category members (slang, idioms, regional terms).
Nothing is kept: the output is only the table (stdout, Markdown) and a JSON file.

Licences: Tatoeba sentences are CC-BY 2.0 FR (some CC0); Wiktionary is CC-BY-SA 4.0 / GFDL.

Usage: tm_inventory.py --out inventory.json [--cache DIR]
"""
import argparse
import bz2
import csv
import hashlib
import io
import json
import os
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

TATOEBA = "https://downloads.tatoeba.org/exports/per_language"
WIKT = "https://en.wiktionary.org/w/api.php"
# The near-duplicate check is pairwise over shared trigrams; it runs on sets up to this size (every
# dialect fits). Million-sentence standard languages get exact-duplicate removal only ("near_dup": null).
NEAR_DUP_LIMIT = 200_000
UA = "ModuleX-Translator-corpus-inventory/1 (CI; github.com/modulix0025-dev/android)"

# app language -> (Tatoeba code of the standard language, [(dialect tag, Tatoeba code, name)]).
# Only dialects Tatoeba tags with their own ISO 639-3 code can be counted; regional variants it does
# not separate (en-US/en-GB, es-MX/es-ES, pt-BR/pt-PT, fr-FR/fr-CA) show up with code None.
DIALECTS = {
    "ar": ("ara", [  # Tatoeba files Modern Standard Arabic under the macro code "ara"
        ("ar-EG", "arz", "Egyptian"), ("ar-LB", "apc", "Levantine (North)"), ("ar-PS", "ajp", "Levantine (South)"),
        ("ar-IQ", "acm", "Mesopotamian"), ("ar-MA", "ary", "Moroccan"), ("ar-TN", "aeb", "Tunisian"),
        ("ar-DZ", "arq", "Algerian"), ("ar-LY", "ayl", "Libyan"), ("ar-SD", "apd", "Sudanese"),
        ("ar-SA", "acw", "Hijazi"), ("ar-SA-najd", "ars", "Najdi"), ("ar-AE", "afb", "Gulf"),
        ("ar-TD", "shu", "Chadian"), ("ar-MT", "mlt", "Maltese (separate language, for reference)"),
    ]),
    "en": ("eng", [("en-US", None, "American"), ("en-GB", None, "British"), ("en-sco", "sco", "Scots")]),
    "es": ("spa", [("es-MX", None, "Mexican"), ("es-ES", None, "Peninsular"), ("es-AR", None, "Rioplatense")]),
    "pt": ("por", [("pt-BR", None, "Brazilian"), ("pt-PT", None, "European")]),
    "fr": ("fra", [("fr-FR", None, "France"), ("fr-CA", None, "Canada")]),
    "de": ("deu", [("de-CH", "gsw", "Swiss German"), ("de-AT-bar", "bar", "Bavarian"), ("de-nds", "nds", "Low German")]),
    "it": ("ita", [("it-nap", "nap", "Neapolitan"), ("it-scn", "scn", "Sicilian"), ("it-vec", "vec", "Venetian")]),
    "zh": ("cmn", [("zh-wuu", "wuu", "Wu"), ("zh-nan", "nan", "Min Nan"), ("zh-hak", "hak", "Hakka")]),
    "yue": ("yue", []),
    "fa": ("pes", [("fa-AF", "prs", "Dari")]),
}

# Wiktionary categories that hold dialect lemmas, slang or idioms (counts only).
WIKT_CATS = {
    "ar-EG": ["Egyptian Arabic lemmas", "Egyptian Arabic idioms", "Egyptian Arabic slang"],
    "ar-LB": ["South Levantine Arabic lemmas", "North Levantine Arabic lemmas", "Levantine Arabic lemmas"],
    "ar-IQ": ["Iraqi Arabic lemmas", "Mesopotamian Arabic lemmas"],
    "ar-MA": ["Moroccan Arabic lemmas", "Moroccan Arabic idioms"],
    "ar-TN": ["Tunisian Arabic lemmas"],
    "ar-DZ": ["Algerian Arabic lemmas"],
    "ar-SD": ["Sudanese Arabic lemmas"],
    "ar-SA": ["Hijazi Arabic lemmas", "Najdi Arabic lemmas"],
    "ar-AE": ["Gulf Arabic lemmas"],
    "ar": ["Arabic idioms", "Arabic slang"],
    "en-US": ["American English", "American English slang"],
    "en-GB": ["British English", "British English slang"],
    "en": ["English idioms", "English slang", "English internet slang"],
    "es-MX": ["Mexican Spanish"],
    "es-ES": ["European Spanish"],
    "es-AR": ["Rioplatense Spanish", "Argentine Spanish"],
    "pt-BR": ["Brazilian Portuguese"],
    "pt-PT": ["European Portuguese"],
    "fr-CA": ["Quebec French", "Canadian French"],
    "fr-FR": ["French slang"],
}

_AR_MARKS = re.compile("[ؐ-ًؚ-ٰٟۖ-ۭـ]")
_PUNCT = re.compile(r"[^\w\s]", re.UNICODE)


def normalise(s, arabic):
    """Corpus normal form: NFKC, case-fold, Arabic diacritics/tatweel removed and alef/yaa/hamza
    forms unified, punctuation dropped, whitespace collapsed."""
    s = unicodedata.normalize("NFKC", s).casefold()
    if arabic:
        s = _AR_MARKS.sub("", s)
        s = s.translate(str.maketrans({"أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ى": "ي", "ئ": "ي", "ؤ": "و", "ء": ""}))
    s = _PUNCT.sub(" ", s)
    s = "".join(c for c in unicodedata.normalize("NFD", s) if not unicodedata.combining(c)) if not arabic else s
    return " ".join(s.split())


def trigrams(words):
    return {tuple(words[i:i + 3]) for i in range(len(words) - 2)}


def get(url, cache, retries=4):
    name = os.path.join(cache, hashlib.sha256(url.encode()).hexdigest())
    if os.path.exists(name):
        return open(name, "rb").read()
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=120) as r:
                data = r.read()
            open(name, "wb").write(data)
            return data
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            err = e
            if e.code == 429:  # Wiktionary rate limit: back off longer
                time.sleep(15 * (attempt + 1))
        except (urllib.error.URLError, TimeoutError) as e:
            err = e
        time.sleep(2 ** (attempt + 1))
    raise RuntimeError(f"{url}: {err}")


def tsv(data):
    if data is None:
        return []
    text = bz2.decompress(data).decode("utf-8")
    return list(csv.reader(io.StringIO(text), delimiter="\t", quoting=csv.QUOTE_NONE))


def linked_ids(code, other, cache):
    rows = tsv(get(f"{TATOEBA}/{code}/{code}-{other}_links.tsv.bz2", cache))
    return {r[0] for r in rows if len(r) >= 2}


def tatoeba(code, std, arabic, cache):
    rows = tsv(get(f"{TATOEBA}/{code}/{code}_sentences.tsv.bz2", cache))
    if not rows:
        return None
    total = len(rows)
    to_eng = linked_ids(code, "eng", cache) if code != "eng" else set()
    to_std = linked_ids(code, std, cache) if code != std else set()
    check_near = total <= NEAR_DUP_LIMIT
    seen, kept, short, exact, near = set(), [], 0, 0, 0 if check_near else None
    index = {}  # trigram -> kept ids (posting lists); near-duplicate check against candidates only
    for r in rows:
        if len(r) < 3:
            continue
        sid, text = r[0], r[2]
        norm = normalise(text, arabic)
        words = [w for w in norm.split() if any(c.isalnum() for c in w)]
        if len(words) < 5:
            short += 1
            continue
        if norm in seen:
            exact += 1
            continue
        seen.add(norm)
        if check_near:
            grams = trigrams(words)
            cand = set()
            for g in grams:
                cand.update(index.get(g, ()))
            if any(len(grams & kept[i][1]) / len(grams | kept[i][1]) >= 0.8 for i in cand):
                near += 1
                continue
            for g in grams:
                index.setdefault(g, []).append(len(kept))
            kept.append((sid, grams))
        else:
            kept.append((sid, None))
    ids = {k[0] for k in kept}
    return {
        "sentences": total, "too_short": short, "exact_dup": exact, "near_dup": near, "eligible": len(kept),
        "with_en": len(ids & to_eng), "with_std": len(ids & to_std), "with_both": len(ids & to_eng & to_std),
    }


def wikt_counts(cats, cache):
    time.sleep(1)  # stay well under the API rate limit
    titles = "|".join("Category:" + c for c in cats)
    data = get(f"{WIKT}?action=query&prop=categoryinfo&format=json&formatversion=2&titles={urllib.parse.quote(titles)}", cache)
    pages = json.loads(data)["query"]["pages"]
    out = {}
    for p in pages:
        name = p["title"].removeprefix("Category:")
        out[name] = None if p.get("missing") else p.get("categoryinfo", {}).get("pages", 0)
    return out


HEADER = ("| Dialect | Name | Tatoeba code | Sentences | >=5 words, unique | near-dup removed | with EN | with standard "
          "| with both | Wiktionary |\n|---|---|---|---:|---:|---:|---:|---:|---:|---|")


def table_row(r):
    c = r.get("counts")
    if "error" in r:
        nums = ["error"] * 6
    elif c:
        nums = [c["sentences"], c["eligible"], "not checked" if c["near_dup"] is None else c["near_dup"],
                c["with_en"], c["with_std"], c["with_both"]]
    else:
        nums = ["-"] * 6
    w = "; ".join(f"{k}: {'missing' if v is None else v}" for k, v in r.get("wiktionary", {}).items()) or "-"
    return f"| {r['dialect']} | {r['name']} | {r['tatoeba'] or 'not tagged'} | " + " | ".join(map(str, nums)) + f" | {w} |"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--cache", default=os.path.join(os.environ.get("RUNNER_TEMP", "/tmp"), "tm-cache"))
    a = ap.parse_args()
    os.makedirs(a.cache, exist_ok=True)
    # Dialects first (small, the point of this inventory), then the large standard languages.
    jobs = [(lang, std, d) for lang, (std, ds) in DIALECTS.items() for d in ds]
    jobs += [(lang, std, (lang, std, "standard")) for lang, (std, _) in DIALECTS.items()]
    result, failures = [], []
    print(HEADER, flush=True)
    for lang, std, (tag, code, name) in jobs:
        started = time.time()
        row = {"lang": lang, "dialect": tag, "name": name, "tatoeba": code}
        if code:
            try:
                row["counts"] = tatoeba(code, std, lang == "ar", a.cache)
            except Exception as e:  # report and continue: one host hiccup must not hide the rest
                failures.append(f"{tag}: {e}")
                row["error"] = str(e)
        if tag in WIKT_CATS:
            try:
                row["wiktionary"] = wikt_counts(WIKT_CATS[tag], a.cache)
            except Exception as e:
                failures.append(f"{tag} wiktionary: {e}")
        result.append(row)
        print(table_row(row), flush=True)
        print(f"done {tag} in {time.time() - started:.0f} s", file=sys.stderr, flush=True)
        json.dump({"generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "rows": result},
                  open(a.out, "w"), ensure_ascii=False, indent=1)
    if failures:
        print("\nFailures:\n" + "\n".join(failures))
        sys.exit(1)


if __name__ == "__main__":
    main()
