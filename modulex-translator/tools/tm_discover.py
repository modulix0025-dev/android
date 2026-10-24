#!/usr/bin/env python3
"""Find more dialect sources and print the evidence needed to judge them. Runs in CI ([tm-discover]).

- Hugging Face: every dataset tagged with a dialect language code, with its declared licence, gating,
  task tags and the licence-related lines of its card (public cards only; nothing gated is read).
- Wikivoyage: the Arabic-dialect phrasebooks (CC BY-SA), with a count of phrases written in Arabic script.
- Wikidata lexemes (CC0): lexemes, senses with an English gloss, and usage examples per dialect.

Nothing is downloaded beyond metadata and samples; nothing is approved here.
Usage: tm_discover.py [--out report.json]
"""
import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

UA = "ModuleX-Translator-corpus-discovery/1 (CI; github.com/modulix0025-dev/android)"
CODES = ["arz", "apc", "ajp", "acm", "ary", "aeb", "arq", "afb", "ars", "acw", "apd", "ayl", "acq", "shu", "abv"]
COMMERCIAL_OK = {"cc0-1.0", "cc-by-4.0", "cc-by-3.0", "cc-by-2.0", "cc-by-sa-4.0", "cc-by-sa-3.0", "apache-2.0", "mit",
                 "odc-by", "pddl", "bsd-3-clause", "bsd-2-clause", "unlicense", "cdla-permissive-2.0", "odbl"}
LICENCE_WORDS = re.compile(r"licen[cs]e|commercial|cc[- ]by|creative commons|copyright|subtitle|lyrics|scraped|"
                           r"twitter|tweets|youtube|machine[- ]translat|gpt|synthetic|generated", re.I)


def get(url, retries=3, headers=None):
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code in (401, 403, 404):
                return None
            time.sleep(5 * (attempt + 1))
        except (urllib.error.URLError, TimeoutError):
            time.sleep(5 * (attempt + 1))
    return None


def hf(report):
    seen = {}
    for code in CODES:
        data = get(f"https://huggingface.co/api/datasets?filter=language:{code}&limit=200&full=true")
        for d in json.loads(data or b"[]"):
            e = seen.setdefault(d["id"], {"codes": [], "tags": d.get("tags", []), "gated": d.get("gated"),
                                         "downloads": d.get("downloads"), "license": None})
            e["codes"].append(code)
            lic = [t.split(":", 1)[1] for t in d.get("tags", []) if t.startswith("license:")]
            e["license"] = lic[0] if lic else (d.get("cardData") or {}).get("license")
    print(f"\n=== HUGGING FACE: {len(seen)} datasets tagged with a dialect code ===")
    rows = sorted(seen.items(), key=lambda kv: -(kv[1]["downloads"] or 0))
    for name, e in rows:
        lic = e["license"] if isinstance(e["license"], str) else json.dumps(e["license"])
        ok = "OK-LICENCE" if str(lic).lower() in COMMERCIAL_OK else "excluded-licence" if lic else "no-licence"
        tasks = [t.split(":", 1)[1] for t in e["tags"] if t.startswith("task_categories:")]
        print(f"{ok:17} gated={e['gated']!s:5} dl={e['downloads']!s:>7} {name}  licence={lic} codes={','.join(e['codes'])} tasks={','.join(tasks)}")
        if ok == "OK-LICENCE" and not e["gated"]:
            card = get(f"https://huggingface.co/datasets/{name}/raw/main/README.md")
            text = (card or b"").decode("utf-8", "replace")
            lines = [" ".join(l.split()) for l in text.splitlines() if LICENCE_WORDS.search(l)]
            for l in lines[:12]:
                print("      | " + l[:300])
            files = get(f"https://huggingface.co/api/datasets/{name}/tree/main?recursive=true")
            names = [f["path"] for f in json.loads(files or b"[]") if f.get("type") == "file"][:15]
            print(f"      files: {names}")
    report["huggingface"] = seen


def wikivoyage(report):
    print("\n=== WIKIVOYAGE phrasebooks (CC BY-SA) ===")
    api = "https://en.wikivoyage.org/w/api.php"
    res = json.loads(get(f"{api}?action=query&list=search&srsearch={urllib.parse.quote('Arabic phrasebook')}&srlimit=50&format=json") or b"{}")
    titles = [r["title"] for r in res.get("query", {}).get("search", []) if "phrasebook" in r["title"].lower()]
    out = {}
    for t in titles:
        page = json.loads(get(f"{api}?action=parse&page={urllib.parse.quote(t)}&prop=wikitext&format=json&formatversion=2") or b"{}")
        text = page.get("parse", {}).get("wikitext", "")
        phrases = [l for l in text.splitlines() if l.startswith(";") or l.startswith(":")]
        arabic = [l for l in phrases if re.search("[؀-ۿ]", l)]
        out[t] = {"chars": len(text), "phrase_lines": len(phrases), "with_arabic_script": len(arabic)}
        print(f"{t}: {len(text)} chars, {len(phrases)} phrase lines, {len(arabic)} with Arabic script")
        for l in (arabic or phrases)[:6]:
            print("      | " + l[:200])
    report["wikivoyage"] = out


def wikidata(report):
    print("\n=== WIKIDATA lexemes (CC0) ===")
    q = """SELECT ?code (COUNT(DISTINCT ?l) AS ?lexemes) (COUNT(DISTINCT ?s) AS ?enSenses) (COUNT(DISTINCT ?ex) AS ?examples) WHERE {
      VALUES ?code { %s }
      ?lang wdt:P220 ?code . ?l dct:language ?lang .
      OPTIONAL { ?l ontolex:sense ?s . ?s skos:definition ?g . FILTER(LANG(?g) = "en") }
      OPTIONAL { ?l p:P5831 ?ex }
    } GROUP BY ?code""" % " ".join(f'"{c}"' for c in CODES)
    data = get("https://query.wikidata.org/sparql?format=json&query=" + urllib.parse.quote(q),
               headers={"Accept": "application/sparql-results+json"})
    rows = json.loads(data or b"{}").get("results", {}).get("bindings", [])
    out = {}
    for r in rows:
        out[r["code"]["value"]] = {k: int(r[k]["value"]) for k in ("lexemes", "enSenses", "examples")}
        print(f"{r['code']['value']}: {out[r['code']['value']]}")
    if not rows:
        print("no result (query failed or no lexemes)")
    report["wikidata"] = out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="tm_discover.json")
    a = ap.parse_args()
    report = {}
    for step in (hf, wikivoyage, wikidata):
        try:
            step(report)
        except Exception as e:  # keep going: each section is independent
            print(f"{step.__name__} failed: {type(e).__name__}: {e}", file=sys.stderr)
    json.dump(report, open(a.out, "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
