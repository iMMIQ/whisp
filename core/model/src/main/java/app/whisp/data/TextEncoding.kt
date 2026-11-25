// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes subtitle and script bytes with automatic detection for UTF-8, UTF-16 BOM, Shift_JIS
 * (Windows-31J / MS932), and GB18030 while allowing explicit overrides.
 */
object TextEncoding {
    const val AUTO = "auto"
    const val UTF8 = "UTF-8"
    const val SHIFT_JIS = "Shift_JIS"
    const val GB18030 = "GB18030"

    val supported = listOf(AUTO, UTF8, SHIFT_JIS, GB18030)

    fun decode(bytes: ByteArray, preferred: String = AUTO): String {
        if (bytes.isEmpty()) return ""
        if (preferred != AUTO) {
            return runCatching {
                val charset =
                    if (preferred == SHIFT_JIS)
                        runCatching { Charset.forName("Windows-31J") }
                            .getOrElse { Charset.forName("Shift_JIS") }
                    else Charset.forName(preferred)
                bytes.toString(charset).removePrefix("\uFEFF")
            }
                .getOrElse { bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF") }
        }
        // 1. Explicit BOM markers take precedence.
        if (
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // 2. Strict UTF-8 check: valid UTF-8 passes unmodified.
        strictDecode(bytes, Charsets.UTF_8)?.let {
            return it.removePrefix("\uFEFF")
        }
        // 3. Score legacy East Asian encodings (Windows-31J / Shift_JIS vs GB18030).
        val sjisCharset = runCatching {
            Charset.forName("Windows-31J")
        }
            .getOrElse { Charset.forName("Shift_JIS") }
        val gbCharset = runCatching {
            Charset.forName("GB18030")
        }
            .getOrElse { Charset.forName("GBK") }
        val sjis = strictDecode(bytes, sjisCharset)
        val gb = strictDecode(bytes, gbCharset)
        return when {
            sjis != null && gb == null -> sjis
            gb != null && sjis == null -> gb
            sjis != null && gb != null -> if (japaneseScore(sjis) >= chineseScore(gb)) sjis else gb
            else -> bytes.toString(Charsets.UTF_8)
        }.removePrefix("\uFEFF")
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = runCatching {
        charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
        .getOrNull()

    private fun japaneseScore(text: String): Int {
        var score = 0
        for (ch in text) {
            val code = ch.code
            when {
                // Hiragana & Katakana strongly indicate Japanese text.
                code in 0x3040..0x30FF || code in 0xFF65..0xFF9F -> score += 6
                // Common Japanese punctuation (、。「」『』・ー)
                code in 0x3000..0x303F -> score += 2
                // CJK Unified Ideographs
                code in 0x4E00..0x9FFF -> score += 1
                // Private-use or half-width control clutter reduces confidence
                code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' -> score -= 8
            }
        }
        return score
    }

    private fun chineseScore(text: String): Int {
        var score = 0
        for (ch in text) {
            val code = ch.code
            when {
                // Simplified Chinese punctuation & fullwidth marks
                code in 0x3000..0x303F || code in 0xFF01..0xFF5E -> score += 2
                // Common CJK Unified Ideographs
                code in 0x4E00..0x9FFF -> score += 2
                code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' -> score -= 8
            }
        }
        return score
    }
}
