#!/usr/bin/env python3
"""Islamic expressions and terms as glossary hints from Arabic into every app language. Runs in CI
([tm-build]); Wiktionary (CC BY-SA 4.0, already approved for the dialect corpora) is the only source.

- Arabic -> English: the first definition of the Arabic phrase's own Wiktionary entry.
- Arabic -> other languages: the translation table of the matching English Wiktionary entry
  (e.g. "inshallah" lists its translations in dozens of languages), restricted to the table whose
  heading matches the intended sense. Nothing is translated here; missing languages stay missing.
- Spelling variants people type (انشالله, ماشاء الله) are added on the Arabic side only.
Words with several everyday meanings (المغرب Morocco/sunset, العشاء dinner, العصر era, الظهر back/noon,
سنة year, أذان/آذان ears) are never listed as single words; the prayer names appear only as "صلاة ...".

Writes app/src/main/assets/glossary/ar-<lang>.islamic.tsv and prints a coverage table.
Usage: tm_islamic.py [--cache DIR]
"""
import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.join(os.path.dirname(__file__), "..")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "glossary")
API = "https://en.wiktionary.org/w/api.php"
UA = "ModuleX-Translator-glossary-builder/1 (CI; github.com/modulix0025-dev/android)"

# (Arabic phrase, spelling variants, English Wiktionary entry, words that must occur in the trans-top sense)
TERMS = [
    ("إن شاء الله", ["انشالله", "إنشاء الله", "ان شاء الله", "إن شاء اللّٰه"], "inshallah", []),
    ("ما شاء الله", ["ماشاء الله", "ماشالله"], "mashallah", []),
    ("الحمد لله", ["الحمدلله", "الحمد للّٰه"], "alhamdulillah", []),
    ("سبحان الله", ["سبحان اللّٰه"], "subhanallah", []),
    ("الله أكبر", ["اللّٰه أكبر"], "Allahu akbar", []),
    ("بسم الله", ["بسم اللّٰه"], "bismillah", []),
    ("بسم الله الرحمن الرحيم", [], "bismillah", []),
    ("أستغفر الله", ["استغفر الله"], "astaghfirullah", []),
    ("جزاك الله خيرا", ["جزاك الله خير", "جزاكم الله خيرا"], "jazakallah", []),
    ("السلام عليكم", ["السلام عليكم ورحمة الله", "السلام عليكم ورحمة الله وبركاته"], "as-salamu alaykum", []),
    ("وعليكم السلام", ["وعليكم السلام ورحمة الله"], "wa alaykum as-salam", []),
    ("صلى الله عليه وسلم", [], "peace be upon him", []),
    ("رحمه الله", ["الله يرحمه"], "rahimahullah", []),
    ("إنا لله وإنا إليه راجعون", ["انا لله وانا اليه راجعون"], "inna lillahi wa inna ilayhi raji'un", []),
    ("لا حول ولا قوة إلا بالله", [], "la hawla wa la quwwata illa billah", []),
    ("لا إله إلا الله", [], "la ilaha illallah", []),
    ("عيد مبارك", ["عيدكم مبارك"], "Eid Mubarak", []),
    ("رمضان كريم", [], "Ramadan Kareem", []),
    ("رمضان", [], "Ramadan", ["month"]),
    ("عيد الفطر", [], "Eid al-Fitr", []),
    ("عيد الأضحى", ["عيد الاضحى"], "Eid al-Adha", []),
    ("الحج", [], "hajj", []),
    ("العمرة", [], "umrah", []),
    ("الزكاة", [], "zakat", []),
    ("صدقة", [], "sadaqah", []),
    ("الوضوء", [], "wudu", []),
    ("إفطار", ["الإفطار"], "iftar", []),
    ("سحور", ["السحور"], "suhur", []),
    ("التراويح", ["صلاة التراويح"], "tarawih", []),
    ("ليلة القدر", [], "Laylat al-Qadr", []),
    ("القبلة", [], "qibla", []),
    ("الكعبة", [], "Kaaba", []),
    ("القرآن", ["القرآن الكريم"], "Quran", []),
    ("حلال", [], "halal", []),
    ("صلاة الفجر", [], "Fajr", []),
    ("صلاة الظهر", [], "Dhuhr", []),
    ("صلاة العصر", [], "Asr", []),
    ("صلاة المغرب", [], "Maghrib", []),
    ("صلاة العشاء", [], "Isha", []),
    ("صلاة الجمعة", [], "Jumu'ah", []),
    ("مسجد", ["المسجد"], "mosque", []),
]
NEVER = {"المغرب", "العشاء", "العصر", "الظهر", "سنة", "أذان", "آذان", "وقف", "عيش"}

# app language -> Wiktionary translation codes accepted for it
APP_LANGS = {
    "fr": ["fr"], "pt": ["pt"], "es": ["es"], "ja": ["ja"], "tr": ["tr"], "ru": ["ru"], "ko": ["ko"], "th": ["th"],
    "it": ["it"], "de": ["de"], "vi": ["vi"], "ms": ["ms"], "id": ["id"], "tl": ["tl"], "hi": ["hi"], "pl": ["pl"],
    "cs": ["cs"], "nl": ["nl"], "km": ["km"], "my": ["my"], "fa": ["fa"], "gu": ["gu"], "ur": ["ur"], "te": ["te"],
    "mr": ["mr"], "he": ["he"], "bn": ["bn"], "ta": ["ta"], "uk": ["uk"], "bo": ["bo"], "kk": ["kk"], "mn": ["mn"],
    "ug": ["ug"], "yue": ["yue"],
}
# Mandarin (cmn) is not mapped: translation tables mix traditional and simplified characters, and a
# wrong script would be worse than leaving zh / zh-Hant to the model.


def get(params, cache):
    url = API + "?" + urllib.parse.urlencode({**params, "format": "json", "formatversion": 2})
    name = os.path.join(cache, hashlib.sha256(url.encode()).hexdigest())
    if os.path.exists(name):
        return json.load(open(name, encoding="utf-8"))
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                data = json.loads(r.read())
            json.dump(data, open(name, "w", encoding="utf-8"))
            time.sleep(1)
            return data
        except urllib.error.HTTPError as e:
            time.sleep(15 * (attempt + 1) if e.code == 429 else 3)
        except (urllib.error.URLError, TimeoutError):
            time.sleep(3)
    raise RuntimeError(f"Wiktionary API failed: {params}")


def wikitext(title, cache):
    """Wikitext of an entry, following one redirect."""
    d = get({"action": "parse", "page": title, "prop": "wikitext", "redirects": 1}, cache)
    return d.get("parse", {}).get("wikitext", "")


def section(text, lang):
    m = re.search(rf"^=={re.escape(lang)}==\s*$(.*?)(?=^==[^=]|\Z)", text, re.S | re.M)
    return m.group(1) if m else ""


def plain(s):
    s = re.sub(r"\{\{(?:l|m|w|q|qualifier|gloss|lb|sense|ng|non-gloss)\|[^{}]*?\}\}", lambda m: "" if m.group(0).startswith(("{{lb", "{{q", "{{sense", "{{qualifier")) else m.group(0).split("|")[-1].rstrip("}"), s)
    s = re.sub(r"\{\{[^{}]*\}\}", "", s)
    s = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]+)\]\]", r"\1", s)
    s = re.sub(r"'''?|<[^>]+>", "", s)
    return " ".join(s.split()).strip(" ;,.")


def arabic_gloss(phrase, cache):
    ar = section(wikitext(phrase, cache), "Arabic")
    for line in ar.splitlines():
        if re.match(r"^#(?![:*])", line):
            g = plain(line.lstrip("#"))
            g = re.split(r"[;(]", g)[0].strip()
            if g and len(g.split()) <= 12:
                return g
    return None


def translations(entry, must, cache):
    """Wiktionary code -> first listed translation, from the trans-top table of the right sense."""
    en = section(wikitext(entry, cache), "English")
    out = {}
    for m in re.finditer(r"\{\{trans-top(?:\|([^}]*))?\}\}(.*?)\{\{trans-bottom\}\}", en, re.S):
        head = (m.group(1) or "").lower()
        if must and not any(w in head for w in must):
            continue
        for t in re.finditer(r"\{\{t\+?\|([a-z-]+)\|([^|}]+)", m.group(2)):
            code, word = t.group(1), plain(t.group(2))
            if word and code not in out:
                out[code] = word
        if out:
            break  # first matching sense table only
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=os.path.join(os.environ.get("RUNNER_TEMP", "/tmp"), "tm-islamic-cache"))
    a = ap.parse_args()
    os.makedirs(a.cache, exist_ok=True)
    rows = {lang: [] for lang in ["en"] + list(APP_LANGS)}
    missing = []
    for phrase, variants, entry, must in TERMS:
        assert phrase not in NEVER and not set(variants) & NEVER
        en = arabic_gloss(phrase, a.cache) or arabic_gloss(phrase.removeprefix("ال"), a.cache)
        tr = translations(entry, must, a.cache)
        if en:
            for v in [phrase] + variants:
                rows["en"].append([v, en])
        else:
            missing.append(f"{phrase}: no Arabic Wiktionary definition")
        for lang, codes in APP_LANGS.items():
            w = next((tr[c] for c in codes if c in tr), None)
            if w:
                for v in [phrase] + variants:
                    rows[lang].append([v, w])
        print(f"{phrase} ({entry}): en={en!r} languages={len([l for l in APP_LANGS if any(c in tr for c in APP_LANGS[l])])}", flush=True)
    header = ("# Islamic expressions and terms, Arabic -> {lang}, generated by tools/tm_islamic.py from Wiktionary\n"
              "# (CC BY-SA 4.0, Wiktionary contributors): the Arabic entry's definition for English, the English entry's\n"
              "# translation table for other languages. Do not edit by hand.\n")
    for lang, rs in rows.items():
        path = os.path.join(OUT, f"ar-{lang}.islamic.tsv")
        seen, keep = set(), []
        for src, tgt in rs:
            if src not in seen and "\t" not in tgt:
                seen.add(src)
                keep.append(f"{src}\t{tgt}")
        if keep:
            open(path, "w", encoding="utf-8").write(header.format(lang=lang) + "\n".join(keep) + "\n")
        elif os.path.exists(path):
            os.remove(path)
    print("\n| language | entries |\n|---|---:|")
    for lang, rs in rows.items():
        print(f"| ar-{lang} | {len({r[0] for r in rs})} |")
    if missing:
        print("\nNot found:\n" + "\n".join(missing))


if __name__ == "__main__":
    main()
