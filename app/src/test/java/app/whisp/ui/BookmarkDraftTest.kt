// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import app.whisp.data.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookmarkDraftTest {
    @Test
    fun cuePositionIncludesSubtitleOffset() {
        val draft = BookmarkDraft.fromCue("track", Cue(60_000, 62_000, "台词"), 500)
        assertEquals("track", draft.trackId)
        assertEquals(60_500, draft.position)
        assertEquals(62_500L, draft.end)
        assertEquals("台词", draft.title)
    }

    @Test
    fun negativePositionIsClampedAndOpenEndStaysNull() {
        val draft = BookmarkDraft.fromCue("track", Cue(100, Long.MAX_VALUE, "尾句"), -500)
        assertEquals(0, draft.position)
        assertNull(draft.end)
    }

    @Test
    fun currentPositionDraftKeepsPositionAndTitle() {
        val draft = BookmarkDraft.atPosition("track", 12_345, "标题")
        assertEquals(12_345, draft.position)
        assertNull(draft.end)
        assertEquals("标题", draft.title)
    }

    @Test
    fun loopDraftRetainsBothEndpoints() {
        val draft = BookmarkDraft.forLoop("track", 5_000, 12_000, "循环片段")
        assertEquals(5_000, draft.position)
        assertEquals(12_000L, draft.end)
        assertEquals("循环片段", draft.title)
    }
}
