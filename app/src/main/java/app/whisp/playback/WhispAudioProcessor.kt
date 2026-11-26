// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Low-overhead PCM audio processor for ASMR / voice listening:
 * - Stereo channel swap (fixes inverted binaural recordings or reversed sleep earbuds)
 * - Mono downmix (for single-ear side sleeping)
 * - Stereo L/R balance attenuation (for uneven ear fit or side-sleeping preference)
 * - 80 Hz low-cut rumble filter (reduces mic handling noise & breath pops)
 * - Per-work loudness gain (-12 dB .. +12 dB) with soft-clip safety
 * - 3-tier night dynamic softener (gentle / standard / strong whisper lift + peak limiter)
 *
 * All parameters are volatile so toggling takes effect on the very next audio frame without
 * resetting playback, while [AudioOffload] keeps hardware DSP offload enabled whenever all effects
 * are neutral.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class WhispAudioProcessor : BaseAudioProcessor() {
    @Volatile var swapChannels: Boolean = false

    @Volatile var monoAudio: Boolean = false

    @Volatile var nightDynamics: Boolean = false

    @Volatile var nightDynamicsStrength: Int = 2

    @Volatile var stereoBalance: Float = 0f

    @Volatile var highPassFilter: Boolean = false

    @Volatile var workGainLinear: Float = 1f

    val hasActiveEffects: Boolean
        get() =
            swapChannels ||
                monoAudio ||
                nightDynamics ||
                highPassFilter ||
                abs(stereoBalance) > 0.005f ||
                abs(workGainLinear - 1f) > 0.005f

    // [0]=envelope, [1]=hpPrevInL, [2]=hpPrevOutL, [3]=hpPrevInR, [4]=hpPrevOutR
    private val dspState = FloatArray(5)
    private var attackCoeff: Float = 0.05f
    private var releaseCoeff: Float = 0.002f
    private var hpAlpha: Float = 0.988f
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
        // 1st-order 80 Hz rumble/DC high-pass filter coefficient: RC / (RC + dt).
        val rc = 1.0 / (2.0 * PI * 80.0)
        val dt = 1.0 / sr
        hpAlpha = (rc / (rc + dt)).toFloat()
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        dspState.fill(0f)
    }

    override fun onReset() {
        dspState.fill(0f)
        pcm16Scratch = ShortArray(0)
        pcmFloatScratch = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return
        val swap = swapChannels
        val mono = monoAudio
        val nightStrength = if (nightDynamics) nightDynamicsStrength.coerceIn(1, 3) else 0
        val balance = stereoBalance.coerceIn(-1f, 1f)
        val gain = workGainLinear.coerceIn(0.25f, 4f)
        val hp = if (highPassFilter) hpAlpha else 0f
        val out = replaceOutputBuffer(remaining)
        val active =
            swap ||
                mono ||
                nightStrength > 0 ||
                abs(balance) > 0.005f ||
                abs(gain - 1f) > 0.005f ||
                hp > 0f
        if (!active) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT ->
                processPcm16(
                    inputBuffer,
                    out,
                    inputAudioFormat.channelCount,
                    swap,
                    mono,
                    nightStrength,
                    balance,
                    gain,
                    hp,
                )
            C.ENCODING_PCM_FLOAT ->
                processPcmFloat(
                    inputBuffer,
                    out,
                    inputAudioFormat.channelCount,
                    swap,
                    mono,
                    nightStrength,
                    balance,
                    gain,
                    hp,
                )
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
        nightStrength: Int,
        balance: Float,
        gainLinear: Float,
        hpCoeff: Float,
    ) {
        if (channels == 2) {
            val frames = input.remaining() / 4
            if (frames > 0) {
                if (DspNative.available && input.isDirect && output.isDirect) {
                    DspNative.processPcm16Stereo(
                        input = input,
                        inPos = input.position(),
                        output = output,
                        outPos = output.position(),
                        frames = frames,
                        swap = swap,
                        mono = mono,
                        nightStrength = nightStrength,
                        balance = balance,
                        gainLinear = gainLinear,
                        highPassCoeff = hpCoeff,
                        attackCoeff = attackCoeff,
                        releaseCoeff = releaseCoeff,
                        state = dspState,
                    )
                    val byteLen = frames * 4
                    input.position(input.position() + byteLen)
                    output.position(output.position() + byteLen)
                } else {
                    val sampleCount = frames * 2
                    if (pcm16Scratch.size < sampleCount) pcm16Scratch = ShortArray(sampleCount)
                    val samples = pcm16Scratch
                    input.asShortBuffer().get(samples, 0, sampleCount)
                    input.position(input.position() + sampleCount * 2)
                    val hasBalance = abs(balance) > 0.005f
                    val hasGain = abs(gainLinear - 1f) > 0.005f
                    val hasHp = hpCoeff > 0f
                    if (nightStrength == 0 && !hasBalance && !hasGain && !hasHp) {
                        var i = 0
                        if (mono) {
                            while (i < sampleCount) {
                                val m =
                                    ((samples[i].toInt() + samples[i + 1].toInt()) / 2).toShort()
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
                        val leftMul = (1f - balance).coerceIn(0f, 1f)
                        val rightMul = (1f + balance).coerceIn(0f, 1f)
                        var prevInL = dspState[1]
                        var prevOutL = dspState[2]
                        var prevInR = dspState[3]
                        var prevOutR = dspState[4]
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
                            if (hasHp) {
                                val yl = hpCoeff * (prevOutL + l - prevInL)
                                val yr = hpCoeff * (prevOutR + r - prevInR)
                                prevInL = l
                                prevOutL = yl
                                prevInR = r
                                prevOutR = yr
                                l = yl
                                r = yr
                            }
                            if (hasGain) {
                                l *= gainLinear
                                r *= gainLinear
                            }
                            if (nightStrength > 0) {
                                val g = computeNightGain(max(abs(l), abs(r)), nightStrength)
                                l *= g
                                r *= g
                            }
                            if (hasBalance) {
                                l *= leftMul
                                r *= rightMul
                            }
                            samples[i] = floatToPcm16(softClip(l))
                            samples[i + 1] = floatToPcm16(softClip(r))
                            i += 2
                        }
                        dspState[1] = prevInL
                        dspState[2] = prevOutL
                        dspState[3] = prevInR
                        dspState[4] = prevOutR
                    }
                    output.asShortBuffer().put(samples, 0, sampleCount)
                    output.position(output.position() + sampleCount * 2)
                }
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
                val sample = input.getShort(startPos + c * 2) * INV_PCM16 * gainLinear
                sum += sample
                peak = max(peak, abs(sample))
            }
            val gain = if (nightStrength > 0) computeNightGain(peak, nightStrength) else 1f
            val monoVal = if (mono && channels > 1) sum / channels else 0f
            for (c in 0 until channels) {
                val raw =
                    if (mono && channels > 1) monoVal
                    else input.getShort(startPos + c * 2) * INV_PCM16 * gainLinear
                val processed = softClip(raw * gain)
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
        nightStrength: Int,
        balance: Float,
        gainLinear: Float,
        hpCoeff: Float,
    ) {
        if (channels == 2) {
            val frames = input.remaining() / 8
            if (frames > 0) {
                if (DspNative.available && input.isDirect && output.isDirect) {
                    DspNative.processPcmFloatStereo(
                        input = input,
                        inPos = input.position(),
                        output = output,
                        outPos = output.position(),
                        frames = frames,
                        swap = swap,
                        mono = mono,
                        nightStrength = nightStrength,
                        balance = balance,
                        gainLinear = gainLinear,
                        highPassCoeff = hpCoeff,
                        attackCoeff = attackCoeff,
                        releaseCoeff = releaseCoeff,
                        state = dspState,
                    )
                    val byteLen = frames * 8
                    input.position(input.position() + byteLen)
                    output.position(output.position() + byteLen)
                } else {
                    val sampleCount = frames * 2
                    if (pcmFloatScratch.size < sampleCount) {
                        pcmFloatScratch = FloatArray(sampleCount)
                    }
                    val samples = pcmFloatScratch
                    input.asFloatBuffer().get(samples, 0, sampleCount)
                    input.position(input.position() + sampleCount * 4)
                    val hasBalance = abs(balance) > 0.005f
                    val hasGain = abs(gainLinear - 1f) > 0.005f
                    val hasHp = hpCoeff > 0f
                    val leftMul = (1f - balance).coerceIn(0f, 1f)
                    val rightMul = (1f + balance).coerceIn(0f, 1f)
                    var prevInL = dspState[1]
                    var prevOutL = dspState[2]
                    var prevInR = dspState[3]
                    var prevOutR = dspState[4]
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
                        if (hasHp) {
                            val yl = hpCoeff * (prevOutL + l - prevInL)
                            val yr = hpCoeff * (prevOutR + r - prevInR)
                            prevInL = l
                            prevOutL = yl
                            prevInR = r
                            prevOutR = yr
                            l = yl
                            r = yr
                        }
                        if (hasGain) {
                            l *= gainLinear
                            r *= gainLinear
                        }
                        if (nightStrength > 0) {
                            val g = computeNightGain(max(abs(l), abs(r)), nightStrength)
                            l *= g
                            r *= g
                        }
                        if (hasBalance) {
                            l *= leftMul
                            r *= rightMul
                        }
                        if (nightStrength > 0 || hasGain || hasHp || hasBalance) {
                            l = softClip(l)
                            r = softClip(r)
                        }
                        samples[i] = l
                        samples[i + 1] = r
                        i += 2
                    }
                    dspState[1] = prevInL
                    dspState[2] = prevOutL
                    dspState[3] = prevInR
                    dspState[4] = prevOutR
                    output.asFloatBuffer().put(samples, 0, sampleCount)
                    output.position(output.position() + sampleCount * 4)
                }
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
                val sample = input.getFloat(startPos + c * 4) * gainLinear
                sum += sample
                peak = max(peak, abs(sample))
            }
            val gain = if (nightStrength > 0) computeNightGain(peak, nightStrength) else 1f
            val monoVal = if (mono && channels > 1) sum / channels else 0f
            for (c in 0 until channels) {
                val raw =
                    if (mono && channels > 1) monoVal
                    else input.getFloat(startPos + c * 4) * gainLinear
                val processed = softClip(raw * gain)
                output.putFloat(processed)
            }
            input.position(startPos + frameBytes)
        }
        while (input.hasRemaining()) output.put(input.get())
    }

    internal fun computeNightGain(peak: Float, strength: Int = 2): Float {
        var env = dspState[0]
        val coeff = if (peak > env) attackCoeff else releaseCoeff
        env += coeff * (peak - env)
        dspState[0] = env
        val makeup: Float
        val threshold: Float
        val ceiling: Float
        when (strength) {
            1 -> {
                makeup = 1.22f
                threshold = 0.22f
                ceiling = 0.85f
            }
            3 -> {
                makeup = 1.65f
                threshold = 0.11f
                ceiling = 0.62f
            }
            else -> {
                makeup = 1.42f
                threshold = 0.16f
                ceiling = 0.75f
            }
        }
        if (env <= threshold) return makeup
        val compressed = threshold * sqrt(sqrt(env / threshold))
        val target = (compressed / env) * makeup
        val ceilingGain = if (env > 0.0001f) ceiling / env else makeup
        return minOf(target, ceilingGain).coerceIn(0.08f, makeup)
    }

    private fun softClip(sample: Float): Float {
        val a = abs(sample)
        if (a <= 0.85f) return sample
        val excess = a - 0.85f
        val shaped = 0.85f + 0.14f * (excess / (excess + 0.14f))
        return if (sample >= 0f) shaped else -shaped
    }

    private fun floatToPcm16(sample: Float): Short =
        (sample.coerceIn(-1f, 1f) * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()

    companion object {
        private const val INV_PCM16 = 1f / 32768f

        fun dbToLinear(db: Float): Float = 10.0.pow(db.coerceIn(-12f, 12f) / 20.0).toFloat()
    }
}
