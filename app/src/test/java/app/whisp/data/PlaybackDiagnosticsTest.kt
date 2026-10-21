// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import app.whisp.playback.DecoderCorrelator
import app.whisp.playback.DecoderEvent
import app.whisp.playback.FormatContinuity
import app.whisp.playback.FormatSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlaybackDiagnosticsTest {
    private fun diagnostics() =
        PlaybackDiagnostics(RuntimeEnvironment.getApplication()).also { it.clear() }

    private fun correlator(diagnostics: PlaybackDiagnostics) =
        DecoderCorrelator { kind, format, decoder, id, replace ->
            val event = DecoderEvent(kind, format?.mime, format?.codecs, decoder, null, id = id)
            if (replace) diagnostics.replaceById(id, event) else diagnostics.record(event)
        }

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

    @Test
    fun consecutiveBufferedRebuildsPreserveEachStoredDecoder() {
        val diagnostics = diagnostics()
        val correlator = correlator(diagnostics)
        correlator.onDecoderInitialized("audio", "aac-decoder")
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/mp4a-latm", "mp4a.40.2"),
            FormatContinuity.BYPASS,
        )
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/flac", "fLaC"),
            FormatContinuity.REBUILD,
        )
        diagnostics.record(DecoderEvent("offload", null, null, null, null, false))
        correlator.onDecoderReleased("audio")
        correlator.onDecoderInitialized("audio", "flac-decoder")
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/mpeg", "mp3"),
            FormatContinuity.REBUILD,
        )
        correlator.onDecoderReleased("audio")
        correlator.onDecoderInitialized("audio", "mp3-decoder")

        val events = diagnostics.snapshot()
        assertEquals(listOf("audio", "audio", "offload", "audio"), events.map { it.kind })
        val audio = events.filter { it.kind == "audio" }
        assertEquals(listOf("audio/mp4a-latm", "audio/flac", "audio/mpeg"), audio.map { it.mime })
        assertEquals(listOf("aac-decoder", "flac-decoder", "mp3-decoder"), audio.map { it.decoder })
        assertEquals(listOf("mp4a.40.2", "fLaC", "mp3"), audio.map { it.codecs })
        assertEquals(3, audio.map { it.id }.toSet().size)
    }

    @Test
    fun bypassAfterBufferedRebuildPreservesTheCompletedRecord() {
        val diagnostics = diagnostics()
        val correlator = correlator(diagnostics)
        correlator.onDecoderInitialized("audio", "aac-decoder")
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/mp4a-latm", null),
            FormatContinuity.BYPASS,
        )
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/flac", null),
            FormatContinuity.REBUILD,
        )
        correlator.onDecoderReleased("audio")
        correlator.onDecoderInitialized("audio", "flac-decoder")
        correlator.onDecoderReleased("audio")
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/mpeg", null),
            FormatContinuity.BYPASS,
        )

        val events = diagnostics.snapshot()
        assertEquals(listOf("audio/mp4a-latm", "audio/flac", "audio/mpeg"), events.map { it.mime })
        assertEquals(listOf("aac-decoder", "flac-decoder", null), events.map { it.decoder })
        assertEquals(3, events.map { it.id }.toSet().size)
    }

    @Test
    fun reusedFormatAfterBufferedRebuildHasItsOwnRecord() {
        val diagnostics = diagnostics()
        val correlator = correlator(diagnostics)
        correlator.onDecoderInitialized("video", "avc-decoder")
        correlator.onFormatChanged(
            "video",
            FormatSnapshot("video/avc", "avc1.640028"),
            FormatContinuity.BYPASS,
        )
        correlator.onFormatChanged(
            "video",
            FormatSnapshot("video/hevc", "hvc1.1.6.L93.B0"),
            FormatContinuity.REBUILD,
        )
        correlator.onDecoderReleased("video")
        correlator.onDecoderInitialized("video", "hevc-decoder")
        correlator.onFormatChanged(
            "video",
            FormatSnapshot("video/hevc", "hvc1.1.6.L120.B0"),
            FormatContinuity.REUSE,
        )

        val events = diagnostics.snapshot()
        assertEquals(listOf("video/avc", "video/hevc", "video/hevc"), events.map { it.mime })
        assertEquals(
            listOf("avc1.640028", "hvc1.1.6.L93.B0", "hvc1.1.6.L120.B0"),
            events.map { it.codecs },
        )
        assertEquals(
            listOf("avc-decoder", "hevc-decoder", "hevc-decoder"),
            events.map { it.decoder },
        )
        assertEquals(3, events.map { it.id }.toSet().size)
    }
}
