// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import org.junit.Assert.*
import org.junit.Test

class DecoderAuditTest {
    private val candidates =
        listOf(
            CodecCandidate("c2.android.aac.decoder", true),
            CodecCandidate("OMX.google.aac.decoder", false),
        )

    @Test
    fun initializedDecoderKeepsItsHardwareFlag() {
        val event =
            DecoderAudit.resolve(
                "audio",
                "audio/mp4a-latm",
                "mp4a.40.2",
                "c2.android.aac.decoder",
                candidates,
            )
        assertEquals("audio", event.kind)
        assertEquals("audio/mp4a-latm", event.mime)
        assertEquals("c2.android.aac.decoder", event.decoder)
        assertEquals(true, event.hardwareAccelerated)
    }

    @Test
    fun softwareDecoderIsMarked() {
        val event =
            DecoderAudit.resolve(
                "audio",
                "audio/mp4a-latm",
                null,
                "OMX.google.aac.decoder",
                candidates,
            )
        assertEquals(false, event.hardwareAccelerated)
    }

    @Test
    fun unknownDecoderKeepsNullFlag() {
        val event = DecoderAudit.resolve("video", "video/avc", null, "OMX.vendor.avc", candidates)
        assertNull(event.hardwareAccelerated)
        assertEquals("OMX.vendor.avc", event.decoder)
    }

    @Test
    fun missingDecoderKeepsNullFlag() {
        val event = DecoderAudit.resolve("audio", "audio/flac", "fLaC", null, candidates)
        assertNull(event.decoder)
        assertNull(event.hardwareAccelerated)
    }

    @Test
    fun offloadTransitionsAreRecorded() {
        assertEquals(true, DecoderAudit.offloaded(true).offloaded)
        assertEquals("offload", DecoderAudit.offloaded(false).kind)
    }
}
