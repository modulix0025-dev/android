package com.modulex.translator.engines

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Measurement, not a pass/fail test: which model file, decoding and prompt form translate culture-
 * and money-specific Arabic (and English) terms instead of copying or transliterating them.
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
        // Held out (no glossary entry).
        ar("إن شاء الله هنتقابل بكرة.", listOf("god willing", "inshallah", "insha'allah", "hopefully"), listOf("tomorrow"), glossary = false),
        ar("الفاتورة كام؟", listOf("bill", "check", "invoice"), listOf("how much"), glossary = false),
        ar("المحفظة ضاعت مني في المترو.", listOf("wallet"), listOf("metro", "subway"), glossary = false),
        ar("عندي ميعاد مع الدكتور الساعة خمسة.", listOf("appointment"), listOf("doctor"), listOf("five", "5"), glossary = false),
        ar("عايز أحجز تذكرة قطر لإسكندرية.", listOf("train"), listOf("alexandria"), listOf("ticket"), glossary = false),
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

    /** Experiment glossary: Arabic surface forms (normalised) -> English rendering, and English -> Arabic. */
    private val glossaryArEn = linkedMapOf(
        "الصدقة الجارية" to "ongoing charity (sadaqah jariyah)", "صدقة جارية" to "ongoing charity (sadaqah jariyah)",
        "جنيه" to "pound (Egyptian pound)", "الزكاة" to "zakat (obligatory alms)", "زكاة" to "zakat (obligatory alms)",
        "الفجر" to "Fajr (dawn prayer)", "ملوخية" to "molokhia", "طعمية" to "falafel (ta'meya)", "فول" to "fava beans",
        "الأسانسير" to "the elevator", "بقشيش" to "tip", "الجرسون" to "the waiter", "للجرسون" to "to the waiter",
        "العربون" to "the deposit", "المهر" to "the dowry (mahr)", "الكشري" to "koshari", "قرش" to "piastre",
        "العيش" to "bread", "عيش" to "bread", "صايم" to "fasting", "عمرة" to "Umrah", "الوقف الخيري" to "the charitable endowment (waqf)",
        "صلة الرحم" to "maintaining family ties",
    )
    private val glossaryEnAr = linkedMapOf(
        "Egyptian pounds" to "جنيهات مصرية", "Egyptian pound" to "جنيه مصري", "Ongoing charity" to "الصدقة الجارية",
    )

    private val diacritics = Regex("[\\u064B-\\u0652\\u0670\\u0640]")
    private fun norm(s: String) = s.replace(diacritics, "").replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').lowercase()

    private fun terms(item: Item): List<Pair<String, String>> {
        val g = if (item.dir == "ar-en") glossaryArEn else glossaryEnAr
        val src = norm(item.src)
        val hits = g.entries.filter { src.contains(norm(it.key)) }
        // Prefer the longest overlapping surface forms ("الصدقة الجارية" over "جنيه" etc. is irrelevant, but
        // "Egyptian pounds" must win over "Egyptian pound").
        return hits.filter { h -> hits.none { o -> o !== h && norm(o.key).contains(norm(h.key)) } }.map { it.key to it.value }
    }

    private fun prompt(style: String, item: Item, withGlossary: Boolean): String {
        val targetEn = if (item.dir == "ar-en") "English" else "Arabic"
        val targetZh = if (item.dir == "ar-en") "英语" else "阿拉伯语"
        val t = if (withGlossary) terms(item) else emptyList()
        return when (style) {
            "en" -> buildString {
                if (t.isNotEmpty()) {
                    append("Refer to the following translations:\n")
                    t.forEach { (s, d) -> append("$s translates to $d\n") }
                    append("\n")
                }
                append("Translate the following text into $targetEn. Note that you should only output the translated result without any additional explanation:\n\n${item.src}")
            }
            "zh" -> buildString {
                if (t.isNotEmpty()) {
                    append("参考下面的翻译：\n")
                    t.forEach { (s, d) -> append("$s 翻译成 $d\n") }
                    append("\n")
                }
                append("将以下文本翻译为$targetZh，注意只需要输出翻译后的结果，不要额外解释：\n\n${item.src}")
            }
            else -> error(style)
        }
    }

    private fun score(item: Item, out: String): Boolean {
        val o = norm(out)
        return item.groups.all { g -> g.any { o.contains(norm(it)) } }
    }

    private val arabicLetters = Regex("[\\u0600-\\u06FF]")

    @Test
    fun compareModelsDecodingAndPrompts() = runBlocking<Unit> {
        val models = System.getProperty("modulex.mtEvalModels")?.split(",")?.map { it.trim() }?.filter { File(it).isFile }.orEmpty()
        assumeTrue("set -Pmodulex.mtEvalModels", models.isNotEmpty())
        val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
        val summary = ArrayList<String>()
        for (model in models) {
            val engine = LocalTranslationEngine()
            engine.load(model, threads, 2048).getOrThrow()
            val h = engine.handleForTest()
            println("FORMATTED_PROMPT_EXAMPLE ${File(model).name}:\n" + NativeTranslator.nativeFormatPrompt(h, prompt("zh", items[0], true)))
            for (decode in listOf("greedy", "sampled")) for (style in listOf("en", "zh")) for (gloss in listOf(false, true)) {
                val cfg = "${File(model).name.removePrefix("Hy-MT2-1.8B-").removeSuffix(".gguf")}/$decode/$style/${if (gloss) "glossary" else "plain"}"
                var ok = 0; var okHeld = 0; var held = 0; var copied = 0; var ms = 0L
                for (item in items) {
                    val p = NativeTranslator.nativeFormatPrompt(h, prompt(style, item, gloss))
                    val metrics = LongArray(4)
                    val t0 = System.nanoTime()
                    val (temp, topP, topK, rep) = if (decode == "greedy") listOf(0f, 1f, 0f, 1.0f) else listOf(0.7f, 0.6f, 20f, 1.05f)
                    val out = NativeTranslator.nativeGenerate(h, p, temp, topP, topK.toInt(), rep, 256, 42, null, metrics).trim()
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
