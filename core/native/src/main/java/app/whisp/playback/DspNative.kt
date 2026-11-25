// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import java.nio.ByteBuffer

/**
 * Native C / ARM64 NEON SIMD audio DSP entry points. Gracefully reports [available] = false on host
 * JVM unit tests where Android native libraries are not loaded.
 */
object DspNative {
    val available: Boolean =
        try {
            System.loadLibrary("whisp_dsp")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }

    external fun processPcm16Stereo(
        input: ByteBuffer,
        inPos: Int,
        output: ByteBuffer,
        outPos: Int,
        frames: Int,
        swap: Boolean,
        mono: Boolean,
        night: Boolean,
        attackCoeff: Float,
        releaseCoeff: Float,
        initialEnvelope: Float,
    ): Float

    external fun processPcmFloatStereo(
        input: ByteBuffer,
        inPos: Int,
        output: ByteBuffer,
        outPos: Int,
        frames: Int,
        swap: Boolean,
        mono: Boolean,
        night: Boolean,
        attackCoeff: Float,
        releaseCoeff: Float,
        initialEnvelope: Float,
    ): Float
}
