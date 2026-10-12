package com.modulex.translator.core.assets

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Reads the key/value metadata header of a GGUF file without loading tensors, so the app can check
 * that an imported or downloaded file really is the expected model architecture before handing it
 * to the native runtime.
 */
data class GgufInfo(
    val version: Int,
    val tensorCount: Long,
    val metadata: Map<String, Any>,
) {
    val architecture: String? get() = metadata["general.architecture"] as? String
    val name: String? get() = metadata["general.name"] as? String
    val fileType: Long? get() = (metadata["general.file_type"] as? Number)?.toLong()
    val hasChatTemplate: Boolean get() = metadata["tokenizer.chat_template"] is String
    fun contextLength(): Long? = architecture?.let { (metadata["$it.context_length"] as? Number)?.toLong() }

    companion object {
        private const val MAGIC = 0x46554747 // "GGUF" little-endian
        private const val MAX_STRING = 1 shl 24
        private const val MAX_KV = 100_000
        private const val MAX_ARRAY_KEEP = 64

        fun read(file: File): GgufInfo = file.inputStream().buffered(1 shl 16).use { read(it) }

        fun read(input: InputStream): GgufInfo {
            val r = LeReader(DataInputStream(input))
            if (r.i32() != MAGIC) throw IOException("not a GGUF file")
            val version = r.i32()
            if (version !in 2..3) throw IOException("unsupported GGUF version $version")
            val tensors = r.i64()
            val kvCount = r.i64()
            if (tensors < 0 || kvCount < 0 || kvCount > MAX_KV) throw IOException("corrupt GGUF header")
            val meta = LinkedHashMap<String, Any>()
            repeat(kvCount.toInt()) {
                val key = r.str()
                val type = r.i32()
                meta[key] = r.value(type)
            }
            return GgufInfo(version, tensors, meta)
        }

        private class LeReader(val d: DataInputStream) {
            fun u8() = d.readUnsignedByte()
            fun i32(): Int = Integer.reverseBytes(d.readInt())
            fun i64(): Long = java.lang.Long.reverseBytes(d.readLong())
            fun f32(): Float = java.lang.Float.intBitsToFloat(i32())
            fun f64(): Double = java.lang.Double.longBitsToDouble(i64())
            fun str(): String {
                val n = i64()
                if (n < 0 || n > MAX_STRING) throw IOException("string too long")
                val b = ByteArray(n.toInt())
                d.readFully(b)
                return String(b, Charsets.UTF_8)
            }

            fun value(type: Int): Any = when (type) {
                0 -> u8()
                1 -> u8().toByte().toInt()
                2 -> java.lang.Short.reverseBytes(d.readShort()).toInt() and 0xffff
                3 -> java.lang.Short.reverseBytes(d.readShort()).toInt()
                4 -> i32().toLong() and 0xffffffffL
                5 -> i32()
                6 -> f32()
                7 -> u8() != 0
                8 -> str()
                9 -> {
                    val et = i32()
                    val n = i64()
                    if (n < 0) throw IOException("bad array length")
                    // Large arrays (vocabularies) are skipped, keeping only a short prefix.
                    val kept = ArrayList<Any>()
                    var i = 0L
                    while (i < n) {
                        val v = value(et)
                        if (kept.size < MAX_ARRAY_KEEP) kept += v
                        i++
                    }
                    GgufArray(et, n, kept)
                }
                10 -> i64()
                11 -> i64()
                12 -> f64()
                else -> throw IOException("unknown GGUF value type $type")
            }
        }
    }
}

data class GgufArray(val elementType: Int, val length: Long, val prefix: List<Any>)

/** Minimal check for whisper.cpp ggml model files ("ggml" magic, little-endian). */
object GgmlSpeechModelCheck {
    fun isGgml(file: File): Boolean = try {
        file.inputStream().use { s ->
            val b = ByteArray(4)
            if (s.read(b) != 4) return false
            val magic = (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)
            magic == 0x67676d6c
        }
    } catch (e: EOFException) { false }
}
