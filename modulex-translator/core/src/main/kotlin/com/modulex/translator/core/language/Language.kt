package com.modulex.translator.core.language

/**
 * Static description of a language and which engines *can* handle it.
 *
 * Capability flags describe what the engine families support in principle. Whether a feature is
 * actually usable on a device is decided at runtime by [LanguageReadiness], which also requires the
 * relevant asset packs to be installed and verified.
 */
data class Language(
    /** Canonical app code (BCP-47 style, e.g. "ar", "zh-Hant"). */
    val code: String,
    val englishName: String,
    val nativeName: String,
    val rtl: Boolean = false,
    /** Name used inside the translation prompt (English prompt template). */
    val promptNameEn: String = englishName,
    /** Name used inside the Chinese prompt template, required when Chinese is source or target. */
    val promptNameZh: String? = null,
    /** True when the translation model officially lists this language. */
    val translation: Boolean = true,
    /** Whisper language token, or null if Whisper has no token for it. */
    val whisperCode: String? = null,
    /** Tesseract traineddata names (without extension) needed to OCR this language. */
    val ocrPacks: List<String> = emptyList(),
    /** Writing system used for script-based direction detection. */
    val script: Script,
) {
    val speechRecognition: Boolean get() = whisperCode != null
    val ocr: Boolean get() = ocrPacks.isNotEmpty()
}

enum class Script { LATIN, ARABIC, CYRILLIC, HAN, KANA, HANGUL, THAI, DEVANAGARI, HEBREW, KHMER, MYANMAR,
    GUJARATI, TELUGU, BENGALI, TAMIL, TIBETAN, MONGOLIAN }

/**
 * Registry of the languages handled by the app. The list mirrors the 33 languages (+ variants)
 * documented for the translation model; ASR/OCR mappings come from the Whisper language table and
 * the tessdata_fast file list.
 */
object LanguageRegistry {

    val all: List<Language> = listOf(
        Language("en", "English", "English", promptNameZh = "英语", whisperCode = "en", ocrPacks = listOf("eng"), script = Script.LATIN),
        Language("ar", "Arabic", "العربية", rtl = true, promptNameZh = "阿拉伯语", whisperCode = "ar", ocrPacks = listOf("ara"), script = Script.ARABIC),
        Language("zh", "Chinese (Simplified)", "简体中文", promptNameEn = "Chinese", promptNameZh = "中文", whisperCode = "zh", ocrPacks = listOf("chi_sim"), script = Script.HAN),
        Language("zh-Hant", "Chinese (Traditional)", "繁體中文", promptNameEn = "Traditional Chinese", promptNameZh = "繁体中文", whisperCode = "zh", ocrPacks = listOf("chi_tra"), script = Script.HAN),
        Language("yue", "Cantonese", "粵語", promptNameZh = "粤语", whisperCode = "yue", ocrPacks = listOf("chi_tra"), script = Script.HAN),
        Language("fr", "French", "Français", promptNameZh = "法语", whisperCode = "fr", ocrPacks = listOf("fra"), script = Script.LATIN),
        Language("pt", "Portuguese", "Português", promptNameZh = "葡萄牙语", whisperCode = "pt", ocrPacks = listOf("por"), script = Script.LATIN),
        Language("es", "Spanish", "Español", promptNameZh = "西班牙语", whisperCode = "es", ocrPacks = listOf("spa"), script = Script.LATIN),
        Language("ja", "Japanese", "日本語", promptNameZh = "日语", whisperCode = "ja", ocrPacks = listOf("jpn"), script = Script.KANA),
        Language("tr", "Turkish", "Türkçe", promptNameZh = "土耳其语", whisperCode = "tr", ocrPacks = listOf("tur"), script = Script.LATIN),
        Language("ru", "Russian", "Русский", promptNameZh = "俄语", whisperCode = "ru", ocrPacks = listOf("rus"), script = Script.CYRILLIC),
        Language("ko", "Korean", "한국어", promptNameZh = "韩语", whisperCode = "ko", ocrPacks = listOf("kor"), script = Script.HANGUL),
        Language("th", "Thai", "ไทย", promptNameZh = "泰语", whisperCode = "th", ocrPacks = listOf("tha"), script = Script.THAI),
        Language("it", "Italian", "Italiano", promptNameZh = "意大利语", whisperCode = "it", ocrPacks = listOf("ita"), script = Script.LATIN),
        Language("de", "German", "Deutsch", promptNameZh = "德语", whisperCode = "de", ocrPacks = listOf("deu"), script = Script.LATIN),
        Language("vi", "Vietnamese", "Tiếng Việt", promptNameZh = "越南语", whisperCode = "vi", ocrPacks = listOf("vie"), script = Script.LATIN),
        Language("ms", "Malay", "Bahasa Melayu", promptNameZh = "马来语", whisperCode = "ms", ocrPacks = listOf("msa"), script = Script.LATIN),
        Language("id", "Indonesian", "Bahasa Indonesia", promptNameZh = "印尼语", whisperCode = "id", ocrPacks = listOf("ind"), script = Script.LATIN),
        Language("tl", "Filipino", "Filipino", promptNameZh = "菲律宾语", whisperCode = "tl", ocrPacks = listOf("fil"), script = Script.LATIN),
        Language("hi", "Hindi", "हिन्दी", promptNameZh = "印地语", whisperCode = "hi", ocrPacks = listOf("hin"), script = Script.DEVANAGARI),
        Language("pl", "Polish", "Polski", promptNameZh = "波兰语", whisperCode = "pl", ocrPacks = listOf("pol"), script = Script.LATIN),
        Language("cs", "Czech", "Čeština", promptNameZh = "捷克语", whisperCode = "cs", ocrPacks = listOf("ces"), script = Script.LATIN),
        Language("nl", "Dutch", "Nederlands", promptNameZh = "荷兰语", whisperCode = "nl", ocrPacks = listOf("nld"), script = Script.LATIN),
        Language("km", "Khmer", "ខ្មែរ", promptNameZh = "高棉语", whisperCode = "km", ocrPacks = listOf("khm"), script = Script.KHMER),
        Language("my", "Burmese", "မြန်မာ", promptNameZh = "缅甸语", whisperCode = "my", ocrPacks = listOf("mya"), script = Script.MYANMAR),
        Language("fa", "Persian", "فارسی", rtl = true, promptNameZh = "波斯语", whisperCode = "fa", ocrPacks = listOf("fas"), script = Script.ARABIC),
        Language("gu", "Gujarati", "ગુજરાતી", promptNameZh = "古吉拉特语", whisperCode = "gu", ocrPacks = listOf("guj"), script = Script.GUJARATI),
        Language("ur", "Urdu", "اردو", rtl = true, promptNameZh = "乌尔都语", whisperCode = "ur", ocrPacks = listOf("urd"), script = Script.ARABIC),
        Language("te", "Telugu", "తెలుగు", promptNameZh = "泰卢固语", whisperCode = "te", ocrPacks = listOf("tel"), script = Script.TELUGU),
        Language("mr", "Marathi", "मराठी", promptNameZh = "马拉地语", whisperCode = "mr", ocrPacks = listOf("mar"), script = Script.DEVANAGARI),
        Language("he", "Hebrew", "עברית", rtl = true, promptNameZh = "希伯来语", whisperCode = "he", ocrPacks = listOf("heb"), script = Script.HEBREW),
        Language("bn", "Bengali", "বাংলা", promptNameZh = "孟加拉语", whisperCode = "bn", ocrPacks = listOf("ben"), script = Script.BENGALI),
        Language("ta", "Tamil", "தமிழ்", promptNameZh = "泰米尔语", whisperCode = "ta", ocrPacks = listOf("tam"), script = Script.TAMIL),
        Language("uk", "Ukrainian", "Українська", promptNameZh = "乌克兰语", whisperCode = "uk", ocrPacks = listOf("ukr"), script = Script.CYRILLIC),
        Language("bo", "Tibetan", "བོད་ཡིག", promptNameZh = "藏语", whisperCode = "bo", ocrPacks = listOf("bod"), script = Script.TIBETAN),
        Language("kk", "Kazakh", "Қазақ тілі", promptNameZh = "哈萨克语", whisperCode = "kk", ocrPacks = listOf("kaz"), script = Script.CYRILLIC),
        Language("mn", "Mongolian", "Монгол", promptNameZh = "蒙古语", whisperCode = "mn", ocrPacks = listOf("mon"), script = Script.CYRILLIC),
        Language("ug", "Uyghur", "ئۇيغۇرچە", rtl = true, promptNameZh = "维吾尔语", whisperCode = null, ocrPacks = listOf("uig"), script = Script.ARABIC),
    )

    private val byCode = all.associateBy { it.code }

    fun get(code: String): Language? = byCode[code]

    fun require(code: String): Language =
        byCode[code] ?: throw IllegalArgumentException("Unsupported language code: $code")

    val chineseFamily = setOf("zh", "zh-Hant", "yue")
}
