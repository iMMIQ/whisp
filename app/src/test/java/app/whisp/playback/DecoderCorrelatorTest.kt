// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import org.junit.Assert.*
import org.junit.Test

class DecoderCorrelatorTest {
    private data class Emission(
        val kind: String,
        val snapshot: FormatSnapshot?,
        val decoder: String?,
        val id: String,
        val replace: Boolean,
    )

    private fun correlator(log: MutableList<Emission>) =
        DecoderCorrelator { kind, snapshot, decoder, id, replace ->
            log += Emission(kind, snapshot, decoder, id, replace)
        }

    @Test
    fun decoderInitPairsWithItsOwnFormatEvent() {
        val log = mutableListOf<Emission>()
        val correlator = correlator(log)
        // A fresh init dispatches before its format event, so the pending
        // pairing wins no matter what the evaluation says.
        correlator.onDecoderInitialized("audio", "aac-decoder")
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/aac", null),
            FormatContinuity.BYPASS,
        )
        correlator.onDecoderInitialized("audio", "flac-decoder")
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/flac", "fLaC"),
            FormatContinuity.BYPASS,
        )
        assertEquals(4, log.size)
        assertEquals(Emission("audio", null, "aac-decoder", log[0].id, false), log[0])
        assertEquals(
            Emission("audio", FormatSnapshot("audio/aac", null), "aac-decoder", log[0].id, true),
            log[1],
        )
        assertEquals(
            Emission("audio", FormatSnapshot("audio/aac", null), "flac-decoder", log[2].id, false),
            log[2],
        )
        assertEquals(
            Emission(
                "audio",
                FormatSnapshot("audio/flac", "fLaC"),
                "flac-decoder",
                log[2].id,
                true,
            ),
            log[3],
        )
        assertNotEquals(log[0].id, log[2].id)
    }

    @Test
    fun videoInitPairsWithItsOwnFormatEvent() {
        val log = mutableListOf<Emission>()
        val correlator = correlator(log)
        correlator.onDecoderInitialized("video", "avc-decoder")
        correlator.onFormatChanged(
            "video",
            FormatSnapshot("video/avc", "avc1"),
            FormatContinuity.BYPASS,
        )
        assertEquals(2, log.size)
        assertEquals(log[0].id, log[1].id)
        assertEquals(
            Emission("video", FormatSnapshot("video/avc", "avc1"), "avc-decoder", log[0].id, true),
            log[1],
        )
    }

    @Test
    fun reusedDecoderKeepsTheLatestFormat() {
        val log = mutableListOf<Emission>()
        val correlator = correlator(log)
        correlator.onDecoderInitialized("audio", "aac-decoder")
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/aac", null),
            FormatContinuity.BYPASS,
        )
        // No re-init: the continuing decoder still applies.
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/aac", "mp4a"),
            FormatContinuity.REUSE,
        )
        assertEquals(3, log.size)
        assertEquals(
            Emission("audio", FormatSnapshot("audio/aac", "mp4a"), "aac-decoder", log[2].id, false),
            log[2],
        )
    }

    @Test
    fun kindsDoNotInterfere() {
        val log = mutableListOf<Emission>()
        val correlator = correlator(log)
        correlator.onDecoderInitialized("audio", "aac-decoder")
        correlator.onDecoderInitialized("video", "avc-decoder")
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/aac", null),
            FormatContinuity.BYPASS,
        )
        correlator.onFormatChanged(
            "video",
            FormatSnapshot("video/avc", null),
            FormatContinuity.BYPASS,
        )
        assertEquals(4, log.size)
        assertEquals(log[0].id, log[2].id)
        assertEquals(log[1].id, log[3].id)
        assertTrue(log[2].replace)
        assertTrue(log[3].replace)
    }

    @Test
    fun releasedDecoderDoesNotAttachToBypassFormat() {
        val log = mutableListOf<Emission>()
        val correlator = correlator(log)
        // init AAC -> format AAC -> release AAC -> MP3 bypass format.
        correlator.onDecoderInitialized("audio", "aac-decoder")
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/aac", null),
            FormatContinuity.BYPASS,
        )
        correlator.onDecoderReleased("audio")
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/mpeg", "mp3"),
            FormatContinuity.BYPASS,
        )
        assertEquals(3, log.size)
        assertEquals(
            Emission("audio", FormatSnapshot("audio/mpeg", "mp3"), null, log[2].id, false),
            log[2],
        )
    }

    @Test
    fun initAfterReleasePairsFresh() {
        val log = mutableListOf<Emission>()
        val correlator = correlator(log)
        correlator.onDecoderInitialized("audio", "aac-decoder")
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/aac", null),
            FormatContinuity.BYPASS,
        )
        correlator.onDecoderReleased("audio")
        // The released init never produced output; the bypass format replaces it in place.
        correlator.onDecoderInitialized("audio", "mp3-decoder")
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/mpeg", "mp3"),
            FormatContinuity.BYPASS,
        )
        assertEquals(4, log.size)
        assertEquals(log[2].id, log[3].id)
        assertEquals(
            Emission("audio", FormatSnapshot("audio/mpeg", "mp3"), "mp3-decoder", log[2].id, true),
            log[3],
        )
    }

    @Test
    fun bufferedRebuildPairsAcrossRelease() {
        val log = mutableListOf<Emission>()
        val correlator = correlator(log)
        // init AAC -> format AAC -> format FLAC (rebuild scheduled, drain pending)
        // -> release AAC -> init FLAC -> format FLAC.
        correlator.onDecoderInitialized("audio", "aac-decoder")
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/aac", null),
            FormatContinuity.BYPASS,
        )
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/flac", "fLaC"),
            FormatContinuity.REBUILD,
        )
        correlator.onDecoderReleased("audio")
        correlator.onDecoderInitialized("audio", "flac-decoder")
        correlator.onFormatChanged(
            "audio",
            FormatSnapshot("audio/flac", "fLaC"),
            FormatContinuity.REUSE,
        )
        val flacEntries = log.filter { it.snapshot?.mime == "audio/flac" }
        // Provisional, in-place decoder update, then the paired format: one id throughout.
        assertEquals(3, flacEntries.size)
        assertTrue(flacEntries.all { it.id == flacEntries[0].id })
        assertEquals("flac-decoder", flacEntries.last().decoder)
        assertTrue(flacEntries.none { it.decoder == "aac-decoder" })
    }
}
