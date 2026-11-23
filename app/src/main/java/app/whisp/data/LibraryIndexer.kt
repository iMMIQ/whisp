// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import okio.ByteString.Companion.encodeUtf8

data class FileEntry(
    val path: String,
    val uri: String,
    val directory: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
)

data class IndexedLibrary(
    val works: List<WorkEntity>,
    val tracks: List<TrackEntity>,
    val attachments: List<AttachmentEntity>,
    val locations: List<LocationEntity>,
)

fun stableId(value: String): String = value.encodeUtf8().sha256().substring(0, 16).hex()

object LibraryIndexer {
    private val rjPattern = Regex("RJ\\d{6,}", RegexOption.IGNORE_CASE)
    private val images = setOf("jpg", "jpeg", "png", "webp")
    private val codePrefix = Regex("^[A-Za-z]\\d+[_-]")
    private val leadingNumber = Regex("^(\\d+)")
    private val leadingNumberSep = Regex("^\\d+[_ .-]*")
    private val bonusPattern = Regex("附赠|特典|おまけ|omake|bonus", RegexOption.IGNORE_CASE)
    private val bracketPattern = Regex("【[^】]*】|\\[[^]]*]")
    private val seriesFrame = Regex("^[「『]|[」』]系列$")

    private fun String.extension() = substringAfterLast('.', "").lowercase()

    private fun String.basename() = substringAfterLast('/')

    private val subtitleSuffix =
        Regex("\\.(${MediaFormats.subtitles.joinToString("|")})$", RegexOption.IGNORE_CASE)
    private val resourceSuffix =
        Regex(
            "\\.(${(MediaFormats.playable + setOf("txt", "jpg", "jpeg", "png", "webp", "pdf")).joinToString("|")})$",
            RegexOption.IGNORE_CASE,
        )

    private fun stem(name: String) = name.replace(subtitleSuffix, "").replace(resourceSuffix, "")

    fun number(name: String): Int =
        leadingNumber.find(name.replace(codePrefix, ""))?.groupValues?.get(1)?.toIntOrNull() ?: 9999

    fun trackTitle(name: String): String =
        stem(name).replace(codePrefix, "").replace(leadingNumberSep, "").trim().ifBlank {
            stem(name)
        }

    private fun rootOf(path: String): String {
        val dirs = path.split('/').dropLast(1)
        val matches = dirs.mapIndexedNotNull { index, name ->
            rjPattern.find(name)?.value?.uppercase()?.let { index to it }
        }
        if (matches.isEmpty()) return dirs.joinToString("/")
        val lastId = matches.last().second
        val index =
            matches
                .lastOrNull { it.second != lastId }
                ?.first
                ?.let { previous ->
                    matches.first { it.first > previous && it.second == lastId }.first
                } ?: matches.first().first
        return dirs.take(index + 1).joinToString("/")
    }

    fun index(
        files: List<FileEntry>,
        sourceId: String,
        generation: Long,
        workOverride: WorkEntity? = null,
    ): IndexedLibrary {
        // Determine each file's work once, instead of walking every path again for every work.
        val fileGroups =
            files.filterNot { it.directory }.groupBy { workOverride?.rj ?: rootOf(it.path) }
        val groups =
            fileGroups
                .mapValues { (_, entries) ->
                    entries.filter { it.path.extension() in MediaFormats.playable }
                }
                .filterValues { it.isNotEmpty() }
        val works = mutableListOf<WorkEntity>()
        val tracks = mutableListOf<TrackEntity>()
        val attachments = mutableListOf<AttachmentEntity>()
        val locations = mutableListOf<LocationEntity>()
        groups.toSortedMap().forEach { (root, group) ->
            val name = root.basename().ifBlank { "未命名作品" }
            val rj = rjPattern.find(name)?.value?.uppercase()
            val mediaSignature =
                group.map { "${it.path.removePrefix("$root/")}:${it.size}" }.sorted()
            val workId =
                workOverride?.id ?: stableId("${rj ?: name}|${mediaSignature.joinToString("|")}")
            val inWork = fileGroups.getValue(root)
            val resourceFiles = inWork.filter {
                it.path.extension() !in MediaFormats.playable &&
                    it.path.extension() in images + MediaFormats.subtitles + setOf("txt", "pdf")
            }
            val resources = resourceFiles.associateWith { file ->
                val relative = file.path.removePrefix("$root/")
                val fileName = file.path.basename()
                val kind =
                    when (file.path.extension()) {
                        in MediaFormats.subtitles -> "subtitle"
                        "txt" ->
                            if (bonusPattern.containsMatchIn(relative.substringBeforeLast('/')))
                                "extra-text"
                            else "script"
                        in images -> "image"
                        else -> "document"
                    }
                AttachmentEntity(
                    stableId("$workId|$relative"),
                    workId,
                    trackTitle(fileName),
                    fileName,
                    relative,
                    kind,
                )
            }
            val cover =
                resources.entries
                    .filter { it.value.kind == "image" }
                    .maxByOrNull {
                        val n = it.key.path.lowercase()
                        when {
                            ("封面" in n || "cover" in n || "folder" in n) &&
                                "介绍" !in n &&
                                "back" !in n -> 100
                            "main" in n -> 70
                            "插画" in n -> 30
                            "角色" in n -> 0
                            else -> 10
                        }
                    }
                    ?.value
                    ?.id
            val title =
                name.replace(rjPattern, "").replace(bracketPattern, "").trim().ifBlank { name }
            val series =
                root
                    .split('/')
                    .dropLast(1)
                    .lastOrNull()
                    ?.takeUnless { rjPattern.containsMatchIn(it) }
                    ?.replace(seriesFrame, "")
            works +=
                workOverride?.copy(coverId = workOverride.coverId ?: cover)
                    ?: WorkEntity(workId, rj, title, name, series = series, coverId = cover)
            fun addLocation(file: FileEntry, id: String) {
                locations +=
                    LocationEntity(
                        stableId("$sourceId|$id|${file.uri}"),
                        id,
                        sourceId,
                        file.uri,
                        file.size,
                        file.modified,
                        generation,
                    )
            }
            resources.forEach { (file, attachment) ->
                attachments += attachment
                addLocation(file, attachment.id)
            }
            // Index companion resources by normalized stem once (O(T + R) instead of O(T * R)).
            val resourcesByStem =
                resources.entries.groupBy { stem(it.key.path.basename()).lowercase() }
            group
                .sortedWith(compareBy<FileEntry> { number(it.path.basename()) }.thenBy { it.path })
                .forEach { file ->
                    val fileName = file.path.basename()
                    val relative = file.path.removePrefix("$root/")
                    val sameName = resourcesByStem[stem(fileName).lowercase()].orEmpty()
                    // Exact stem wins; script folders sometimes prefix the work's code.
                    fun match(kind: String) =
                        sameName
                            .filter { it.value.kind == kind }
                            .minByOrNull {
                                if (
                                    it.key.path.substringBeforeLast('/') ==
                                        file.path.substringBeforeLast('/')
                                )
                                    0
                                else 1
                            }
                            ?.value
                            ?.id
                    val subtitle =
                        sameName
                            .filter { it.value.kind == "subtitle" }
                            .minWithOrNull(
                                compareBy<Map.Entry<FileEntry, AttachmentEntity>> {
                                        if (
                                            it.key.path.substringBeforeLast('/', "") ==
                                                file.path.substringBeforeLast('/', "")
                                        )
                                            0
                                        else 1
                                    }
                                    .thenBy { if (it.key.path.extension() == "vtt") 0 else 1 }
                                    .thenBy { it.key.path },
                            )
                            ?.value
                            ?.id
                    val track =
                        TrackEntity(
                            stableId("$workId|$relative"),
                            workId,
                            trackTitle(fileName),
                            fileName,
                            relative,
                            number(fileName),
                            subtitleId = subtitle,
                            scriptId = match("script"),
                            bonus = bonusPattern.containsMatchIn(relative),
                        )
                    tracks += track
                    addLocation(file, track.id)
                }
        }
        return IndexedLibrary(works, tracks, attachments, locations)
    }
}
