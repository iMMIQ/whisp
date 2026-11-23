// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import kotlinx.serialization.json.*

object DlsiteFileTree {
    private val safeNamePattern = Regex("[A-Za-z0-9_.-]{1,200}")
    private val illegalPathChars = Regex("[\\\\/\\u0000]")
    private val bonusPattern = Regex("特典|おまけ|omake|bonus|附赠", RegexOption.IGNORE_CASE)
    private val supportedAudioExtensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")

    fun index(
        work: DlsiteOwnedWork,
        sourceId: String,
        generation: Long,
        tree: JsonObject,
    ): IndexedLibrary {
        val entity = work.entity(sourceId)
        val files = tree["playfile"] as? JsonObject ?: throw IOException("DLsite 文件目录格式错误")
        val nodes = tree["tree"] as? JsonArray ?: throw IOException("DLsite 文件目录格式错误")
        val tracks = mutableListOf<TrackEntity>()
        val attachments = linkedMapOf<String, AttachmentEntity>()
        val locations = linkedMapOf<String, LocationEntity>()
        val seen = mutableSetOf<String>()
        var visited = 0
        fun resource(id: String, optimized: JsonObject) {
            val name = optimized.text("name")
            require(safeNamePattern.matches(name) && !name.contains("..")) { "DLsite 文件标识无效" }
            locations[id] =
                LocationEntity(
                    stableId("$sourceId|$id"),
                    id,
                    sourceId,
                    "dlsite-resource://${work.workno}/optimized/$name",
                    optimized["length"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0) ?: 0,
                    generation = generation,
                )
        }
        fun subtitle(hash: String, relative: String): String? {
            val item = files[hash] as? JsonObject ?: return null
            val optimized =
                (item["text"] as? JsonObject)?.get("optimized") as? JsonObject ?: return null
            val id = stableId("${entity.id}|subtitle|$hash")
            attachments[id] =
                AttachmentEntity(
                    id,
                    entity.id,
                    LibraryIndexer.trackTitle(relative.substringAfterLast('/')),
                    relative.substringAfterLast('/'),
                    relative,
                    "subtitle",
                )
            resource(id, optimized)
            return id
        }
        fun walk(children: JsonArray, parent: String, depth: Int) {
            require(depth <= 32) { "作品目录层级过多" }
            children.forEach { element ->
                require(++visited <= 20_000) { "作品文件数量过多" }
                val node = element as? JsonObject ?: return@forEach
                val name = node.text("name").replace(illegalPathChars, "_").trim()
                if (name.isBlank() || name in setOf(".", "..")) return@forEach
                val path = listOf(parent, name).filter(String::isNotBlank).joinToString("/")
                if (node.text("type") == "folder") {
                    (node["children"] as? JsonArray)?.let { walk(it, path, depth + 1) }
                    return@forEach
                }
                if (node.text("type") == "hidden") return@forEach
                val hash = node.text("hashname")
                val file = files[hash] as? JsonObject ?: return@forEach
                if (!seen.add(path)) throw IOException("作品目录存在重复文件名")
                if (file.text("type") == "audio") {
                    val audio = file["audio"] as? JsonObject ?: return@forEach
                    val optimized = audio["optimized"] as? JsonObject ?: return@forEach
                    val extension = optimized.text("name").substringAfterLast('.', "").lowercase()
                    if (extension !in supportedAudioExtensions) return@forEach
                    val stem = name.substringBeforeLast('.', name)
                    val relative =
                        listOf(parent, "$stem.$extension")
                            .filter(String::isNotBlank)
                            .joinToString("/")
                    val id = stableId("${entity.id}|$path|$hash")
                    val seconds = optimized["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val subtitle =
                        audio.text("vtt").takeIf(String::isNotBlank)?.let {
                            subtitle(
                                it,
                                listOf(parent, "$stem.vtt")
                                    .filter(String::isNotBlank)
                                    .joinToString("/"),
                            )
                        }
                    tracks +=
                        TrackEntity(
                            id,
                            entity.id,
                            LibraryIndexer.trackTitle(name),
                            relative.substringAfterLast('/'),
                            relative,
                            LibraryIndexer.number(name),
                            if (seconds.isFinite() && seconds > 0) (seconds * 1000).toLong() else 0,
                            subtitleId = subtitle,
                            bonus = bonusPattern.containsMatchIn(path),
                        )
                    resource(id, optimized)
                } else if (
                    file.text("type") == "text" &&
                        name.substringAfterLast('.', "").lowercase() == "txt"
                ) {
                    val optimized =
                        (file["text"] as? JsonObject)?.get("optimized") as? JsonObject
                            ?: return@forEach
                    val id = stableId("${entity.id}|text|$hash")
                    attachments[id] =
                        AttachmentEntity(
                            id,
                            entity.id,
                            LibraryIndexer.trackTitle(name),
                            name,
                            path,
                            "script",
                        )
                    resource(id, optimized)
                }
            }
        }
        walk(nodes, "", 0)
        if (tracks.isEmpty()) throw DlsiteNoPlayableAudioException("作品暂无可播放音轨")
        return IndexedLibrary(
            listOf(entity),
            tracks.sortedWith(compareBy<TrackEntity> { it.order }.thenBy { it.relativePath }),
            attachments.values.toList(),
            locations.values.toList(),
        )
    }
}

private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
