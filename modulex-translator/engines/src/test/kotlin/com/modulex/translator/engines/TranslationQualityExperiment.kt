package com.modulex.translator.engines

import com.modulex.translator.core.language.LanguageRegistry
import com.modulex.translator.core.translation.DecodingParams
import com.modulex.translator.core.translation.Glossary
import com.modulex.translator.core.translation.TranslationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Measurement, not a pass/fail test: how well a model file translates culture-, money- and dialect-
 * specific Arabic (and English) terms, with and without the app's glossary hints.
 *
 * Runs only with -Pmodulex.mtEvalModels=/a/Q4_K_M.gguf,/b/Q8_0.gguf (CI job `mt-eval`).
 * Every output is printed so the scoring can be audited.
 */
class TranslationQualityExperiment {

    /** [groups]: every group must be matched by at least one alternative (case-insensitive). */
    private data class Item(val dir: String, val src: String, val groups: List<List<String>>, val glossary: Boolean)

    private fun ar(src: String, vararg g: List<String>, glossary: Boolean = true) = Item("ar-en", src, g.toList(), glossary)
    private fun en(src: String, vararg g: List<String>, glossary: Boolean = true) = Item("en-ar", src, g.toList(), glossary)

    // glossary = false: no term in the sentence is covered by the experiment glossary (held out),
    // so those items measure the model/decoding/prompt alone.
    private val items = listOf(
        ar("الصدقة الجارية من أفضل الأعمال.", listOf("charity")),
        ar("سعر الكيلو بعشرين جنيه.", listOf("pound", "egp")),
        ar("معايا خمسين جنيه بس.", listOf("pound", "egp")),
        ar("الدولار وصل خمسين جنيه.", listOf("dollar"), listOf("pound", "egp")),
        ar("الإيجار ألفين جنيه في الشهر.", listOf("rent"), listOf("pound", "egp"), listOf("month")),
        ar("الزكاة واجبة على كل مسلم قادر.", listOf("zakat", "alms")),
        ar("صليت الفجر في المسجد.", listOf("dawn", "fajr"), listOf("mosque")),
        ar("عملت ملوخية على الغدا.", listOf("molokhia", "mulukhiyah", "molokhiya", "jute"), listOf("lunch")),
        ar("اشتريت فول وطعمية من المحل.", listOf("bean", "fava", "foul", "ful "), listOf("falafel", "taameya", "ta'meya")),
        ar("الأسانسير عطلان من امبارح.", listOf("elevator", "lift"), listOf("broken", "out of order", "not working", "down")),
        ar("سبت بقشيش للجرسون.", listOf("tip"), listOf("waiter")),
        ar("دفعت العربون للشقة.", listOf("deposit", "down payment"), listOf("apartment", "flat")),
        ar("قيمة المهر كانت كبيرة.", listOf("dowry", "mahr", "bride price")),
        ar("الكشري أشهر أكلة في مصر.", listOf("koshari", "koshary", "kushari"), listOf("egypt")),
        ar("ما معييش ولا قرش.", listOf("penny", "piastre", "cent", "dime", "money", "broke")),
        ar("العيش خلص، هات عيش وانت جاي.", listOf("bread")),
        ar("أنا صايم النهارده.", listOf("fasting")),
        ar("نوينا نعمل عمرة السنة الجاية.", listOf("umrah", "pilgrimage"), listOf("next year")),
        ar("الوقف الخيري بيصرف على المستشفى.", listOf("endowment", "waqf"), listOf("hospital")),
        ar("صلة الرحم واجبة.", listOf("kinship", "family ties", "relatives", "family relations", "ties of")),
        // Held out: no shipped glossary entry covers these (except where noted).
        ar("إن شاء الله هنتقابل بكرة.", listOf("god willing", "inshallah", "insha'allah", "hopefully"), listOf("tomorrow")),
        ar("الفاتورة كام؟", listOf("bill", "check", "invoice"), listOf("how much"), glossary = false),
        ar("المحفظة ضاعت مني في المترو.", listOf("wallet"), listOf("metro", "subway"), glossary = false),
        ar("عندي ميعاد مع الدكتور الساعة خمسة.", listOf("appointment"), listOf("doctor"), listOf("five", "5"), glossary = false),
        ar("عايز أحجز تذكرة قطر لإسكندرية.", listOf("train"), listOf("alexandria"), listOf("ticket")),
        ar("الشاي بالنعناع بيريح المعدة.", listOf("tea"), listOf("mint"), listOf("stomach"), glossary = false),
        ar("دفعت مية ريال في الفندق.", listOf("riyal"), listOf("hotel"), glossary = false),
        ar("الدرهم الإماراتي سعره ثابت.", listOf("dirham"), glossary = false),
        ar("الدينار الكويتي أغلى عملة.", listOf("dinar"), listOf("currency"), glossary = false),
        ar("رمضان كريم يا جماعة.", listOf("ramadan"), glossary = false),
        en("The ticket costs fifty Egyptian pounds.", listOf("جنيه"), listOf("تذكرة")),
        en("Ongoing charity keeps rewarding you after death.", listOf("صدقة جارية", "الصدقة الجارية")),
        en("I need to go to the pharmacy.", listOf("صيدلية"), glossary = false),
        en("The elevator is out of order.", listOf("مصعد", "أسانسير", "المصعد"), listOf("معطل", "عطلان", "لا يعمل"), glossary = false),
        en("Please bring me the bill.", listOf("فاتورة", "الحساب"), glossary = false),
        en("He lost his wallet on the train.", listOf("محفظة", "محفظته"), listOf("قطار"), glossary = false),
    )

    /** The glossary shipped in the app (assets/glossary), i.e. exactly what the app sends. */
    private val glossary: Glossary by lazy {
        val dir = File("../app/src/main/assets/glossary")
        Glossary(dir.listFiles { f -> f.name.endsWith(".tsv") }!!.flatMap { f ->
            val (from, to) = f.name.removeSuffix(".tsv").split("-")
            Glossary.parseTsv(f.readText(), from, to)
        })
    }

    private val diacritics = Regex("[\\u064B-\\u0652\\u0670\\u0640]")
    private fun norm(s: String) = s.replace(diacritics, "").replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('’', '\'').replace('‘', '\'').lowercase()

    private fun score(item: Item, out: String): Boolean {
        val o = norm(out)
        return item.groups.all { g -> g.any { o.contains(norm(it)) } }
    }

    private val arabicLetters = Regex("[\\u0600-\\u06FF]")

    @Test
    fun compareModelsWithAndWithoutTheGlossary() = runBlocking<Unit> {
        val models = System.getProperty("modulex.mtEvalModels")?.split(",")?.map { it.trim() }?.filter { File(it).isFile }.orEmpty()
        assumeTrue("set -Pmodulex.mtEvalModels", models.isNotEmpty())
        val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
        val summary = ArrayList<String>()
        for (model in models) {
            // The production path: LocalTranslationEngine with the app's default decoding parameters.
            val engine = LocalTranslationEngine(expectedArchitecture = null)
            engine.load(model, threads, 2048).getOrThrow()
            for (gloss in listOf(false, true)) {
                engine.glossary = if (gloss) glossary else null
                val cfg = "${File(model).name.removeSuffix(".gguf")}/${if (gloss) "glossary" else "plain"}"
                var ok = 0; var okHeld = 0; var held = 0; var copied = 0; var ms = 0L
                for (item in items) {
                    val (from, to) = item.dir.split("-").map { LanguageRegistry.require(it) }
                    val t0 = System.nanoTime()
                    val out = when (val r = engine.translate(item.src, from, to, DecodingParams())) {
                        is TranslationResult.Success -> r.text
                        else -> "<$r>"
                    }
                    ms += (System.nanoTime() - t0) / 1_000_000
                    val good = score(item, out)
                    if (good) ok++
                    if (!item.glossary) { held++; if (good) okHeld++ }
                    if (item.dir == "ar-en" && arabicLetters.containsMatchIn(out)) copied++
                    println("MT [$cfg] ${if (good) "OK " else "BAD"} ${item.dir} | ${item.src} => $out")
                }
                val line = "MTSUMMARY $cfg: correct=$ok/${items.size} heldOut=$okHeld/$held arabicLeftInEnglish=$copied avgMs=${ms / items.size}"
                println(line); summary += line
            }
            engine.unload()
        }
        println("MTSUMMARY_BEGIN\n" + summary.joinToString("\n") + "\nMTSUMMARY_END")
    }
}
