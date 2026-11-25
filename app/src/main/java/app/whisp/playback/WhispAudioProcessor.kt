// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Low-overhead PCM audio processor for ASMR / voice listening:
 * - Stereo channel swap (fixes inverted binaural recordings or reversed sleep earbuds)
 * - Mono downmix (for single-ear side sleeping)
 * - Night dynamic softener (gentle whisper lift + soft-knee limiter to prevent sudden loud peaks)
 *
 * All toggles are volatile so toggling takes effect on the very next audio frame without resetting
 * playback, while [AudioOffload] keeps hardware DSP offload enabled whenever all toggles are off.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class WhispAudioProcessor : BaseAudioProcessor() {
    @Volatile var swapChannels: Boolean = false

    @Volatile var monoAudio: Boolean = false

    @Volatile var nightDynamics: Boolean = false

    private var envelope: Float = 0f
    private var attackCoeff: Float = 0.05f
    private var releaseCoeff: Float = 0.002f
    private var pcm16Scratch = ShortArray(0)
    private var pcmFloatScratch = FloatArray(0)

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
                inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val sr = inputAudioFormat.sampleRate.coerceAtLeast(8000).toDouble()
        // Fast 4 ms attack to catch sudden bangs/laughs, smooth 180 ms release to avoid pumping.
        attackCoeff = (1.0 - exp(-1.0 / (0.004 * sr))).toFloat()
        releaseCoeff = (1.0 - exp(-1.0 / (0.180 * sr))).toFloat()
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        envelope = 0f
    }

    override fun onReset() {
        envelope = 0f
        pcm16Scratch = ShortArray(0)
        pcmFloatScratch = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return
        val swap = swapChannels
        val mono = monoAudio
        val night = nightDynamics
        val out = replaceOutputBuffer(remaining)
        if (!swap && !mono && !night) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT ->
                processPcm16(inputBuffer, out, inputAudioFormat.channelCount, swap, mono, night)
            C.ENCODING_PCM_FLOAT ->
                processPcmFloat(inputBuffer, out, inputAudioFormat.channelCount, swap, mono, night)
            else -> out.put(inputBuffer)
        }
        out.flip()
    }

    private fun processPcm16(
        input: ByteBuffer,
        output: ByteBuffer,
        channels: Int,
        swap: Boolean,
        mono: Boolean,
        night: Boolean,
    ) {
        if (channels == 2) {
            val frames = input.remaining() / 4
            val sampleCount = frames * 2
            if (sampleCount > 0) {
                if (pcm16Scratch.size < sampleCount) pcm16Scratch = ShortArray(sampleCount)
                val samples = pcm16Scratch
                val inShorts = input.asShortBuffer()
                inShorts.get(samples, 0, sampleCount)
                input.position(input.position() + sampleCount * 2)
                if (!night) {
                    // Bit-exact integer fast path on contiguous array without float roundtrip.
                    var i = 0
                    if (mono) {
                        while (i < sampleCount) {
                            val m = ((samples[i].toInt() + samples[i + 1].toInt()) / 2).toShort()
                            samples[i] = m
                            samples[i + 1] = m
                            i += 2
                        }
                    } else if (swap) {
                        while (i < sampleCount) {
                            val tmp = samples[i]
                            samples[i] = samples[i + 1]
                            samples[i + 1] = tmp
                            i += 2
                        }
                    }
                } else {
                    var i = 0
                    while (i < sampleCount) {
                        var l = samples[i] * INV_PCM16
                        var r = samples[i + 1] * INV_PCM16
                        if (mono) {
                            val m = (l + r) * 0.5f
                            l = m
                            r = m
                        } else if (swap) {
                            val tmp = l
                            l = r
                            r = tmp
                        }
                        val gain = computeNightGain(max(abs(l), abs(r)))
                        samples[i] = floatToPcm16(softClip(l * gain))
                        samples[i + 1] = floatToPcm16(softClip(r * gain))
                        i += 2
                    }
                }
                output.asShortBuffer().put(samples, 0, sampleCount)
                output.position(output.position() + sampleCount * 2)
            }
            while (input.hasRemaining()) output.put(input.get())
            return
        }
        val frameBytes = channels * 2
        while (input.remaining() >= frameBytes) {
            var peak = 0f
            val startPos = input.position()
            var sum = 0f
            for (c in 0 until channels) {
                val sample = input.getShort(startPos + c * 2) * INV_PCM16
                sum += sample
                peak = max(peak, abs(sample))
            }
            val gain = if (night) computeNightGain(peak) else 1f
            val monoVal = if (mono && channels > 1) sum / channels else 0f
            for (c in 0 until channels) {
                val raw =
                    if (mono && channels > 1) monoVal
                    else input.getShort(startPos + c * 2) * INV_PCM16
                val processed = if (night) softClip(raw * gain) else raw
                output.putShort(floatToPcm16(processed))
            }
            input.position(startPos + frameBytes)
        }
        while (input.hasRemaining()) output.put(input.get())
    }

    private fun processPcmFloat(
        input: ByteBuffer,
        output: ByteBuffer,
        channels: Int,
        swap: Boolean,
        mono: Boolean,
        night: Boolean,
    ) {
        if (channels == 2) {
            val frames = input.remaining() / 8
            val sampleCount = frames * 2
            if (sampleCount > 0) {
                if (pcmFloatScratch.size < sampleCount) pcmFloatScratch = FloatArray(sampleCount)
                val samples = pcmFloatScratch
                input.asFloatBuffer().get(samples, 0, sampleCount)
                input.position(input.position() + sampleCount * 4)
                var i = 0
                while (i < sampleCount) {
                    var l = samples[i]
                    var r = samples[i + 1]
                    if (mono) {
                        val m = (l + r) * 0.5f
                        l = m
                        r = m
                    } else if (swap) {
                        val tmp = l
                        l = r
                        r = tmp
                    }
                    if (night) {
                        val gain = computeNightGain(max(abs(l), abs(r)))
                        l = softClip(l * gain)
                        r = softClip(r * gain)
                    }
                    samples[i] = l
                    samples[i + 1] = r
                    i += 2
                }
                output.asFloatBuffer().put(samples, 0, sampleCount)
                output.position(output.position() + sampleCount * 4)
            }
            while (input.hasRemaining()) output.put(input.get())
            return
        }
        val frameBytes = channels * 4
        while (input.remaining() >= frameBytes) {
            var peak = 0f
            val startPos = input.position()
            var sum = 0f
            for (c in 0 until channels) {
                val sample = input.getFloat(startPos + c * 4)
                sum += sample
                peak = max(peak, abs(sample))
            }
            val gain = if (night) computeNightGain(peak) else 1f
            val monoVal = if (mono && channels > 1) sum / channels else 0f
            for (c in 0 until channels) {
                val raw = if (mono && channels > 1) monoVal else input.getFloat(startPos + c * 4)
                val processed = if (night) softClip(raw * gain) else raw
                output.putFloat(processed)
            }
            input.position(startPos + frameBytes)
        }
        while (input.hasRemaining()) output.put(input.get())
    }

    internal fun computeNightGain(peak: Float): Float {
        val coeff = if (peak > envelope) attackCoeff else releaseCoeff
        envelope += coeff * (peak - envelope)
        val env = envelope
        // Gentle makeup (+3 dB) for quiet whispers while strongly compressing peaks above -16 dBFS.
        val makeup = 1.42f
        val threshold = 0.16f
        if (env <= threshold) return makeup
        // 4:1 compression ratio: (env / threshold)^(1/4) == sqrt(sqrt(env / threshold)).
        val compressed = threshold * sqrt(sqrt(env * INV_THRESHOLD))
        val target = (compressed / env) * makeup
        // Hard ceiling around -2.5 dBFS (0.75) so sudden claps or laughs stay comfortable at night.
        val ceilingGain = if (env > 0.0001f) 0.75f / env else makeup
        return minOf(target, ceilingGain).coerceIn(0.08f, makeup)
    }

    private fun softClip(sample: Float): Float {
        val a = abs(sample)
        if (a <= 0.85f) return sample
        // Smooth asymptotic knee from 0.85 toward 0.99 to prevent harsh digital clipping.
        val excess = a - 0.85f
        val shaped = 0.85f + 0.14f * (excess / (excess + 0.14f))
        return if (sample >= 0f) shaped else -shaped
    }

    private fun floatToPcm16(sample: Float): Short =
        (sample.coerceIn(-1f, 1f) * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()

    companion object {
        private const val INV_PCM16 = 1f / 32768f
        private const val INV_THRESHOLD = 1f / 0.16f
    }
}
