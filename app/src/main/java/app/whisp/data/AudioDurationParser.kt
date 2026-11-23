// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

/**
 * Lightweight header-only duration parser for remote audio files (MP3, FLAC, WAV, Ogg/Opus, M4A)
 * using small range reads instead of downloading full media payloads.
 */
object AudioDurationParser {
    private val supported = setOf("mp3", "flac", "wav", "ogg", "opus", "m4a")

    fun supports(fileName: String): Boolean = MediaFormats.extension(fileName) in supported

    fun duration(fileName: String, size: Long, read: (Long, Int) -> ByteArray): Long {
        if (size <= 0) return 0
        return when (MediaFormats.extension(fileName)) {
            "mp3" -> Mp3Metadata.duration(size, read)
            "flac" -> flacDuration(size, read)
            "wav" -> wavDuration(size, read)
            "ogg",
            "opus" -> oggDuration(size, read)
            "m4a" -> mp4Duration(size, read)
            else -> 0L
        }
    }

    internal fun flacDuration(size: Long, read: (Long, Int) -> ByteArray): Long {
        if (size < 42) return 0
        val head = read(0, minOf(64L, size).toInt())
        if (head.size < 42 || ascii(head, 0, 4) != "fLaC") return 0
        val blockType = head[4].toInt() and 0x7f
        val blockLen = u24be(head, 5)
        if (blockType != 0 || blockLen < 34) return 0
        // STREAMINFO starts at offset 8; bytes 18..25 contain 20-bit sampleRate + 3-bit channels +
        // 5-bit bps + 36-bit totalSamples.
        val o = 8
        val sampleRate =
            ((u8(head, o + 10) shl 12) or (u8(head, o + 11) shl 4) or (u8(head, o + 12) ushr 4))
                .toLong()
        val totalSamples = ((u8(head, o + 13).toLong() and 0x0fL) shl 32) or u32be(head, o + 14)
        if (sampleRate <= 0 || totalSamples <= 0) return 0
        return totalSamples * 1000L / sampleRate
    }

    internal fun wavDuration(size: Long, read: (Long, Int) -> ByteArray): Long {
        if (size < 44) return 0
        val head = read(0, minOf(4096L, size).toInt())
        if (head.size < 44 || ascii(head, 0, 4) != "RIFF" || ascii(head, 8, 4) != "WAVE") return 0
        var byteRate = 0L
        var dataSize = 0L
        var offset = 12
        while (offset + 8 <= head.size) {
            val id = ascii(head, offset, 4)
            val chunkSize = u32le(head, offset + 4)
            val dataOffset = offset + 8
            if (id == "fmt " && dataOffset + 16 <= head.size) {
                byteRate = u32le(head, dataOffset + 8)
            } else if (id == "data") {
                dataSize =
                    if (chunkSize == 0xFFFF_FFFFL || chunkSize == 0L) size - dataOffset
                    else minOf(chunkSize, size - dataOffset)
                break
            }
            val next = dataOffset.toLong() + chunkSize + (chunkSize and 1L)
            if (next <= offset || next >= head.size) break
            offset = next.toInt()
        }
        if (byteRate <= 0 || dataSize <= 0) return 0
        return dataSize * 1000L / byteRate
    }

    internal fun oggDuration(size: Long, read: (Long, Int) -> ByteArray): Long {
        if (size < 58) return 0
        val head = read(0, minOf(4096L, size).toInt())
        if (head.size < 58 || ascii(head, 0, 4) != "OggS") return 0
        val segments = u8(head, 26)
        val payloadOffset = 27 + segments
        if (payloadOffset + 16 > head.size) return 0
        val sampleRate =
            when {
                ascii(head, payloadOffset, 8) == "OpusHead" -> 48_000L
                head[payloadOffset] == 1.toByte() &&
                    ascii(head, payloadOffset + 1, 6) == "vorbis" -> u32le(head, payloadOffset + 12)
                else -> 0L
            }
        if (sampleRate <= 0) return 0
        val preSkip =
            if (ascii(head, payloadOffset, 8) == "OpusHead" && payloadOffset + 12 <= head.size)
                u16le(head, payloadOffset + 10).toLong()
            else 0L
        val tailLen = minOf(65_536L, size).toInt()
        val tail = read(size - tailLen, tailLen)
        var lastGranule = -1L
        for (i in (tail.size - 14) downTo 0) {
            if (
                tail[i] == 'O'.code.toByte() &&
                    tail[i + 1] == 'g'.code.toByte() &&
                    tail[i + 2] == 'g'.code.toByte() &&
                    tail[i + 3] == 'S'.code.toByte()
            ) {
                val granule = i64le(tail, i + 6)
                if (granule > 0) {
                    lastGranule = granule
                    break
                }
            }
        }
        val samples = (lastGranule - preSkip).coerceAtLeast(0L)
        return if (samples > 0) samples * 1000L / sampleRate else 0L
    }

    internal fun mp4Duration(size: Long, read: (Long, Int) -> ByteArray): Long {
        if (size < 32) return 0
        var offset = 0L
        var hops = 0
        while (offset + 8 <= size && hops++ < 32) {
            val header = read(offset, minOf(16L, size - offset).toInt())
            if (header.size < 8) return 0
            val rawSize = u32be(header, 0)
            val type = ascii(header, 4, 4)
            val headerSize: Long
            val boxSize: Long
            if (rawSize == 1L) {
                if (header.size < 16) return 0
                headerSize = 16L
                boxSize = i64be(header, 8)
            } else if (rawSize == 0L) {
                headerSize = 8L
                boxSize = size - offset
            } else {
                headerSize = 8L
                boxSize = rawSize
            }
            if (boxSize < headerSize || offset + boxSize > size) return 0
            if (type == "moov") {
                val moovPayloadOffset = offset + headerSize
                val moovPayloadLen = minOf(boxSize - headerSize, 256L * 1024).toInt()
                val moov = read(moovPayloadOffset, moovPayloadLen)
                return parseMvhd(moov)
            }
            offset += boxSize
        }
        return 0
    }

    private fun parseMvhd(moov: ByteArray): Long {
        var pos = 0
        while (pos + 8 <= moov.size) {
            val boxSize = u32be(moov, pos)
            val type = ascii(moov, pos + 4, 4)
            if (boxSize < 8 || pos + boxSize > moov.size) break
            if (type == "mvhd" && pos + 28 <= moov.size) {
                val version = u8(moov, pos + 8)
                if (version == 0 && pos + 28 <= moov.size) {
                    val timescale = u32be(moov, pos + 20)
                    val duration = u32be(moov, pos + 24)
                    return if (timescale > 0 && duration > 0) duration * 1000L / timescale else 0L
                } else if (version == 1 && pos + 40 <= moov.size) {
                    val timescale = u32be(moov, pos + 28)
                    val duration = i64be(moov, pos + 32)
                    return if (timescale > 0 && duration > 0) duration * 1000L / timescale else 0L
                }
            }
            pos += boxSize.toInt()
        }
        return 0L
    }

    private fun u8(data: ByteArray, index: Int): Int = data[index].toInt() and 0xff

    private fun u16le(data: ByteArray, index: Int): Int =
        u8(data, index) or (u8(data, index + 1) shl 8)

    private fun u24be(data: ByteArray, index: Int): Int =
        (u8(data, index) shl 16) or (u8(data, index + 1) shl 8) or u8(data, index + 2)

    private fun u32be(data: ByteArray, index: Int): Long =
        (u8(data, index).toLong() shl 24) or
            (u8(data, index + 1).toLong() shl 16) or
            (u8(data, index + 2).toLong() shl 8) or
            u8(data, index + 3).toLong()

    private fun u32le(data: ByteArray, index: Int): Long =
        u8(data, index).toLong() or
            (u8(data, index + 1).toLong() shl 8) or
            (u8(data, index + 2).toLong() shl 16) or
            (u8(data, index + 3).toLong() shl 24)

    private fun i64be(data: ByteArray, index: Int): Long =
        (0..7).fold(0L) { acc, i -> (acc shl 8) or u8(data, index + i).toLong() }

    private fun i64le(data: ByteArray, index: Int): Long =
        (7 downTo 0).fold(0L) { acc, i -> (acc shl 8) or u8(data, index + i).toLong() }

    private fun ascii(data: ByteArray, offset: Int, length: Int): String =
        String(data, offset, length, Charsets.US_ASCII)
}
