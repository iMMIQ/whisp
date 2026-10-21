// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import app.whisp.playback.DecoderEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlaybackDiagnosticsTest {
    private fun diagnostics() =
        PlaybackDiagnostics(RuntimeEnvironment.getApplication()).also { it.clear() }

    @Test
    fun recordedEventsRoundTrip() {
        val diagnostics = diagnostics()
        diagnostics.record(
            DecoderEvent("audio", "audio/mp4a-latm", "mp4a.40.2", "c2 decoder", true),
        )
        diagnostics.record(DecoderEvent("offload", null, null, null, null, true))
        val snapshot = diagnostics.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals("c2 decoder", snapshot[0].decoder)
        assertEquals(true, snapshot[0].hardwareAccelerated)
        assertEquals(true, snapshot[1].offloaded)
        assertNull(snapshot[1].mime)
    }

    @Test
    fun onlyTheLatestEventsAreKept() {
        val diagnostics = diagnostics()
        repeat(70) { index ->
            diagnostics.record(
                DecoderEvent("audio", "audio/mp4a-latm", null, "decoder-$index", true),
            )
        }
        val snapshot = diagnostics.snapshot()
        assertEquals(64, snapshot.size)
        assertEquals("decoder-6", snapshot.first().decoder)
        assertEquals("decoder-69", snapshot.last().decoder)
    }

    @Test
    fun replaceByIdFindsTheProvisionalAcrossOffload() {
        val diagnostics = diagnostics()
        diagnostics.record(DecoderEvent("audio", null, null, "aac-decoder", null, id = "init-1"))
        diagnostics.record(DecoderEvent("offload", null, null, null, null, true, id = "off-1"))
        diagnostics.replaceById(
            "init-1",
            DecoderEvent("audio", "audio/mp4a-latm", null, "aac-decoder", true, id = "init-1"),
        )
        val snapshot = diagnostics.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals("audio/mp4a-latm", snapshot[0].mime)
        assertEquals("offload", snapshot[1].kind)
    }

    @Test
    fun replaceByIdAppendsWhenMissing() {
        val diagnostics = diagnostics()
        diagnostics.replaceById(
            "missing",
            DecoderEvent("audio", "audio/aac", null, "aac-decoder", true, id = "missing"),
        )
        assertEquals("missing", diagnostics.snapshot().single().id)
    }

    @Test
    fun replaceByIdKeepsTheCap() {
        val diagnostics = diagnostics()
        repeat(64) { index ->
            diagnostics.record(
                DecoderEvent("audio", null, null, "decoder-$index", null, id = "id-$index"),
            )
        }
        diagnostics.replaceById(
            "id-0",
            DecoderEvent("audio", "audio/aac", null, "decoder-0", true, id = "id-0"),
        )
        val snapshot = diagnostics.snapshot()
        assertEquals(64, snapshot.size)
        assertEquals("audio/aac", snapshot.first().mime)
    }
}
