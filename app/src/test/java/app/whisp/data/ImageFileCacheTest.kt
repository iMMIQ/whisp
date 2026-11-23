// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageFileCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun evictsLeastRecentlyUsedAndKeepsJustWrittenImage() {
        val root = temporary.newFolder()
        val cache = ImageFileCache(root, 10)
        cache.put("first", ByteArray(4))
        cache.put("second", ByteArray(4))
        File(root, "first").setLastModified(1)
        File(root, "second").setLastModified(2)
        cache.get("first")
        cache.put("third", ByteArray(4))
        assertNotNull(cache.get("first"))
        assertNull(cache.get("second"))
        assertNotNull(cache.get("third"))
        assertEquals(8, cache.size())
    }

    @Test
    fun restartEnforcesLimitAndRemovesInterruptedTemporaryWrites() {
        val root = temporary.newFolder()
        File(root, "old").apply {
            writeBytes(ByteArray(8))
            setLastModified(1)
        }
        File(root, "new").apply {
            writeBytes(ByteArray(8))
            setLastModified(2)
        }
        File(root, "interrupted.tmp").writeBytes(ByteArray(20))
        val cache = ImageFileCache(root, 10)
        assertNull(cache.get("old"))
        assertNotNull(cache.get("new"))
        assertFalse(File(root, "interrupted.tmp").exists())
        assertEquals(8, cache.size())
    }

    @Test
    fun clearDoesNotTouchSiblingCustomArtworkOrOfflineFiles() {
        val root = temporary.newFolder()
        val custom = File(root, "custom").apply { writeText("keep") }
        val offline = File(root, "offline").apply { writeText("keep") }
        val cache = ImageFileCache(File(root, "cache"), 10)
        cache.put("cover", ByteArray(4))
        cache.clear()
        assertEquals(0, cache.size())
        assertEquals("keep", custom.readText())
        assertEquals("keep", offline.readText())
    }

    @Test
    fun oversizedEntryDoesNotDisplaceValidCache() {
        val cache = ImageFileCache(temporary.newFolder(), 4)
        cache.put("keep", byteArrayOf(1, 2, 3))
        try {
            cache.put("huge", ByteArray(5))
            fail("Expected limit")
        } catch (_: IllegalArgumentException) {}
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("keep")!!.readBytes())
        assertNull(cache.get("huge"))
    }

    @Test
    fun replacementIsAtomicAndLeavesNoTemporaryFiles() {
        val root = temporary.newFolder()
        val cache = ImageFileCache(root, 10)
        cache.put("cover", byteArrayOf(1))
        cache.put("cover", byteArrayOf(2, 3))
        assertArrayEquals(byteArrayOf(2, 3), cache.get("cover")!!.readBytes())
        assertEquals(listOf("cover"), root.list()!!.toList())
    }

    @Test
    fun deferredInitializationAvoidsConstructorIoUntilInitialized() {
        val root = temporary.newFolder()
        val stale = File(root, "interrupted.tmp").apply { writeBytes(ByteArray(12)) }
        val cache = ImageFileCache(root, 10, eagerInit = false)
        assertTrue("Deferred cache must not touch disk in constructor", stale.exists())
        cache.initialize()
        assertFalse(stale.exists())
        cache.put("a", ByteArray(4))
        assertNotNull(cache.peek("a"))
        assertTrue(File(root, "a").delete())
        assertNull(cache.peek("a"))
        assertEquals(0L, cache.size())
    }
}
