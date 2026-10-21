// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

/** A decoder candidate resolved from the device codec list. */
internal data class CodecCandidate(val name: String, val hardwareAccelerated: Boolean)

/** One decoder or offload observation for the playback diagnostic report. */
data class DecoderEvent(
    val kind: String,
    val mime: String?,
    val codecs: String?,
    val decoder: String?,
    val hardwareAccelerated: Boolean?,
    val offloaded: Boolean? = null,
    val id: String? = null,
)

/** Matches an initialized decoder against the queried codec list. */
internal object DecoderAudit {
    fun resolve(
        kind: String,
        mime: String?,
        codecs: String?,
        decoderName: String?,
        candidates: List<CodecCandidate>,
    ): DecoderEvent {
        val hardware =
            decoderName
                ?.let { name -> candidates.firstOrNull { it.name == name } }
                ?.hardwareAccelerated
        return DecoderEvent(kind, mime, codecs, decoderName, hardware)
    }

    fun offloaded(offloaded: Boolean): DecoderEvent =
        DecoderEvent("offload", null, null, null, null, offloaded)
}
