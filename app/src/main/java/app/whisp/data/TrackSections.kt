// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

object EditionPreference {
    const val COMPACT_MP3 = "mp3"
    const val LOSSLESS_FIRST = "lossless"
    const val DEFAULT = "default"

    val supported = listOf(COMPACT_MP3, LOSSLESS_FIRST, DEFAULT)
}

data class TrackSection(
    val key: String,
    val title: String,
    val bonus: Boolean,
    val tracks: List<TrackEntity>,
) {
    val totalBytes: Long
        get() = tracks.sumOf { it.size.coerceAtLeast(0L) }
}

data class QueuePosition(val index: Int, val positionMs: Long)

/** Keep directory editions separate: equal names do not prove equal recordings. */
object TrackSections {
    private val bonusFolder = Regex("附赠|特典|おまけ|omake|bonus|extra", RegexOption.IGNORE_CASE)
    private val losslessHint = Regex("(?i)\\b(wav|flac|lossless|hi-?res)\\b|无损")
    private val compactHint = Regex("(?i)\\b(mp3|aac|opus|m4a|ogg)\\b")

    private fun sectionFormatRank(section: TrackSection, preference: String): Int {
        val title = section.title
        val exts = section.tracks.mapTo(HashSet()) { MediaFormats.extension(it.fileName) }
        val isLossless =
            losslessHint.containsMatchIn(title) ||
                (exts.isNotEmpty() && exts.all { it == "wav" || it == "flac" })
        val isCompact =
            title.equals("mp3", ignoreCase = true) ||
                compactHint.containsMatchIn(title) ||
                (exts.isNotEmpty() && exts.all { it in setOf("mp3", "m4a", "aac", "opus", "ogg") })
        return when (preference) {
            EditionPreference.LOSSLESS_FIRST ->
                when {
                    isLossless -> 0
                    !isCompact -> 1
                    else -> 2
                }
            EditionPreference.DEFAULT -> 0
            else ->
                when {
                    title.equals("mp3", ignoreCase = true) -> 0
                    isCompact -> 1
                    !isLossless -> 2
                    else -> 3
                }
        }
    }

    fun groups(
        tracks: List<TrackEntity>,
        preference: String = EditionPreference.COMPACT_MP3,
    ): List<TrackSection> {
        val parents = tracks.map { it.relativePath.substringBeforeLast('/', "") }
        val components = parents.map { it.split('/').filter(String::isNotBlank) }
        val common =
            if (components.isEmpty()) 0
            else
                components
                    .minOf { it.size }
                    .let { limit ->
                        (0 until limit).firstOrNull { i ->
                            components.any { it[i] != components.first()[i] }
                        } ?: limit
                    }
        return tracks
            .groupBy { track ->
                val parent = track.relativePath.substringBeforeLast('/', "")
                (track.bonus || bonusFolder.containsMatchIn(parent)) to parent
            }
            .map { (key, rows) ->
                val path =
                    key.second
                        .split('/')
                        .filter(String::isNotBlank)
                        .drop(common)
                        .joinToString(" / ")
                TrackSection(
                    "${key.first}:${key.second}",
                    path.ifBlank { if (key.first) "附赠" else "正篇" },
                    key.first,
                    rows.sortedWith(compareBy({ it.order }, { it.relativePath })),
                )
            }
            .sortedWith(
                compareBy<TrackSection>(
                    { it.bonus },
                    { sectionFormatRank(it, preference) },
                    { it.key },
                ),
            )
    }

    fun queue(
        tracks: List<TrackEntity>,
        selectedId: String? = null,
        preference: String = EditionPreference.COMPACT_MP3,
    ): List<TrackEntity> {
        val sections = groups(tracks, preference)
        return (sections.firstOrNull { it.tracks.any { t -> t.id == selectedId } }
                ?: sections.firstOrNull())
            ?.tracks
            .orEmpty()
    }
}

object PlaybackQueue {
    fun finished(tracks: List<TrackEntity>, progress: ProgressEntity?): Boolean {
        val last = tracks.lastOrNull() ?: return false
        return progress?.trackId == last.id &&
            last.durationMs > 0 &&
            progress.positionMs > 0 &&
            progress.positionMs >= last.durationMs - 500
    }

    fun resume(tracks: List<TrackEntity>, progress: ProgressEntity?): QueuePosition {
        val index = tracks.indexOfFirst { it.id == progress?.trackId }
        if (index < 0) return QueuePosition(0, 0)
        val position = progress!!.positionMs.coerceAtLeast(0)
        val duration = tracks[index].durationMs
        return if (duration > 0 && position > 0 && position >= duration - 500)
            QueuePosition(if (index == tracks.lastIndex) 0 else index + 1, 0)
        else QueuePosition(index, position)
    }
}
