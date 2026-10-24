package com.modulex.translator.core.translation

/**
 * Terminology hints for the translation model: culture-, religion- and money-specific terms that a
 * small model tends to copy or transliterate (e.g. «جنيه» -> "Gneih" instead of "pound").
 *
 * The glossary is plain data (one TSV per language pair, see app assets glossary/). Matching is
 * done on normalised text so diacritics, alef/yaa spelling variants, Arabic clitic
 * prefixes (و ف ب ك ل ال) and common suffixes do not prevent a match, while a term is never matched
 * inside an unrelated longer word.
 */
class Glossary(entries: List<Entry>) {
    data class Entry(val sourceLang: String, val targetLang: String, val source: String, val target: String)

    data class Hint(val source: String, val target: String)

    private data class Compiled(val entry: Entry, val regex: Regex, val length: Int)

    private val byPair: Map<Pair<String, String>, List<Compiled>> = entries
        .groupBy { it.sourceLang to it.targetLang }
        .mapValues { (pair, list) ->
            list.map { Compiled(it, compile(normalise(it.source, pair.first), pair.first), it.source.length) }
                .sortedByDescending { it.length } // longest terms win over terms they contain
        }

    val size: Int = entries.size

    /**
     * Hints for the terms that occur in [text], longest first, without overlaps, at most [max].
     */
    fun hintsFor(text: String, sourceLang: String, targetLang: String, max: Int = 8): List<Hint> {
        val list = byPair[sourceLang to targetLang] ?: return emptyList()
        val norm = normalise(text, sourceLang)
        val taken = ArrayList<IntRange>()
        val out = ArrayList<Hint>()
        for (c in list) {
            val m = c.regex.findAll(norm).firstOrNull { r -> taken.none { it.first <= r.range.last && r.range.first <= it.last } } ?: continue
            taken += m.range
            if (out.none { it.source == c.entry.source }) out += Hint(c.entry.source, c.entry.target)
            if (out.size == max) break
        }
        return out
    }

    companion object {
        private val arabicMarks = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]")

        /**
         * Matching form: lower case; for Arabic no diacritics/tatweel and unified alef/yaa. Taa marbuta
         * stays distinct from haa, otherwise «عيشة» (life) would read as «عيش» + possessive «ه».
         */
        fun normalise(s: String, lang: String): String =
            if (lang == "ar") {
                s.replace(arabicMarks, "")
                    .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
                    .replace('ى', 'ي')
            } else {
                s.lowercase()
            }

        // Arabic: optional conjunction/preposition clitics and article before the term, and a short
        // list of inflectional/possessive endings after it. Anything else adjacent means another word.
        private const val AR_PREFIX = "(?<![\\p{L}])(?:و|ف)?(?:ب|ل|ك)?(?:ال|ل)?"
        private const val AR_SUFFIX = "(?:ات|ان|ين|ون|ا|ي|ه|ها|هم|هن|كم|نا|ك)?(?![\\p{L}])"

        private fun compile(term: String, lang: String): Regex {
            val body = Regex.escape(term).replace(" ", "\\E\\s+\\Q")
            return if (lang == "ar") {
                // A term that already starts with the article also matches without it after a clitic (والجنيه / بالجنيه).
                val bare = term.removePrefix("ال")
                val alt = if (bare != term && bare.length >= 2) "|" + Regex.escape(bare).replace(" ", "\\E\\s+\\Q") else ""
                Regex("$AR_PREFIX(?:$body$alt)$AR_SUFFIX")
            } else {
                Regex("(?<![\\p{L}])$body(?:s|es)?(?![\\p{L}])")
            }
        }

        /** Parses `source<TAB>target` lines; `#` starts a comment line. */
        fun parseTsv(text: String, sourceLang: String, targetLang: String): List<Entry> =
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
                    if (parts.size == 2) Entry(sourceLang, targetLang, parts[0], parts[1]) else null
                }
                .toList()
    }
}
