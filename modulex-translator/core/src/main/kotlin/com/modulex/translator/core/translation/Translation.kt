package com.modulex.translator.core.translation

import com.modulex.translator.core.language.Language
import com.modulex.translator.core.language.LanguageRegistry

/**
 * Builds the user turn for the translation model, following the prompt formats documented by the
 * model authors (English template, or the Chinese template when Chinese is involved). The chat
 * template itself (special tokens) is applied natively from the model's own GGUF metadata.
 *
 * [hints] use the authors' documented terminology form ("Reference the following translations").
 * Measured on the real model (CI mt-eval, 36 hard ar<->en items): hints raise correct term
 * renderings from 21 to 30, while quantisation (Q4_K_M/Q6_K/Q8_0), sampling vs. greedy and the
 * instruction language each changed the result by at most one item.
 */
object PromptBuilder {
    fun build(text: String, source: Language, target: Language, hints: List<Glossary.Hint> = emptyList()): String {
        val chinese = source.code in LanguageRegistry.chineseFamily || target.code in LanguageRegistry.chineseFamily
        return if (chinese && target.promptNameZh != null) {
            buildString {
                if (hints.isNotEmpty()) {
                    append("参考下面的翻译：\n")
                    hints.forEach { append(it.source).append(" 翻译成 ").append(it.target).append('\n') }
                }
                append("将以下文本翻译为${target.promptNameZh}，注意只需要输出翻译后的结果，不要额外解释：\n\n$text")
            }
        } else if (hints.isNotEmpty()) {
            buildString {
                append("Reference the following translations:\n")
                hints.forEach { append(it.source).append(" translates to ").append(it.target).append('\n') }
                append("\nTranslate the following text into ${target.promptNameEn}. Note that you must ONLY output the translated result without any additional explanation:\n\n$text")
            }
        } else {
            "Translate the following text into ${target.promptNameEn}. Note that you should only output the translated result without any additional explanation:\n\n$text"
        }
    }
}

/** Recommended decoding parameters published for the 1.8B model. */
data class DecodingParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.6f,
    val topK: Int = 20,
    val repetitionPenalty: Float = 1.05f,
    val maxNewTokens: Int = 1024,
    val seed: Int = 42,
)

/**
 * Splits long input into chunks the model can translate independently, cutting only at paragraph,
 * then sentence, then clause boundaries, and only as a last resort at whitespace. Separators
 * (newlines, spaces) between chunks are preserved verbatim so the output keeps its layout.
 */
object TextSegmenter {
    data class Piece(val text: String, val separatorAfter: String)

    private val sentenceEnd = Regex("(?<=[.!?。！？؟۔…])[\"'”’»)\\]]*\\s+")
    private val clauseEnd = Regex("(?<=[,;:،؛，；：])\\s+")

    fun split(input: String, maxChars: Int = 1_000): List<Piece> {
        require(maxChars >= 50)
        if (input.isBlank()) return emptyList()
        val out = ArrayList<Piece>()
        // Paragraphs: keep the exact newline run as the separator.
        val para = Regex("\\n\\s*\\n|\\n")
        var last = 0
        for (m in para.findAll(input)) {
            addParagraph(input.substring(last, m.range.first), m.value, maxChars, out)
            last = m.range.last + 1
        }
        addParagraph(input.substring(last), "", maxChars, out)
        // Leading whitespace of the whole input is not translatable; drop empty pieces but keep separators.
        return mergeEmpty(out)
    }

    private fun addParagraph(p: String, sep: String, max: Int, out: MutableList<Piece>) {
        if (p.length <= max) { out += Piece(p, sep); return }
        val sentences = splitKeeping(p, sentenceEnd)
        packInto(sentences, sep, max, out) { s ->
            val clauses = splitKeeping(s.first, clauseEnd)
            val tmp = ArrayList<Piece>()
            packInto(clauses, s.second, max, tmp) { c -> hardWrap(c.first, c.second, max) }
            tmp
        }
    }

    /** Splits keeping the delimiter whitespace as the separator of the left part. */
    private fun splitKeeping(s: String, re: Regex): List<Pair<String, String>> {
        val res = ArrayList<Pair<String, String>>()
        var last = 0
        for (m in re.findAll(s)) {
            // Punctuation/quotes before whitespace belong to the left part.
            val ws = m.value.indexOfFirst { it.isWhitespace() }.let { if (it < 0) m.value.length else it }
            val cut = m.range.first + ws
            res += s.substring(last, cut) to s.substring(cut, m.range.last + 1)
            last = m.range.last + 1
        }
        res += s.substring(last) to ""
        return res
    }

    private fun packInto(
        units: List<Pair<String, String>>, finalSep: String, max: Int, out: MutableList<Piece>,
        tooBig: (Pair<String, String>) -> List<Piece>,
    ) {
        val sb = StringBuilder()
        var pendingSep = ""
        fun flush(sep: String) {
            if (sb.isNotEmpty()) { out += Piece(sb.toString(), sep); sb.clear() }
        }
        for ((i, u) in units.withIndex()) {
            val isLast = i == units.lastIndex
            val sep = if (isLast) finalSep else u.second
            if (u.first.length > max) {
                flush(pendingSep)
                val parts = tooBig(u.first to sep)
                out += parts
                pendingSep = ""
                continue
            }
            if (sb.isNotEmpty() && sb.length + pendingSep.length + u.first.length > max) flush(pendingSep)
            else if (sb.isNotEmpty()) sb.append(pendingSep)
            sb.append(u.first)
            pendingSep = sep
        }
        flush(pendingSep)
    }

    private fun hardWrap(s: String, sep: String, max: Int): List<Piece> {
        val res = ArrayList<Piece>()
        var start = 0
        while (start < s.length) {
            var end = minOf(s.length, start + max)
            if (end < s.length) {
                val ws = s.lastIndexOf(' ', end)
                if (ws > start) end = ws
                // Never split a surrogate pair.
                if (Character.isHighSurrogate(s[end - 1])) end--
            }
            val chunk = s.substring(start, end)
            var next = end
            var gap = ""
            while (next < s.length && s[next] == ' ') { gap += " "; next++ }
            res += Piece(chunk, if (next >= s.length) sep else gap)
            start = next
        }
        return res
    }

    private fun mergeEmpty(pieces: List<Piece>): List<Piece> {
        val res = ArrayList<Piece>()
        var carry = ""
        for (p in pieces) {
            if (p.text.isBlank()) { carry += p.text + p.separatorAfter; continue }
            if (carry.isNotEmpty() && res.isNotEmpty()) {
                val prev = res.removeAt(res.lastIndex)
                res += prev.copy(separatorAfter = prev.separatorAfter + carry)
            }
            carry = ""
            res += p
        }
        if (carry.isNotEmpty() && res.isNotEmpty()) {
            val prev = res.removeAt(res.lastIndex)
            res += prev.copy(separatorAfter = prev.separatorAfter + carry)
        }
        return res
    }

    fun join(translated: List<String>, pieces: List<Piece>): String {
        require(translated.size == pieces.size)
        val sb = StringBuilder()
        for (i in pieces.indices) sb.append(translated[i].trim()).append(pieces[i].separatorAfter)
        return sb.toString()
    }
}

/** Cleans raw model output without changing its meaning. */
object OutputSanitizer {
    private val thinkBlock = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

    fun clean(raw: String): String = raw.replace(thinkBlock, "").trim()
}

/** Structured result of a translation request. Never contains invented text. */
sealed interface TranslationResult {
    data class Success(val text: String, val metrics: TranslationMetrics) : TranslationResult
    data class Failure(val error: TranslationError, val detail: String? = null) : TranslationResult
    data object Cancelled : TranslationResult
}

data class TranslationMetrics(
    val promptTokens: Int,
    val generatedTokens: Int,
    val prefillMs: Long,
    val decodeMs: Long,
    val totalMs: Long,
)

enum class TranslationError {
    MODEL_NOT_INSTALLED, MODEL_LOAD_FAILED, UNSUPPORTED_LANGUAGE, INPUT_TOO_LONG, INFERENCE_FAILED, OUT_OF_MEMORY, EMPTY_OUTPUT
}

/** Contract implemented by the native runtime (and by nothing else in production code). */
interface TranslationEngine : AutoCloseable {
    val isLoaded: Boolean
    suspend fun load(modelPath: String, threads: Int, contextSize: Int): Result<ModelInfo>
    suspend fun translate(text: String, source: Language, target: Language, params: DecodingParams = DecodingParams()): TranslationResult
    /** Requests cancellation of the running generation (checked between tokens). */
    fun cancel()
    fun unload()
    override fun close() = unload()
}

data class ModelInfo(
    val architecture: String,
    val name: String,
    val parameterCount: Long,
    val sizeBytes: Long,
    val contextTrain: Int,
    val hasChatTemplate: Boolean,
)
