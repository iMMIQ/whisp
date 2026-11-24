// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.webvtt.WebvttParser

data class Cue(val startMs: Long, val endMs: Long, val text: String)

data class DisplayCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val secondaryText: String? = null,
) {
    fun toCue(): Cue = Cue(startMs, endMs, text)
}

@androidx.annotation.OptIn(UnstableApi::class)
object SubtitleTimeline {
    private val enhancedLrcTag = Regex("<\\d+:\\d+(?:[.,]\\d+)?>")
    private val assOverrideTag = Regex("\\{\\\\[^}]*\\}")

    private fun cleanTimedText(raw: String): String =
        if ('{' in raw) raw.replace(assOverrideTag, "") else raw

    private fun time(value: String): Long {
        val parts = value.trim().replace(',', '.').split(':')
        require(parts.size in 2..3)
        val seconds = parts.last().toDouble()
        val minutes = parts[parts.lastIndex - 1].toLong()
        val hours = if (parts.size == 3) parts[0].toLong() else 0L
        return (hours * 3600000 + minutes * 60000 + seconds * 1000).toLong()
    }

    fun parse(content: String, extension: String): List<Cue> =
        when (extension.lowercase()) {
            "lrc" -> lrc(content)
            "srt" -> timed(content, SubripParser())
            else -> vtt(content)
        }

    fun vtt(content: String): List<Cue> {
        return timed(content, WebvttParser())
    }

    private fun timed(content: String, parser: SubtitleParser): List<Cue> {
        if (content.isBlank()) return emptyList()
        val bytes = content.removePrefix("\uFEFF").toByteArray(Charsets.UTF_8)
        val result = mutableListOf<Cue>()
        parser.parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { group ->
            val text =
                group.cues
                    .mapNotNull { it.text?.toString()?.let(::cleanTimedText) }
                    .joinToString("\n")
            val start = group.startTimeUs / 1000
            val end = group.endTimeUs / 1000
            if (text.isNotBlank() && start >= 0 && end > start) result += Cue(start, end, text)
        }
        return result.sortedBy { it.startMs }
    }

    fun lrc(content: String): List<Cue> {
        val timestamp = Regex("\\[(\\d+:\\d+(?:[.,]\\d+)?)\\]")
        val offset =
            Regex("\\[offset:([+-]?\\d+)\\]", RegexOption.IGNORE_CASE)
                .find(content)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull() ?: 0L
        // Keep empty-text timestamp markers so silence breaks terminate the previous cue cleanly.
        val rawEvents =
            content
                .lines()
                .flatMap { line ->
                    val matches = timestamp.findAll(line).toList()
                    if (matches.isEmpty()) emptyList()
                    else {
                        val cleaned =
                            line
                                .replace(timestamp, "")
                                .replace(enhancedLrcTag, "")
                                .let(::cleanTimedText)
                                .trim()
                        matches.mapNotNull { match ->
                            runCatching {
                                (time(match.groupValues[1]) + offset).coerceAtLeast(0L) to cleaned
                            }
                                .getOrNull()
                        }
                    }
                }
                .sortedBy { it.first }
                .groupBy({ it.first }, { it.second })
                .map { (start, texts) ->
                    val nonBlank = texts.filter(String::isNotBlank).distinct()
                    start to nonBlank.joinToString("\n")
                }
        val result = mutableListOf<Cue>()
        for (index in rawEvents.indices) {
            val (start, text) = rawEvents[index]
            if (text.isBlank()) continue
            val end = rawEvents.getOrNull(index + 1)?.first ?: Long.MAX_VALUE
            if (end > start) result += Cue(start, end, text)
        }
        return result
    }

    fun current(cues: List<Cue>, positionMs: Long): Int {
        val found = anchor(cues, positionMs)
        if (found < 0) return -1
        if (positionMs >= cues[found].startMs && positionMs < cues[found].endMs) return found
        // Check a bounded window of earlier cues in case a shorter nested cue ended inside a longer
        // one.
        val floor = (found - 8).coerceAtLeast(0)
        for (i in found - 1 downTo floor) {
            val cue = cues[i]
            if (positionMs >= cue.startMs && positionMs < cue.endMs) return i
        }
        return -1
    }

    // Keep a readable context during quiet intervals, while current() retains exact timing.
    fun anchor(cues: List<Cue>, positionMs: Long): Int {
        if (cues.isEmpty()) return -1
        // An upper-bound insertion point also selects the last duplicate timestamp.
        val insertion = cues.binarySearch { if (it.startMs <= positionMs) -1 else 1 }
        return (-insertion - 2).coerceAtLeast(0)
    }

    /**
     * Aligns an optional secondary subtitle track onto [primary] cues for bilingual display by
     * matching overlapping or nearby timestamps without altering the primary timeline order.
     */
    fun mergeBilingual(primary: List<Cue>, secondary: List<Cue>): List<DisplayCue> {
        if (primary.isEmpty()) return emptyList()
        if (secondary.isEmpty()) return primary.map { DisplayCue(it.startMs, it.endMs, it.text) }
        return primary.map { cue ->
            val probeMs =
                if (cue.endMs < Long.MAX_VALUE) (cue.startMs + cue.endMs) / 2L
                else cue.startMs + 250L
            val matched =
                current(secondary, probeMs).takeIf { it >= 0 }?.let { secondary[it] }
                    ?: run {
                        val nearIdx = anchor(secondary, cue.startMs)
                        listOfNotNull(
                                secondary.getOrNull(nearIdx),
                                secondary.getOrNull(nearIdx + 1),
                            )
                            .firstOrNull { candidate ->
                                val overlapStart = maxOf(cue.startMs, candidate.startMs)
                                val overlapEnd = minOf(cue.endMs, candidate.endMs)
                                overlapEnd > overlapStart ||
                                    kotlin.math.abs(candidate.startMs - cue.startMs) <= 600L
                            }
                    }
            DisplayCue(
                startMs = cue.startMs,
                endMs = cue.endMs,
                text = cue.text,
                secondaryText = matched?.text?.takeIf { it != cue.text },
            )
        }
    }
}
