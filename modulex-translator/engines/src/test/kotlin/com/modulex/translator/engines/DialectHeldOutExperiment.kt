package com.modulex.translator.engines

import com.modulex.translator.core.language.LanguageRegistry
import com.modulex.translator.core.translation.DecodingParams
import com.modulex.translator.core.translation.Glossary
import com.modulex.translator.core.translation.TranslationMemory
import com.modulex.translator.core.translation.TranslationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.math.min
import kotlin.test.Test

/**
 * Measurement, not a pass/fail test: does the shipped dialect corpus help on pairs it has never seen?
 *
 * The 10% held-out split (engines/src/test/resources/tm-heldout, never shipped, never a reference) is
 * translated dialect -> English three ways with the production engine: plain, glossary only, glossary +
 * corpus retrieval. Scores: chrF (character 6-grams, beta 2) against the stored English, and keyword
 * recall (share of the reference's content words of 4+ letters found in the output). Every output is
 * printed. Runs only with -Pmodulex.mtEvalModels (CI job `mt-eval`).
 */
class DialectHeldOutExperiment {
    private val perDialect = (System.getProperty("modulex.tmEvalPerDialect") ?: "40").toInt()

    private fun shipped(dir: String): List<TranslationMemory.Entry> =
        File(dir).listFiles { f -> f.name.endsWith(".tsv") }.orEmpty().flatMap { f ->
            val lang = TranslationMemory.baseLang(f.name.removeSuffix(".tsv"))
            TranslationMemory.parseTsv(f.readText(), lang, if (lang == "en") "ar" else "en")
        }

    private val glossary: Glossary by lazy {
        Glossary(File("../app/src/main/assets/glossary").listFiles { f -> f.name.endsWith(".tsv") }!!.flatMap { f ->
            val (from, to) = f.name.removeSuffix(".tsv").split("-")
            Glossary.parseTsv(f.readText(), from, to)
        })
    }

    @Test
    fun plainVersusGlossaryVersusRetrieval() = runBlocking<Unit> {
        val model = System.getProperty("modulex.mtEvalModels")?.split(",")?.map { it.trim() }?.firstOrNull { File(it).isFile }
        assumeTrue("set -Pmodulex.mtEvalModels", model != null)
        val memory = TranslationMemory(shipped("../app/src/main/assets/tm"))
        val heldout = shipped("src/test/resources/tm-heldout").filter { TranslationMemory.baseLang(it.dialect) == "ar" }
        assumeTrue("no held-out pairs", heldout.isNotEmpty())
        val engine = LocalTranslationEngine(expectedArchitecture = null)
        engine.load(model!!, Runtime.getRuntime().availableProcessors().coerceAtMost(4), 2048).getOrThrow()
        val ar = LanguageRegistry.require("ar"); val en = LanguageRegistry.require("en")
        val summary = ArrayList<String>()
        for ((dialect, items) in heldout.groupBy { it.dialect }.toSortedMap()) {
            val sample = items.sortedBy { it.id }.take(perDialect)
            for (cfg in listOf("plain", "glossary", "glossary+retrieval")) {
                engine.glossary = if (cfg == "plain") null else glossary
                engine.memory = if (cfg == "glossary+retrieval") memory else null
                var chrf = 0.0; var kw = 0.0; var withRefs = 0
                for (e in sample) {
                    val ref = e.translations.getValue("en")
                    if (cfg == "glossary+retrieval" && memory.references(e.source, "ar", "en").isNotEmpty()) withRefs++
                    val out = when (val r = engine.translate(e.source, ar, en, DecodingParams())) {
                        is TranslationResult.Success -> r.text
                        else -> "<$r>"
                    }
                    val c = chrF(out, ref); val k = keywordRecall(out, ref)
                    chrf += c; kw += k
                    println("TM [$dialect/$cfg] chrF=%.1f kw=%.2f | ${e.source} => $out || ref: $ref".format(c, k))
                }
                val line = "TMSUMMARY $dialect $cfg: n=${sample.size} chrF=%.1f keywordRecall=%.3f withReferences=$withRefs"
                    .format(chrf / sample.size, kw / sample.size)
                println(line); summary += line
            }
        }
        engine.unload()
        println("TMSUMMARY_BEGIN\n" + summary.joinToString("\n") + "\nTMSUMMARY_END")
    }

    companion object {
        private val stop = setOf("that", "this", "with", "have", "from", "they", "will", "what", "were", "your", "there",
            "their", "would", "about", "when", "been", "into", "than", "then", "them", "very", "just", "like")

        fun keywordRecall(out: String, ref: String): Double {
            val words = { s: String -> s.lowercase().split(Regex("[^\\p{L}']+")).filter { it.length >= 4 && it !in stop }.toSet() }
            val r = words(ref); if (r.isEmpty()) return 1.0
            return (r intersect words(out)).size.toDouble() / r.size
        }

        /** Sentence chrF (Popović 2015): character n-grams 1..6 without spaces, beta = 2, scaled to 0..100. */
        fun chrF(hyp: String, ref: String, n: Int = 6, beta: Double = 2.0): Double {
            val h = hyp.lowercase().replace(Regex("\\s+"), ""); val r = ref.lowercase().replace(Regex("\\s+"), "")
            var p = 0.0; var rc = 0.0; var k = 0
            for (order in 1..n) {
                val hg = grams(h, order); val rg = grams(r, order)
                if (hg.isEmpty() || rg.isEmpty()) continue
                val match = hg.entries.sumOf { (g, c) -> min(c, rg[g] ?: 0) }.toDouble()
                p += match / hg.values.sum(); rc += match / rg.values.sum(); k++
            }
            if (k == 0) return 0.0
            p /= k; rc /= k
            val b2 = beta * beta
            return if (p + rc == 0.0) 0.0 else 100 * (1 + b2) * p * rc / (b2 * p + rc)
        }

        private fun grams(s: String, n: Int): Map<String, Int> {
            val m = HashMap<String, Int>()
            for (i in 0..s.length - n) { val g = s.substring(i, i + n); m[g] = (m[g] ?: 0) + 1 }
            return m
        }
    }
}
