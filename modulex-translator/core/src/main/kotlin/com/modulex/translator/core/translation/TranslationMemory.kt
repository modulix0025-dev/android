package com.modulex.translator.core.translation

import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Verified dialect sentence pairs shipped in the APK (assets tm/<dialect>.tsv, built and checked by
 * tools/tm_build.py). Used two ways before the model runs:
 *
 * - exact match: when the normalised input equals a stored sentence, its stored translation is the answer;
 * - retrieval: otherwise the most similar stored pairs (character 3-gram TF-IDF, cosine) are sent to the
 *   model as references in the authors' documented terminology form, next to the glossary hints.
 *
 * A lexical dialect classifier only narrows which corpus is searched; when it is unsure, every corpus of
 * the input language is searched.
 */
class TranslationMemory(entries: List<Entry>) {
    /**
     * [dialect] is the corpus tag (e.g. "ar-EG"); [translations] maps a target language code to the
     * stored translation (e.g. "ar" -> the MSA sentence, "en" -> English).
     */
    data class Entry(val id: String, val dialect: String, val source: String, val translations: Map<String, String>)

    data class Match(val entry: Entry, val score: Double)

    /** Inverted index of one language: per 3-gram, the entries containing it and their TF-IDF weights. */
    private class LangIndex(
        val entries: List<Entry>,
        val idf: Map<String, Double>,
        val postIdx: Map<String, IntArray>,
        val postW: Map<String, FloatArray>,
        val length: DoubleArray,
    )

    private val index: Map<String, LangIndex>
    private val exact: Map<Pair<String, String>, Entry>
    private val dialectModel: Map<String, DialectModel>

    val size: Int = entries.size

    init {
        val grouped = entries.groupBy { baseLang(it.dialect) }
        val idx = HashMap<String, LangIndex>()
        val ex = HashMap<Pair<String, String>, Entry>()
        for ((lang, list) in grouped) {
            val norms = list.map { normalise(it.source, lang) }
            val grams = norms.map { ngrams(it) }
            val df = HashMap<String, Int>()
            grams.forEach { g -> g.keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
            val n = list.size.toDouble()
            val langIdf = df.mapValues { (_, d) -> ln((n + 1) / (d + 1)) + 1 }
            val lists = HashMap<String, MutableList<Pair<Int, Float>>>()
            val length = DoubleArray(list.size)
            grams.forEachIndexed { i, g ->
                var sq = 0.0
                for ((gram, tf) in g) {
                    val w = (1 + ln(tf.toDouble())) * langIdf.getValue(gram)
                    sq += w * w
                    lists.getOrPut(gram) { ArrayList() }.add(i to w.toFloat())
                }
                length[i] = sqrt(sq)
            }
            idx[lang] = LangIndex(list, langIdf, lists.mapValues { l -> IntArray(l.value.size) { l.value[it].first } },
                lists.mapValues { l -> FloatArray(l.value.size) { l.value[it].second } }, length)
            list.forEachIndexed { i, e -> ex.putIfAbsent(lang to norms[i], e) }
        }
        index = idx; exact = ex
        dialectModel = grouped.mapValues { (lang, list) -> DialectModel(list, lang) }
    }

    /** The stored entry whose normalised source equals [text], if it has a translation into [targetLang]. */
    fun exactMatch(text: String, sourceLang: String, targetLang: String): Entry? =
        exact[sourceLang to normalise(text, sourceLang)]?.takeIf { it.translations[targetLang].isNullOrBlank().not() }

    /** Most likely corpus for [text] among the dialects of [sourceLang], or null when the evidence is weak. */
    fun detectDialect(text: String, sourceLang: String): String? = dialectModel[sourceLang]?.classify(normalise(text, sourceLang))

    /**
     * Up to [k] stored pairs most similar to [text] that have a translation into [targetLang] and a cosine
     * score of at least [minScore]. Searches the detected dialect first, all dialects of the language when
     * detection is unsure.
     */
    fun similar(text: String, sourceLang: String, targetLang: String, k: Int = 3, minScore: Double = 0.3): List<Match> {
        val li = index[sourceLang] ?: return emptyList()
        val q = ngrams(normalise(text, sourceLang)).mapNotNull { (g, tf) -> li.idf[g]?.let { g to (1 + ln(tf.toDouble())) * it } }
        if (q.isEmpty()) return emptyList()
        val qLen = sqrt(q.sumOf { it.second * it.second })
        val dot = DoubleArray(li.entries.size)
        for ((g, wq) in q) {
            val ids = li.postIdx.getValue(g); val ws = li.postW.getValue(g)
            for (j in ids.indices) dot[ids[j]] += wq * ws[j]
        }
        val dialect = detectDialect(text, sourceLang)
        fun ranked(onlyDialect: String?): List<Match> {
            val best = ArrayList<Match>(k + 1)
            for (i in dot.indices) {
                if (dot[i] == 0.0) continue
                val score = dot[i] / (qLen * li.length[i])
                if (score < minScore || (best.size == k && score <= best.last().score)) continue
                val e = li.entries[i]
                if ((onlyDialect != null && e.dialect != onlyDialect) || e.translations[targetLang].isNullOrBlank()) continue
                best += Match(e, score)
                best.sortByDescending { it.score }
                if (best.size > k) best.removeAt(best.lastIndex)
            }
            return best
        }
        return dialect?.let { ranked(it) }?.takeIf { it.isNotEmpty() } ?: ranked(null)
    }

    /**
     * References for the prompt: the stored pairs most similar to [text], as source -> target hints, each at
     * most [maxChars] long on either side so the prompt stays within the context budget.
     */
    fun references(text: String, sourceLang: String, targetLang: String, k: Int = 3, maxChars: Int = 200): List<Glossary.Hint> =
        similar(text, sourceLang, targetLang, k)
            .map { Glossary.Hint(it.entry.source, it.entry.translations.getValue(targetLang)) }
            .filter { it.source.length <= maxChars && it.target.length <= maxChars }

    /** Unigram naive Bayes over the corpus words of each dialect of one language. */
    private class DialectModel(entries: List<Entry>, private val lang: String) {
        private val counts: Map<String, Map<String, Int>>
        private val totals: Map<String, Int>
        private val vocab: Int

        init {
            val c = HashMap<String, HashMap<String, Int>>()
            for (e in entries) {
                val m = c.getOrPut(e.dialect) { HashMap() }
                normalise(e.source, lang).split(' ').filter { it.isNotEmpty() }.forEach { m[it] = (m[it] ?: 0) + 1 }
            }
            counts = c; totals = c.mapValues { it.value.values.sum() }
            vocab = c.values.flatMap { it.keys }.toSet().size
        }

        fun classify(norm: String): String? {
            if (counts.size < 2) return counts.keys.firstOrNull()
            // Only words seen in some corpus carry evidence (unseen words would favour small corpora). Light
            // smoothing; the winner must lead by ln(20) ≈ 3.
            val words = norm.split(' ').filter { w -> w.isNotEmpty() && counts.values.any { w in it } }
            if (words.isEmpty()) return null
            val scores = counts.keys.associateWith { d ->
                val m = counts.getValue(d); val t = totals.getValue(d).toDouble()
                words.sumOf { ln(((m[it] ?: 0) + ALPHA) / (t + ALPHA * vocab)) }
            }.entries.sortedByDescending { it.value }
            return if (scores[0].value - scores[1].value >= 3.0) scores[0].key else null
        }

        private companion object { const val ALPHA = 0.1 }
    }

    companion object {
        private val arabicMarks = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]")
        private val punct = Regex("[^\\p{L}\\p{N}\\p{M}_\\s]")
        private val combining = Regex("\\p{Mn}+")

        fun baseLang(dialect: String): String = dialect.substringBefore('-')

        /**
         * Corpus normal form, identical to tools/tm_inventory.py `normalise`: NFKC, lower case, punctuation
         * removed, whitespace collapsed; Arabic without diacritics/tatweel and with alef/yaa/hamza forms
         * unified; other scripts without combining accents.
         */
        fun normalise(s: String, lang: String): String {
            var t = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase().replace("ß", "ss") // = Python casefold for these scripts
            if (lang == "ar") {
                t = t.replace(arabicMarks, "")
                    .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
                    .replace('ى', 'ي').replace('ئ', 'ي').replace('ؤ', 'و').replace("ء", "")
                t = t.replace(punct, " ")
            } else {
                t = t.replace(punct, " ")
                t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(combining, "")
            }
            return t.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        }

        /** Character 3-grams of " text " (word boundaries count), with counts. */
        private fun ngrams(norm: String): Map<String, Int> {
            if (norm.isEmpty()) return emptyMap()
            val s = " $norm "
            val m = HashMap<String, Int>()
            for (i in 0..s.length - 3) { val g = s.substring(i, i + 3); m[g] = (m[g] ?: 0) + 1 }
            return m
        }

        /**
         * Parses a tm/<dialect>.tsv file: header `id dialect source standard en terms domain provenance license`.
         * `standard` is stored as the translation into [standardLang] (MSA for Arabic dialects), `en` as the
         * translation into [enLang] (English; Arabic for English dialects).
         */
        fun parseTsv(text: String, standardLang: String, enLang: String = "en"): List<Entry> {
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty()) return emptyList()
            val header = lines.first().split('\t')
            fun col(name: String) = header.indexOf(name).also { require(it >= 0) { "tm file without column $name" } }
            val id = col("id"); val dialect = col("dialect"); val source = col("source"); val standard = col("standard"); val en = col("en")
            return lines.drop(1).map { line ->
                val f = line.split('\t')
                val tr = buildMap {
                    f.getOrNull(standard)?.takeIf { it.isNotBlank() }?.let { put(standardLang, it) }
                    f.getOrNull(en)?.takeIf { it.isNotBlank() }?.let { put(enLang, it) }
                }
                Entry(f[id], f[dialect], f[source], tr)
            }
        }
    }
}
