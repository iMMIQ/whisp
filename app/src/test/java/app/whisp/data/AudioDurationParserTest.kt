// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class AudioDurationParserTest {
    private fun reader(bytes: ByteArray): (Long, Int) -> ByteArray = { offset, length ->
        val start = offset.toInt().coerceAtMost(bytes.size)
        val end = (start + length).coerceAtMost(bytes.size)
        bytes.copyOfRange(start, end)
    }

    @Test
    fun parsesFlacStreamInfoDuration() {
        // 44100 Hz, 441000 samples => 10_000 ms
        val bytes = ByteArray(64)
        "fLaC".toByteArray().copyInto(bytes, 0)
        bytes[4] = 0x80.toByte() // last metadata block, type 0 (STREAMINFO)
        bytes[7] = 34 // STREAMINFO length
        val sampleRate = 44100
        val totalSamples = 441000L
        val o = 8
        bytes[o + 10] = ((sampleRate ushr 12) and 0xff).toByte()
        bytes[o + 11] = ((sampleRate ushr 4) and 0xff).toByte()
        bytes[o + 12] = ((sampleRate and 0x0f) shl 4).toByte()
        bytes[o + 13] = ((totalSamples ushr 32) and 0x0f).toByte()
        bytes[o + 14] = ((totalSamples ushr 24) and 0xff).toByte()
        bytes[o + 15] = ((totalSamples ushr 16) and 0xff).toByte()
        bytes[o + 16] = ((totalSamples ushr 8) and 0xff).toByte()
        bytes[o + 17] = (totalSamples and 0xff).toByte()

        assertEquals(
            10_000L,
            AudioDurationParser.duration("01_track.flac", bytes.size.toLong(), reader(bytes)),
        )
    }

    @Test
    fun parsesWavDurationSkippingExtraChunks() {
        // Stereo 16-bit 48000 Hz => byteRate = 192000; dataSize = 192000 * 5 => 5_000 ms
        val byteRate = 192_000
        val dataSize = byteRate * 5
        val buf = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(dataSize + 72)
        buf.put("WAVE".toByteArray())
        buf.put("JUNK".toByteArray())
        buf.putInt(4)
        buf.putInt(0)
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(2) // channels
        buf.putInt(48000)
        buf.putInt(byteRate)
        buf.putShort(4)
        buf.putShort(16)
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        val header = buf.array()
        val totalSize = header.size.toLong() + dataSize
        assertEquals(
            5_000L,
            AudioDurationParser.duration("01_track.wav", totalSize) { offset, len ->
                if (offset == 0L) header.copyOf(minOf(len, header.size)) else ByteArray(0)
            },
        )
    }

    @Test
    fun parsesOggOpusAndM4aDurations() {
        // Ogg Opus: pre-skip 312, final granule 480_312 => 10_000 ms at 48 kHz
        val ogg = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        ogg.put("OggS".toByteArray())
        ogg.position(26)
        ogg.put(1) // 1 segment
        ogg.put(19) // segment size
        ogg.put("OpusHead".toByteArray())
        ogg.put(1) // version
        ogg.put(2) // channels
        ogg.putShort(312) // pre-skip
        ogg.position(80)
        ogg.put("OggS".toByteArray())
        ogg.position(86)
        ogg.putLong(480_312L)
        val oggBytes = ogg.array()
        assertEquals(
            10_000L,
            AudioDurationParser.duration("voice.opus", oggBytes.size.toLong(), reader(oggBytes)),
        )

        // M4A with ftyp + moov/mvhd v0: timescale=1000, duration=42500 => 42_500 ms
        val mp4 = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN)
        mp4.putInt(16)
        mp4.put("ftyp".toByteArray())
        mp4.put("M4A ".toByteArray())
        mp4.putInt(0)
        mp4.putInt(48) // moov size
        mp4.put("moov".toByteArray())
        mp4.putInt(40) // mvhd size
        mp4.put("mvhd".toByteArray())
        mp4.putInt(0) // version 0 + flags
        mp4.putInt(0) // creation
        mp4.putInt(0) // modification
        mp4.putInt(1000) // timescale
        mp4.putInt(42500) // duration
        val mp4Bytes = mp4.array()
        assertEquals(
            42_500L,
            AudioDurationParser.duration("track.m4a", mp4Bytes.size.toLong(), reader(mp4Bytes)),
        )
    }

    @Test
    fun malformedInputsReturnZeroSafely() {
        val junk = ByteArray(100) { 0x55 }
        for (name in listOf("a.flac", "a.wav", "a.ogg", "a.opus", "a.m4a", "a.aac")) {
            assertEquals(0L, AudioDurationParser.duration(name, 100, reader(junk)))
        }
    }
}
