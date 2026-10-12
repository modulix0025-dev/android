package com.modulex.translator.core.assets

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class PackKind {
    @SerialName("translation_model") TRANSLATION_MODEL,
    @SerialName("speech_model") SPEECH_MODEL,
    @SerialName("ocr_language") OCR_LANGUAGE,
    @SerialName("tts_voice") TTS_VOICE,
    @SerialName("tts_runtime_data") TTS_RUNTIME_DATA,
}

@Serializable
data class AssetFile(
    /** File name inside the pack directory. Must be a plain name (no path separators). */
    val name: String,
    /** HTTPS download URL pinned to an immutable revision, or null for import-only/bundled files. */
    val url: String? = null,
    val sizeBytes: Long? = null,
    /** Lowercase hex SHA-256. A file without a pinned hash can never be marked verified. */
    val sha256: String? = null,
)

@Serializable
data class AssetPack(
    val id: String,
    val kind: PackKind,
    /** Neutral, user-facing name (no vendor branding). */
    val title: String,
    val version: String,
    /** App language codes served by this pack; empty means "all languages of the engine". */
    val languages: List<String> = emptyList(),
    val files: List<AssetFile>,
    /** Upstream identity, kept for licensing/attribution and diagnostics (not shown on main screens). */
    val upstream: String,
    val license: String,
    val attribution: String,
    /** True when the files ship inside the APK (copied out on first use). */
    val bundled: Boolean = false,
    /** Engine-specific hints, e.g. quantization, expected GGUF architecture. */
    val meta: Map<String, String> = emptyMap(),
) {
    val totalBytes: Long? get() = if (files.all { it.sizeBytes != null }) files.sumOf { it.sizeBytes!! } else null

    /** All files have a pinned hash and size: the pack can be downloaded and verified. */
    val isPinned: Boolean get() = files.all { it.sha256 != null && it.sizeBytes != null }

    val isDownloadable: Boolean get() = isPinned && files.all { it.url?.startsWith("https://") == true }
}

@Serializable
data class AssetManifest(val schema: Int, val packs: List<AssetPack>) {
    fun pack(id: String) = packs.firstOrNull { it.id == id }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,128}$")
        private val SAFE_ID = Regex("^[a-z0-9._-]{1,64}$")
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        fun parse(text: String): AssetManifest {
            val m = json.decodeFromString(serializer(), text)
            require(m.schema == 1) { "unsupported manifest schema ${m.schema}" }
            val ids = HashSet<String>()
            for (p in m.packs) {
                require(SAFE_ID.matches(p.id)) { "bad pack id ${p.id}" }
                require(ids.add(p.id)) { "duplicate pack id ${p.id}" }
                require(p.files.isNotEmpty()) { "pack ${p.id} has no files" }
                for (f in p.files) {
                    require(SAFE_NAME.matches(f.name) && f.name != "." && f.name != "..") { "bad file name ${f.name}" }
                    f.sha256?.let { require(HEX64.matches(it)) { "bad sha256 for ${f.name}" } }
                    f.url?.let { require(it.startsWith("https://")) { "non-HTTPS url for ${f.name}" } }
                    f.sizeBytes?.let { require(it > 0) { "bad size for ${f.name}" } }
                }
            }
            return m
        }
    }
}
