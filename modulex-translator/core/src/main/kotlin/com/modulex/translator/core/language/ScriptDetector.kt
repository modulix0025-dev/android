package com.modulex.translator.core.language

/**
 * Writing-system based source detection.
 *
 * This is intentionally conservative: it never guesses between two languages that share a script
 * (e.g. English vs French, Arabic vs Persian). It is used to pick the direction inside a known
 * language pair, which is the only case where script detection is reliable.
 */
object ScriptDetector {

    data class ScriptHistogram(val counts: Map<Script, Int>) {
        val total: Int = counts.values.sum()
        fun share(script: Script): Double = if (total == 0) 0.0 else (counts[script] ?: 0).toDouble() / total
        val dominant: Script? get() = counts.maxByOrNull { it.value }?.key
    }

    fun histogram(text: String): ScriptHistogram {
        val counts = HashMap<Script, Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            scriptOf(cp)?.let { counts[it] = (counts[it] ?: 0) + 1 }
            i += Character.charCount(cp)
        }
        return ScriptHistogram(counts)
    }

    fun scriptOf(cp: Int): Script? {
        if (!Character.isLetter(cp)) return null
        return when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.LATIN -> Script.LATIN
            Character.UnicodeScript.ARABIC -> Script.ARABIC
            Character.UnicodeScript.CYRILLIC -> Script.CYRILLIC
            Character.UnicodeScript.HAN -> Script.HAN
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Script.KANA
            Character.UnicodeScript.HANGUL -> Script.HANGUL
            Character.UnicodeScript.THAI -> Script.THAI
            Character.UnicodeScript.DEVANAGARI -> Script.DEVANAGARI
            Character.UnicodeScript.HEBREW -> Script.HEBREW
            Character.UnicodeScript.KHMER -> Script.KHMER
            Character.UnicodeScript.MYANMAR -> Script.MYANMAR
            Character.UnicodeScript.GUJARATI -> Script.GUJARATI
            Character.UnicodeScript.TELUGU -> Script.TELUGU
            Character.UnicodeScript.BENGALI -> Script.BENGALI
            Character.UnicodeScript.TAMIL -> Script.TAMIL
            Character.UnicodeScript.TIBETAN -> Script.TIBETAN
            Character.UnicodeScript.MONGOLIAN -> Script.MONGOLIAN
            else -> null
        }
    }

    /** Result of choosing a side of a language pair. */
    sealed interface PairDecision {
        data class Detected(val language: Language, val confidence: Double) : PairDecision
        /** Both languages share a script or the text is inconclusive: ask the user. */
        data object Ambiguous : PairDecision
    }

    /**
     * Decide which of [a] or [b] the [text] is written in. Japanese text containing kana counts as
     * Japanese even with Han characters mixed in.
     */
    fun decideWithinPair(text: String, a: Language, b: Language, minShare: Double = 0.6): PairDecision {
        if (a.script == b.script) return PairDecision.Ambiguous
        val h = histogram(text)
        if (h.total < 2) return PairDecision.Ambiguous
        fun score(l: Language): Double = when (l.script) {
            Script.KANA -> h.share(Script.KANA) + h.share(Script.HAN) * if (h.share(Script.KANA) > 0.05) 1.0 else 0.0
            else -> h.share(l.script)
        }
        val sa = score(a)
        val sb = score(b)
        return when {
            sa >= minShare && sa > sb -> PairDecision.Detected(a, sa)
            sb >= minShare && sb > sa -> PairDecision.Detected(b, sb)
            else -> PairDecision.Ambiguous
        }
    }

    /**
     * Detects a source language only when the dominant writing system is used by exactly one
     * supported language (e.g. Hangul -> Korean, Thai -> Thai). Returns null for shared scripts
     * (Latin, Arabic, Cyrillic, Han, Devanagari) where script alone cannot decide.
     */
    fun detectUnique(text: String, minShare: Double = 0.6): Language? {
        val h = histogram(text)
        if (h.total < 2) return null
        val dom = h.dominant ?: return null
        // Kana anywhere means Japanese, even when Han characters dominate.
        if (h.share(Script.KANA) > 0.05) return LanguageRegistry.get("ja")
        if (h.share(dom) < minShare) return null
        return LanguageRegistry.all.filter { it.script == dom }.singleOrNull()
    }
}
