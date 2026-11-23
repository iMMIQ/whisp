// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LibraryIndexerTest {
    private fun file(path: String, size: Long = 1024) =
        FileEntry(path, "content://test/$path", false, size)

    @Test
    fun videoOnlyWorksAndMixedQueuesKeepCaptionsAndResourceRoles() {
        val indexed =
            LibraryIndexer.index(
                listOf(
                    file("RJ00000001 示例/01_画面.MP4"),
                    file("RJ00000001 示例/01_画面.MP4.SRT"),
                    file("RJ00000001 示例/02_声音.wav"),
                    file("RJ00000001 示例/03_附赠.mkv"),
                    file("RJ00000001 示例/封面.jpg"),
                    file("独立视频/01_示例.webm"),
                ),
                "source",
            )
        assertEquals(2, indexed.works.size)
        assertEquals(4, indexed.tracks.size)
        val mixed = indexed.tracks.filter { it.workId == indexed.works.first { it.rj != null }.id }
        assertEquals(listOf("画面", "声音", "附赠"), mixed.map { it.title })
        assertEquals(listOf(true, false, true), mixed.map { it.isVideo })
        assertEquals(
            indexed.attachments.first { it.kind == "subtitle" }.id,
            mixed.first().subtitleId,
        )
        assertTrue(mixed.last().bonus)
        assertEquals(setOf("subtitle", "image"), indexed.attachments.map { it.kind }.toSet())
    }

    @Test
    fun indexesSyntheticDirectoryFixture() {
        val json = javaClass.getResource("/library-layout.json")!!.readText()
        val entries =
            Json.parseToJsonElement(json).jsonArray.map {
                val row = it.jsonObject
                file(
                    row.getValue("path").jsonPrimitive.content,
                    row.getValue("size").jsonPrimitive.long,
                )
            }
        val result = LibraryIndexer.index(entries, "phone")
        assertEquals(1, result.works.size)
        assertEquals(8, result.tracks.size)
        assertEquals((0..7).toList(), result.tracks.map { it.order })
        assertTrue(result.tracks.all { it.subtitleId != null })
        assertTrue(result.tracks.all { it.scriptId != null })
        assertNotNull(result.works.single().coverId)
    }

    @Test
    fun groupsNestedPackageAndMatchesDoubleExtension() {
        val root = "「Example Series」系列/RJ00000002 【简体中文版】示例作品"
        val wrapped = "$root/RJ00000002 示例作品"
        val result =
            LibraryIndexer.index(
                listOf(
                    file("$wrapped/mp3/z32_02_示例音轨.mp3"),
                    file("$wrapped/mp3/z32_00_标题朗读.mp3"),
                    file("$wrapped/mp3/z32_02_示例音轨.mp3.vtt"),
                    file("$wrapped/script/z32_02_示例音轨.txt"),
                    file("$wrapped/omake/z32_封面.jpg"),
                ),
                "nas",
            )
        assertEquals(1, result.works.size)
        assertEquals("RJ00000002", result.works.single().rj)
        assertEquals("Example Series", result.works.single().series)
        assertEquals(listOf(0, 2), result.tracks.map { it.order })
        assertEquals("示例音轨", result.tracks.last().title)
        assertNotNull(result.tracks.last().subtitleId)
        assertNotNull(result.tracks.last().scriptId)
        assertEquals(
            result.works.single().coverId,
            result.attachments.single { it.kind == "image" }.id,
        )
    }

    @Test
    fun sameWorkAcrossSourcesKeepsDistinctSourceOwnershipAndEmbeddedUris() {
        val paths = listOf("RJ00000001 测试作品/mp3/03_测试音轨.mp3", "RJ00000001 测试作品/mp3/03_测试音轨.mp3.vtt")
        val local = LibraryIndexer.index(paths.map { file(it) }, "phone")
        val remote =
            LibraryIndexer.index(
                paths.map { file(it).copy(uri = "https://nas.example/$it") },
                "nas",
            )
        assertNotEquals(local.works.single().id, remote.works.single().id)
        assertEquals("phone", local.works.single().sourceId)
        assertEquals("nas", remote.works.single().sourceId)
        assertEquals("content://test/${paths.first()}", local.tracks.single().uri)
        assertEquals("https://nas.example/${paths.first()}", remote.tracks.single().uri)
    }

    @Test
    fun differentEditionIsNotMergedJustBecauseRjMatches() {
        val first = LibraryIndexer.index(listOf(file("RJ00000001 中文/mp3/01_示例音轨.mp3", 100)), "a")
        val second = LibraryIndexer.index(listOf(file("RJ00000001 日本語/mp3/01_示例音轨.mp3", 200)), "a")
        assertNotEquals(first.works.single().id, second.works.single().id)
    }

    @Test
    fun distinctNestedWorkRemainsSeparate() {
        val indexed =
            LibraryIndexer.index(
                listOf(
                    file("RJ00000001 包/01_正篇.mp3"),
                    file("RJ00000001 包/RJ00000002 别的作品/01_正篇.mp3"),
                ),
                "a",
            )
        assertEquals(2, indexed.works.size)
        assertEquals(setOf("RJ00000001", "RJ00000002"), indexed.works.map { it.rj }.toSet())
    }

    @Test
    fun attachedFilesDoNotBecomeExtraTracks() {
        val indexed =
            LibraryIndexer.index(
                listOf(
                    file("RJ00000001 测试作品/mp3/07_附赠语音.mp3"),
                    file("RJ00000001 测试作品/mp3/07_附赠语音.mp3.vtt"),
                    file("RJ00000001 测试作品/附赠1/角色设定.jpg"),
                    file("RJ00000001 测试作品/附赠1/封面.jpg"),
                    file("RJ00000001 测试作品/附赠1/封面_介绍.jpg"),
                ),
                "a",
            )
        assertEquals(1, indexed.tracks.size)
        assertTrue(indexed.tracks.single().bonus)
        assertEquals(
            "封面.jpg",
            indexed.attachments.single { it.id == indexed.works.single().coverId }.fileName,
        )
    }

    @Test
    fun captionsUseTheMatchingRecordingDirectoryBeforeFormatPreference() {
        val indexed =
            LibraryIndexer.index(
                listOf(
                    file("RJ00000001 Example/wav/01_Intro.wav"),
                    file("RJ00000001 Example/wav/01_Intro.vtt"),
                    file("RJ00000001 Example/mp3/01_Intro.mp3"),
                    file("RJ00000001 Example/mp3/01_Intro.lrc"),
                ),
                "source",
            )
        val captions = indexed.attachments.associateBy { it.id }
        for (track in indexed.tracks) {
            assertEquals(
                track.relativePath.substringBeforeLast('/'),
                captions.getValue(track.subtitleId!!).relativePath.substringBeforeLast('/'),
            )
        }
    }

    @Test
    fun uppercaseDoubleExtensionCaptionsMatchAudio() {
        val indexed =
            LibraryIndexer.index(
                listOf(
                    file("RJ00000001 Example/01_Intro.MP3"),
                    file("RJ00000001 Example/01_INTRO.MP3.VTT"),
                ),
                "source",
            )
        assertEquals(indexed.attachments.single().id, indexed.tracks.single().subtitleId)
        assertEquals("INTRO", indexed.attachments.single().title)
    }
}
