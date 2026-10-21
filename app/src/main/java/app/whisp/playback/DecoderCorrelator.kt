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

/** A null evaluation means bypass: a fresh init always dispatches before its format event. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun formatContinuity(evaluation: DecoderReuseEvaluation?): FormatContinuity =
    when {
        evaluation == null -> FormatContinuity.BYPASS
        evaluation.result == DecoderReuseEvaluation.REUSE_RESULT_NO -> FormatContinuity.REBUILD
        else -> FormatContinuity.REUSE
    }

/**
 * Pairs each decoder initialization with the input format event from the same renderer call.
 * Renderers initialize the codec before dispatching the format event, so recording at init time
 * would read a stale or missing format.
 *
 * A pending pairing always wins over the evaluation: init and its format stay adjacent in the
 * per-kind event stream. An init without a following format is always an in-place recovery, where
 * the cached format is still current.
 *
 * A release always precedes the next init of the same renderer; only the decoder name is cleared,
 * never a pending pairing. After a release, a format without a new init is bypass and keeps a null
 * decoder.
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
    private val pending = mutableMapOf<String, String>()

    fun onDecoderInitialized(kind: String, decoder: String) {
        lastDecoder[kind] = decoder
        val existing = pending[kind]
        if (existing != null) {
            // A format event is already waiting for this init (e.g. delayed
            // rebuild): fold the decoder name into the same entry.
            sink(kind, cached[kind], decoder, existing, true)
        } else {
            val id = UUID.randomUUID().toString()
            pending[kind] = id
            sink(kind, cached[kind], decoder, id, false)
        }
    }

    fun onFormatChanged(kind: String, snapshot: FormatSnapshot?, continuity: FormatContinuity) {
        cached[kind] = snapshot
        val pendingId = pending.remove(kind)
        if (pendingId != null) {
            sink(kind, snapshot, lastDecoder[kind], pendingId, true)
        } else if (continuity == FormatContinuity.REBUILD) {
            val id = UUID.randomUUID().toString()
            pending[kind] = id
            sink(kind, snapshot, null, id, false)
        } else {
            val decoder = if (continuity == FormatContinuity.REUSE) lastDecoder[kind] else null
            sink(kind, snapshot, decoder, UUID.randomUUID().toString(), false)
        }
    }

    fun onDecoderReleased(kind: String) {
        lastDecoder.remove(kind)
    }
}
