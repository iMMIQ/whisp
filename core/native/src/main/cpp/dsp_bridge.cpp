// SPDX-License-Identifier: LGPL-3.0-or-later
#include <jni.h>
#include <math.h>
#include <stdint.h>

#if defined(__aarch64__) || defined(__ARM_NEON)
#include <arm_neon.h>
#define WHISP_HAS_NEON 1
#else
#define WHISP_HAS_NEON 0
#endif

namespace {
constexpr float kInvPcm16 = 1.0f / 32768.0f;
constexpr float kMakeup = 1.42f;
constexpr float kThreshold = 0.16f;
constexpr float kInvThreshold = 1.0f / kThreshold;

inline float compute_night_gain(float peak, float attack_coeff, float release_coeff,
                                float *envelope) {
    float env = *envelope;
    const float coeff = (peak > env) ? attack_coeff : release_coeff;
    env += coeff * (peak - env);
    *envelope = env;
    if (env <= kThreshold)
        return kMakeup;
    // 4:1 ratio: (env / threshold)^(1/4) == sqrt(sqrt(env / threshold)).
    const float compressed = kThreshold * sqrtf(sqrtf(env * kInvThreshold));
    const float target = (compressed / env) * kMakeup;
    const float ceiling_gain = (env > 0.0001f) ? (0.75f / env) : kMakeup;
    float gain = (target < ceiling_gain) ? target : ceiling_gain;
    if (gain < 0.08f)
        gain = 0.08f;
    else if (gain > kMakeup)
        gain = kMakeup;
    return gain;
}

inline float soft_clip(float sample) {
    const float a = fabsf(sample);
    if (a <= 0.85f)
        return sample;
    const float excess = a - 0.85f;
    const float shaped = 0.85f + 0.14f * (excess / (excess + 0.14f));
    return (sample >= 0.0f) ? shaped : -shaped;
}

inline int16_t float_to_pcm16(float sample) {
    if (sample > 1.0f)
        sample = 1.0f;
    else if (sample < -1.0f)
        sample = -1.0f;
    long v = lroundf(sample * 32768.0f);
    if (v > 32767)
        v = 32767;
    else if (v < -32768)
        v = -32768;
    return static_cast<int16_t>(v);
}

void process_pcm16_stereo_impl(const int16_t *in, int16_t *out, int32_t frames, bool swap,
                               bool mono, bool night, float attack_coeff, float release_coeff,
                               float *envelope) {
    int32_t i = 0;
    if (!night) {
        if (mono) {
#if WHISP_HAS_NEON
            for (; i + 8 <= frames; i += 8) {
                int16x8x2_t lr = vld2q_s16(in + i * 2);
                int16x8_t m = vhaddq_s16(lr.val[0], lr.val[1]);
                int16x8x2_t out_lr = {{m, m}};
                vst2q_s16(out + i * 2, out_lr);
            }
#endif
            for (; i < frames; ++i) {
                const int16_t m = static_cast<int16_t>(
                    (static_cast<int32_t>(in[i * 2]) + static_cast<int32_t>(in[i * 2 + 1])) / 2);
                out[i * 2] = m;
                out[i * 2 + 1] = m;
            }
            return;
        }
        if (swap) {
#if WHISP_HAS_NEON
            for (; i + 8 <= frames; i += 8) {
                int16x8x2_t lr = vld2q_s16(in + i * 2);
                int16x8x2_t rl = {{lr.val[1], lr.val[0]}};
                vst2q_s16(out + i * 2, rl);
            }
#endif
            for (; i < frames; ++i) {
                out[i * 2] = in[i * 2 + 1];
                out[i * 2 + 1] = in[i * 2];
            }
            return;
        }
        for (; i < frames; ++i) {
            out[i * 2] = in[i * 2];
            out[i * 2 + 1] = in[i * 2 + 1];
        }
        return;
    }

    for (; i < frames; ++i) {
        float l = static_cast<float>(in[i * 2]) * kInvPcm16;
        float r = static_cast<float>(in[i * 2 + 1]) * kInvPcm16;
        if (mono) {
            const float m = (l + r) * 0.5f;
            l = m;
            r = m;
        } else if (swap) {
            const float tmp = l;
            l = r;
            r = tmp;
        }
        const float al = fabsf(l);
        const float ar = fabsf(r);
        const float peak = (al > ar) ? al : ar;
        const float gain = compute_night_gain(peak, attack_coeff, release_coeff, envelope);
        out[i * 2] = float_to_pcm16(soft_clip(l * gain));
        out[i * 2 + 1] = float_to_pcm16(soft_clip(r * gain));
    }
}

void process_pcm_float_stereo_impl(const float *in, float *out, int32_t frames, bool swap,
                                   bool mono, bool night, float attack_coeff, float release_coeff,
                                   float *envelope) {
    int32_t i = 0;
    if (!night) {
        if (mono) {
#if WHISP_HAS_NEON
            for (; i + 4 <= frames; i += 4) {
                float32x4x2_t lr = vld2q_f32(in + i * 2);
                float32x4_t m = vmulq_n_f32(vaddq_f32(lr.val[0], lr.val[1]), 0.5f);
                float32x4x2_t out_lr = {{m, m}};
                vst2q_f32(out + i * 2, out_lr);
            }
#endif
            for (; i < frames; ++i) {
                const float m = (in[i * 2] + in[i * 2 + 1]) * 0.5f;
                out[i * 2] = m;
                out[i * 2 + 1] = m;
            }
            return;
        }
        if (swap) {
#if WHISP_HAS_NEON
            for (; i + 4 <= frames; i += 4) {
                float32x4x2_t lr = vld2q_f32(in + i * 2);
                float32x4x2_t rl = {{lr.val[1], lr.val[0]}};
                vst2q_f32(out + i * 2, rl);
            }
#endif
            for (; i < frames; ++i) {
                out[i * 2] = in[i * 2 + 1];
                out[i * 2 + 1] = in[i * 2];
            }
            return;
        }
        for (; i < frames; ++i) {
            out[i * 2] = in[i * 2];
            out[i * 2 + 1] = in[i * 2 + 1];
        }
        return;
    }

    for (; i < frames; ++i) {
        float l = in[i * 2];
        float r = in[i * 2 + 1];
        if (mono) {
            const float m = (l + r) * 0.5f;
            l = m;
            r = m;
        } else if (swap) {
            const float tmp = l;
            l = r;
            r = tmp;
        }
        const float al = fabsf(l);
        const float ar = fabsf(r);
        const float peak = (al > ar) ? al : ar;
        const float gain = compute_night_gain(peak, attack_coeff, release_coeff, envelope);
        out[i * 2] = soft_clip(l * gain);
        out[i * 2 + 1] = soft_clip(r * gain);
    }
}
} // namespace

extern "C" JNIEXPORT jfloat JNICALL Java_app_whisp_playback_DspNative_processPcm16Stereo(
    JNIEnv *env, jobject, jobject input_buf, jint in_pos, jobject output_buf, jint out_pos,
    jint frames, jboolean swap, jboolean mono, jboolean night, jfloat attack_coeff,
    jfloat release_coeff, jfloat initial_envelope) {
    if (!input_buf || !output_buf || frames <= 0 || in_pos < 0 || out_pos < 0)
        return initial_envelope;
    const jlong byte_len = static_cast<jlong>(frames) * 4;
    void *in_addr = env->GetDirectBufferAddress(input_buf);
    void *out_addr = env->GetDirectBufferAddress(output_buf);
    const jlong in_cap = env->GetDirectBufferCapacity(input_buf);
    const jlong out_cap = env->GetDirectBufferCapacity(output_buf);
    if (!in_addr || !out_addr || in_cap < static_cast<jlong>(in_pos) + byte_len ||
        out_cap < static_cast<jlong>(out_pos) + byte_len) {
        return initial_envelope;
    }
    float envelope = initial_envelope;
    const auto *in_ptr =
        reinterpret_cast<const int16_t *>(static_cast<const uint8_t *>(in_addr) + in_pos);
    auto *out_ptr = reinterpret_cast<int16_t *>(static_cast<uint8_t *>(out_addr) + out_pos);
    process_pcm16_stereo_impl(in_ptr, out_ptr, frames, swap == JNI_TRUE, mono == JNI_TRUE,
                              night == JNI_TRUE, attack_coeff, release_coeff, &envelope);
    return envelope;
}

extern "C" JNIEXPORT jfloat JNICALL Java_app_whisp_playback_DspNative_processPcmFloatStereo(
    JNIEnv *env, jobject, jobject input_buf, jint in_pos, jobject output_buf, jint out_pos,
    jint frames, jboolean swap, jboolean mono, jboolean night, jfloat attack_coeff,
    jfloat release_coeff, jfloat initial_envelope) {
    if (!input_buf || !output_buf || frames <= 0 || in_pos < 0 || out_pos < 0)
        return initial_envelope;
    const jlong byte_len = static_cast<jlong>(frames) * 8;
    void *in_addr = env->GetDirectBufferAddress(input_buf);
    void *out_addr = env->GetDirectBufferAddress(output_buf);
    const jlong in_cap = env->GetDirectBufferCapacity(input_buf);
    const jlong out_cap = env->GetDirectBufferCapacity(output_buf);
    if (!in_addr || !out_addr || in_cap < static_cast<jlong>(in_pos) + byte_len ||
        out_cap < static_cast<jlong>(out_pos) + byte_len) {
        return initial_envelope;
    }
    float envelope = initial_envelope;
    const auto *in_ptr =
        reinterpret_cast<const float *>(static_cast<const uint8_t *>(in_addr) + in_pos);
    auto *out_ptr = reinterpret_cast<float *>(static_cast<uint8_t *>(out_addr) + out_pos);
    process_pcm_float_stereo_impl(in_ptr, out_ptr, frames, swap == JNI_TRUE, mono == JNI_TRUE,
                                  night == JNI_TRUE, attack_coeff, release_coeff, &envelope);
    return envelope;
}
