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
)

fun stableId(value: String): String = value.encodeUtf8().sha256().substring(0, 16).hex()

object SubtitleLang {
    const val AUTO = "auto"
    const val NONE = "none"
    const val ZH = "zh"
    const val JA = "ja"
    const val EN = "en"
    const val KO = "ko"

    val primaryOptions = listOf(AUTO, ZH, JA, EN, KO)
    val secondaryOptions = listOf(NONE, ZH, JA, EN, KO)

    fun label(code: String): String =
        when (code) {
            AUTO -> "自动"
            NONE -> "关闭"
            ZH -> "中文"
            JA -> "日语"
            EN -> "英语"
            KO -> "韩语"
            else -> code
        }
}

object LibraryIndexer {
    private val workCodePattern = Regex("(?:RJ|VJ|BJ)\\d{6,8}", RegexOption.IGNORE_CASE)
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
    private val languageSuffix =
        Regex(
            "[._-](?:zh(?:[-_](?:cn|tw|hk|hans|hant))?|chs|cht|cn|sc|tc|ja|jp|jpn|en|eng|ko|kr|bilingual|dual|sub|cc)$",
            RegexOption.IGNORE_CASE,
        )
    private val resourceSuffix =
        Regex(
            "\\.(${(MediaFormats.playable + setOf("txt", "jpg", "jpeg", "png", "webp", "pdf")).joinToString("|")})$",
            RegexOption.IGNORE_CASE,
        )

    private val zhToken =
        Regex(
            "(?:[._\\-\\[【(]|^)(?:zh(?:[-_](?:cn|tw|hk|hans|hant))?|chs|cht|cn|sc|tc)(?:[._\\-\\]】)]|$)|中文|简中|繁中|简体|繁体|汉化|機翻|机翻",
            RegexOption.IGNORE_CASE,
        )
    private val jaToken =
        Regex(
            "(?:[._\\-\\[【(]|^)(?:ja|jp|jpn)(?:[._\\-\\]】)]|$)|日语|日文|日本語|原文",
            RegexOption.IGNORE_CASE,
        )
    private val enToken =
        Regex(
            "(?:[._\\-\\[【(]|^)(?:en|eng)(?:[._\\-\\]】)]|$)|英文|英语|english",
            RegexOption.IGNORE_CASE,
        )
    private val koToken =
        Regex(
            "(?:[._\\-\\[【(]|^)(?:ko|kr|kor)(?:[._\\-\\]】)]|$)|韩语|韩文|한국어",
            RegexOption.IGNORE_CASE,
        )

    private fun stem(name: String) = name.replace(subtitleSuffix, "").replace(resourceSuffix, "")

    internal fun subtitleBaseStem(name: String): String {
        var current = stem(name)
        while (true) {
            val stripped = current.replace(languageSuffix, "")
            if (stripped == current || stripped.isBlank()) break
            current = stripped
        }
        return current
    }

    /** Detects a coarse subtitle language code (`zh`, `ja`, `en`, `ko`) from file/path hints. */
    fun subtitleLanguage(fileName: String, relativePath: String = fileName): String? {
        val target = "$relativePath/$fileName"
        return when {
            zhToken.containsMatchIn(target) -> SubtitleLang.ZH
            jaToken.containsMatchIn(target) -> SubtitleLang.JA
            enToken.containsMatchIn(target) -> SubtitleLang.EN
            koToken.containsMatchIn(target) -> SubtitleLang.KO
            else -> null
        }
    }

    /**
     * Returns matching subtitle attachments for [track] sorted by match quality, followed by
     * remaining work subtitles.
     */
    fun subtitleCandidates(
        track: TrackEntity,
        attachments: List<AttachmentEntity>,
    ): List<AttachmentEntity> {
        val allSubtitles = attachments.filter { it.kind == "subtitle" }
        if (allSubtitles.isEmpty()) return emptyList()
        val trackDir = track.relativePath.substringBeforeLast('/', "")
        val exactStem = stem(track.fileName).lowercase()
        val baseStem = subtitleBaseStem(track.fileName).lowercase()
        return allSubtitles.sortedWith(
            compareBy<AttachmentEntity> {
                    when {
                        it.id == track.subtitleId -> 0
                        stem(it.fileName).lowercase() == exactStem -> 1
                        subtitleBaseStem(it.fileName).lowercase() == baseStem -> 2
                        number(it.fileName) == track.order && track.order < 9999 -> 3
                        else -> 4
                    }
                }
                .thenBy {
                    if (it.relativePath.substringBeforeLast('/', "") == trackDir) 0 else 1
                }
                .thenBy { if (it.fileName.extension() == "vtt") 0 else 1 }
                .thenBy { it.relativePath },
        )
    }

    /**
     * Automatically selects primary and optional secondary subtitles for [track] based on preferred
     * language settings, restricted to subtitles that genuinely match [track].
     */
    fun pickAutoSubtitles(
        track: TrackEntity,
        attachments: List<AttachmentEntity>,
        primaryLang: String = SubtitleLang.AUTO,
        secondaryLang: String = SubtitleLang.NONE,
    ): Pair<String?, String?> {
        if (primaryLang == SubtitleLang.AUTO && secondaryLang == SubtitleLang.NONE) {
            return track.subtitleId to null
        }
        val exactStem = stem(track.fileName).lowercase()
        val baseStem = subtitleBaseStem(track.fileName).lowercase()
        val matching =
            subtitleCandidates(track, attachments).filter { candidate ->
                candidate.id == track.subtitleId ||
                    stem(candidate.fileName).lowercase() == exactStem ||
                    subtitleBaseStem(candidate.fileName).lowercase() == baseStem ||
                    (track.order < 9999 && number(candidate.fileName) == track.order)
            }
        if (matching.isEmpty()) return track.subtitleId to null
        val primary =
            if (primaryLang == SubtitleLang.AUTO) {
                matching.firstOrNull { it.id == track.subtitleId } ?: matching.first()
            } else {
                matching.firstOrNull {
                    subtitleLanguage(it.fileName, it.relativePath) == primaryLang
                } ?: matching.firstOrNull { it.id == track.subtitleId } ?: matching.first()
            }
        val secondary =
            if (secondaryLang == SubtitleLang.NONE || secondaryLang == primaryLang) {
                null
            } else {
                matching.firstOrNull { candidate ->
                    candidate.id != primary.id &&
                        subtitleLanguage(candidate.fileName, candidate.relativePath) ==
                            secondaryLang
                }
            }
        return primary.id to secondary?.id
    }

    fun number(name: String): Int =
        leadingNumber.find(name.replace(codePrefix, ""))?.groupValues?.get(1)?.toIntOrNull() ?: 9999

    fun trackTitle(name: String): String =
        stem(name).replace(codePrefix, "").replace(leadingNumberSep, "").trim().ifBlank {
            stem(name)
        }

    private fun rootOf(path: String): String {
        val dirs = path.split('/').dropLast(1)
        val matches = dirs.mapIndexedNotNull { index, name ->
            workCodePattern.find(name)?.value?.uppercase()?.let { index to it }
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
        workOverride: WorkEntity? = null,
    ): IndexedLibrary {
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
        groups.toSortedMap().forEach { (root, group) ->
            val name = root.basename().ifBlank { "未命名作品" }
            val rj = workCodePattern.find(name)?.value?.uppercase()
            // Per-source directory identity stays stable automatically when tracks are
            // added/removed.
            val workId = workOverride?.id ?: stableId("$sourceId|$root")
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
                    id = stableId("$workId|$relative"),
                    workId = workId,
                    title = trackTitle(fileName),
                    fileName = fileName,
                    relativePath = relative,
                    kind = kind,
                    uri = file.uri,
                    size = file.size,
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
                name.replace(workCodePattern, "").replace(bracketPattern, "").trim().ifBlank {
                    name
                }
            val series =
                root
                    .split('/')
                    .dropLast(1)
                    .lastOrNull()
                    ?.takeUnless { workCodePattern.containsMatchIn(it) }
                    ?.replace(seriesFrame, "")
            works +=
                workOverride?.copy(
                    coverId = workOverride.coverId ?: cover,
                    sourceId = workOverride.sourceId ?: sourceId,
                )
                    ?: WorkEntity(
                        id = workId,
                        rj = rj,
                        title = title,
                        originalTitle = name,
                        series = series,
                        coverId = cover,
                        sourceId = sourceId,
                    )
            attachments += resources.values
            val resourcesByStem =
                resources.entries.groupBy { stem(it.key.path.basename()).lowercase() }
            val subtitlesByBaseStem =
                resources.entries
                    .filter { it.value.kind == "subtitle" }
                    .groupBy { subtitleBaseStem(it.key.path.basename()).lowercase() }
            group
                .sortedWith(compareBy<FileEntry> { number(it.path.basename()) }.thenBy { it.path })
                .forEach { file ->
                    val fileName = file.path.basename()
                    val relative = file.path.removePrefix("$root/")
                    val exactStemKey = stem(fileName).lowercase()
                    val baseStemKey = subtitleBaseStem(fileName).lowercase()
                    val sameName = resourcesByStem[exactStemKey].orEmpty()
                    fun match(kind: String) =
                        sameName
                            .filter { it.value.kind == kind }
                            .minByOrNull {
                                if (
                                    it.key.path.substringBeforeLast('/', "") ==
                                        file.path.substringBeforeLast('/', "")
                                )
                                    0
                                else 1
                            }
                            ?.value
                            ?.id
                    val exactSubtitles = sameName.filter { it.value.kind == "subtitle" }
                    val candidateSubtitles = exactSubtitles.ifEmpty {
                        subtitlesByBaseStem[baseStemKey].orEmpty()
                    }
                    val subtitle =
                        candidateSubtitles
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
                    tracks +=
                        TrackEntity(
                            id = stableId("$workId|$relative"),
                            workId = workId,
                            title = trackTitle(fileName),
                            fileName = fileName,
                            relativePath = relative,
                            order = number(fileName),
                            subtitleId = subtitle,
                            scriptId = match("script"),
                            bonus = bonusPattern.containsMatchIn(relative),
                            uri = file.uri,
                            size = file.size,
                        )
                }
        }
        return IndexedLibrary(works, tracks, attachments)
    }
}
