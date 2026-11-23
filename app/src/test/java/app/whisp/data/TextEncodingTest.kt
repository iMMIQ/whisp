// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class TextEncodingTest {
    @Test
    fun decodesUtf8AndBomStreams() {
        val sample = "轻声耳语 テスト"
        assertEquals(sample, TextEncoding.decode(sample.toByteArray(Charsets.UTF_8)))
        val utf8Bom =
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                sample.toByteArray(Charsets.UTF_8)
        assertEquals(sample, TextEncoding.decode(utf8Bom))
        val utf16Le =
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sample.toByteArray(Charsets.UTF_16LE)
        assertEquals(sample, TextEncoding.decode(utf16Le))
        val utf16Be =
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + sample.toByteArray(Charsets.UTF_16BE)
        assertEquals(sample, TextEncoding.decode(utf16Be))
    }

    @Test
    fun detectsShiftJisAndGb18030Automatically() {
        val japanese = "おやすみなさい、今日もありがとう。ゆっくり休んでね。"
        val sjisBytes = japanese.toByteArray(Charset.forName("Shift_JIS"))
        assertEquals(japanese, TextEncoding.decode(sjisBytes, TextEncoding.AUTO))
        assertEquals(japanese, TextEncoding.decode(sjisBytes, TextEncoding.SHIFT_JIS))

        val chinese = "晚安，今天也辛苦了，请伴着雨声好好休息吧。"
        val gbBytes = chinese.toByteArray(Charset.forName("GB18030"))
        assertEquals(chinese, TextEncoding.decode(gbBytes, TextEncoding.AUTO))
        assertEquals(chinese, TextEncoding.decode(gbBytes, TextEncoding.GB18030))
    }
}
