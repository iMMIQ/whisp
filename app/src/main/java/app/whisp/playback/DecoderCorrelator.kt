// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import java.util.UUID

/** A renderer input format snapshot, detached from Media3 types for testability. */
internal data class FormatSnapshot(val mime: String?, val codecs: String?)

/** What a format event implies about the decoder that will render it. */
internal enum class FormatContinuity {
    /** The active decoder continues; pair the last known decoder. */
    REUSE,
    /** A rebuild is scheduled; the decoder is not known yet. */
    REBUILD,
    /** No decoder will render this format (bypass); pair nothing. */
    BYPASS,
}

/** Classifies format events; a preceding initialization takes priority over this hint. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun formatContinuity(evaluation: DecoderReuseEvaluation?): FormatContinuity =
    when {
        evaluation == null -> FormatContinuity.BYPASS
        evaluation.result == DecoderReuseEvaluation.REUSE_RESULT_NO -> FormatContinuity.REBUILD
        else -> FormatContinuity.REUSE
    }

/**
 * Pairs renderer input formats and decoder initializations in either event order.
 *
 * Init-first observations await a format and are abandoned when that decoder is released.
 * Format-first rebuilds await initialization across the old decoder's release. Initialization
 * completes that pairing without waiting for another format callback.
 *
 * Every emission carries a stable id so the sink can replace the provisional entry in place, no
 * matter what was recorded after it.
 */
internal class DecoderCorrelator(
    private val sink:
        (
            kind: String,
            snapshot: FormatSnapshot?,
            decoder: String?,
            id: String,
            replaceProvisional: Boolean,
        ) -> Unit,
) {
    private val cached = mutableMapOf<String, FormatSnapshot?>()
    private val lastDecoder = mutableMapOf<String, String?>()
    private val awaitingFormat = mutableMapOf<String, String>()
    private val awaitingDecoder = mutableMapOf<String, String>()

    fun onDecoderInitialized(kind: String, decoder: String) {
        lastDecoder[kind] = decoder
        val existing = awaitingDecoder.remove(kind)
        if (existing != null) {
            sink(kind, cached[kind], decoder, existing, true)
        } else {
            val id = UUID.randomUUID().toString()
            awaitingFormat[kind] = id
            sink(kind, cached[kind], decoder, id, false)
        }
    }

    fun onFormatChanged(kind: String, snapshot: FormatSnapshot?, continuity: FormatContinuity) {
        cached[kind] = snapshot
        val pendingId = awaitingFormat.remove(kind)
        // A new format supersedes any earlier format still waiting for initialization.
        awaitingDecoder.remove(kind)
        if (pendingId != null) {
            sink(kind, snapshot, lastDecoder[kind], pendingId, true)
        } else if (continuity == FormatContinuity.REBUILD) {
            val id = UUID.randomUUID().toString()
            awaitingDecoder[kind] = id
            sink(kind, snapshot, null, id, false)
        } else {
            val decoder = if (continuity == FormatContinuity.REUSE) lastDecoder[kind] else null
            sink(kind, snapshot, decoder, UUID.randomUUID().toString(), false)
        }
    }

    fun onDecoderReleased(kind: String) {
        lastDecoder.remove(kind)
        awaitingFormat.remove(kind)
    }
}
