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

struct NightParams {
    float makeup;
    float threshold;
    float inv_threshold;
    float ceiling;
};

inline NightParams night_params_for(int32_t strength) {
    switch (strength) {
    case 1:
        return {1.22f, 0.22f, 1.0f / 0.22f, 0.85f};
    case 3:
        return {1.65f, 0.11f, 1.0f / 0.11f, 0.62f};
    default:
        return {1.42f, 0.16f, 1.0f / 0.16f, 0.75f};
    }
}

inline float compute_night_gain(float peak, float attack_coeff, float release_coeff,
                                const NightParams &params, float *envelope) {
    float env = *envelope;
    const float coeff = (peak > env) ? attack_coeff : release_coeff;
    env += coeff * (peak - env);
    *envelope = env;
    if (env <= params.threshold)
        return params.makeup;
    // 4:1 ratio: (env / threshold)^(1/4) == sqrt(sqrt(env / threshold)).
    const float compressed = params.threshold * sqrtf(sqrtf(env * params.inv_threshold));
    const float target = (compressed / env) * params.makeup;
    const float ceiling_gain = (env > 0.0001f) ? (params.ceiling / env) : params.makeup;
    float gain = (target < ceiling_gain) ? target : ceiling_gain;
    if (gain < 0.08f)
        gain = 0.08f;
    else if (gain > params.makeup)
        gain = params.makeup;
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

inline void balance_multipliers(float balance, float *left_mul, float *right_mul) {
    float l = 1.0f - balance;
    float r = 1.0f + balance;
    if (l < 0.0f)
        l = 0.0f;
    else if (l > 1.0f)
        l = 1.0f;
    if (r < 0.0f)
        r = 0.0f;
    else if (r > 1.0f)
        r = 1.0f;
    *left_mul = l;
    *right_mul = r;
}

// state layout: [0]=envelope, [1]=hp_prev_in_l, [2]=hp_prev_out_l, [3]=hp_prev_in_r,
// [4]=hp_prev_out_r
void process_pcm16_stereo_impl(const int16_t *in, int16_t *out, int32_t frames, bool swap,
                               bool mono, int32_t night_strength, float balance, float gain_linear,
                               float hp_coeff, float attack_coeff, float release_coeff,
                               float *state) {
    const bool has_balance = fabsf(balance) > 0.005f;
    const bool has_gain = fabsf(gain_linear - 1.0f) > 0.005f;
    const bool has_hp = hp_coeff > 0.0f;
    const bool has_night = night_strength > 0;
    int32_t i = 0;

    if (!has_night && !has_balance && !has_gain && !has_hp) {
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

    float left_mul = 1.0f;
    float right_mul = 1.0f;
    if (has_balance)
        balance_multipliers(balance, &left_mul, &right_mul);
    const NightParams params = night_params_for(night_strength);
    float env = state[0];
    float prev_in_l = state[1];
    float prev_out_l = state[2];
    float prev_in_r = state[3];
    float prev_out_r = state[4];

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
        if (has_hp) {
            const float yl = hp_coeff * (prev_out_l + l - prev_in_l);
            const float yr = hp_coeff * (prev_out_r + r - prev_in_r);
            prev_in_l = l;
            prev_out_l = yl;
            prev_in_r = r;
            prev_out_r = yr;
            l = yl;
            r = yr;
        }
        if (has_gain) {
            l *= gain_linear;
            r *= gain_linear;
        }
        if (has_night) {
            const float al = fabsf(l);
            const float ar = fabsf(r);
            const float peak = (al > ar) ? al : ar;
            const float gain = compute_night_gain(peak, attack_coeff, release_coeff, params, &env);
            l *= gain;
            r *= gain;
        }
        if (has_balance) {
            l *= left_mul;
            r *= right_mul;
        }
        out[i * 2] = float_to_pcm16(soft_clip(l));
        out[i * 2 + 1] = float_to_pcm16(soft_clip(r));
    }
    state[0] = env;
    state[1] = prev_in_l;
    state[2] = prev_out_l;
    state[3] = prev_in_r;
    state[4] = prev_out_r;
}

void process_pcm_float_stereo_impl(const float *in, float *out, int32_t frames, bool swap,
                                   bool mono, int32_t night_strength, float balance,
                                   float gain_linear, float hp_coeff, float attack_coeff,
                                   float release_coeff, float *state) {
    const bool has_balance = fabsf(balance) > 0.005f;
    const bool has_gain = fabsf(gain_linear - 1.0f) > 0.005f;
    const bool has_hp = hp_coeff > 0.0f;
    const bool has_night = night_strength > 0;
    int32_t i = 0;

    if (!has_night && !has_balance && !has_gain && !has_hp) {
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

    float left_mul = 1.0f;
    float right_mul = 1.0f;
    if (has_balance)
        balance_multipliers(balance, &left_mul, &right_mul);
    const NightParams params = night_params_for(night_strength);
    float env = state[0];
    float prev_in_l = state[1];
    float prev_out_l = state[2];
    float prev_in_r = state[3];
    float prev_out_r = state[4];

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
        if (has_hp) {
            const float yl = hp_coeff * (prev_out_l + l - prev_in_l);
            const float yr = hp_coeff * (prev_out_r + r - prev_in_r);
            prev_in_l = l;
            prev_out_l = yl;
            prev_in_r = r;
            prev_out_r = yr;
            l = yl;
            r = yr;
        }
        if (has_gain) {
            l *= gain_linear;
            r *= gain_linear;
        }
        if (has_night) {
            const float al = fabsf(l);
            const float ar = fabsf(r);
            const float peak = (al > ar) ? al : ar;
            const float gain = compute_night_gain(peak, attack_coeff, release_coeff, params, &env);
            l *= gain;
            r *= gain;
        }
        if (has_balance) {
            l *= left_mul;
            r *= right_mul;
        }
        out[i * 2] = soft_clip(l);
        out[i * 2 + 1] = soft_clip(r);
    }
    state[0] = env;
    state[1] = prev_in_l;
    state[2] = prev_out_l;
    state[3] = prev_in_r;
    state[4] = prev_out_r;
}
} // namespace

extern "C" JNIEXPORT void JNICALL Java_app_whisp_playback_DspNative_processPcm16Stereo(
    JNIEnv *env, jobject, jobject input_buf, jint in_pos, jobject output_buf, jint out_pos,
    jint frames, jboolean swap, jboolean mono, jint night_strength, jfloat balance,
    jfloat gain_linear, jfloat hp_coeff, jfloat attack_coeff, jfloat release_coeff,
    jfloatArray state_arr) {
    if (!input_buf || !output_buf || !state_arr || frames <= 0 || in_pos < 0 || out_pos < 0)
        return;
    if (env->GetArrayLength(state_arr) < 5)
        return;
    const jlong byte_len = static_cast<jlong>(frames) * 4;
    void *in_addr = env->GetDirectBufferAddress(input_buf);
    void *out_addr = env->GetDirectBufferAddress(output_buf);
    const jlong in_cap = env->GetDirectBufferCapacity(input_buf);
    const jlong out_cap = env->GetDirectBufferCapacity(output_buf);
    if (!in_addr || !out_addr || in_cap < static_cast<jlong>(in_pos) + byte_len ||
        out_cap < static_cast<jlong>(out_pos) + byte_len) {
        return;
    }
    float state[5] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    env->GetFloatArrayRegion(state_arr, 0, 5, state);
    const auto *in_ptr =
        reinterpret_cast<const int16_t *>(static_cast<const uint8_t *>(in_addr) + in_pos);
    auto *out_ptr = reinterpret_cast<int16_t *>(static_cast<uint8_t *>(out_addr) + out_pos);
    process_pcm16_stereo_impl(in_ptr, out_ptr, frames, swap == JNI_TRUE, mono == JNI_TRUE,
                              night_strength, balance, gain_linear, hp_coeff, attack_coeff,
                              release_coeff, state);
    env->SetFloatArrayRegion(state_arr, 0, 5, state);
}

extern "C" JNIEXPORT void JNICALL Java_app_whisp_playback_DspNative_processPcmFloatStereo(
    JNIEnv *env, jobject, jobject input_buf, jint in_pos, jobject output_buf, jint out_pos,
    jint frames, jboolean swap, jboolean mono, jint night_strength, jfloat balance,
    jfloat gain_linear, jfloat hp_coeff, jfloat attack_coeff, jfloat release_coeff,
    jfloatArray state_arr) {
    if (!input_buf || !output_buf || !state_arr || frames <= 0 || in_pos < 0 || out_pos < 0)
        return;
    if (env->GetArrayLength(state_arr) < 5)
        return;
    const jlong byte_len = static_cast<jlong>(frames) * 8;
    void *in_addr = env->GetDirectBufferAddress(input_buf);
    void *out_addr = env->GetDirectBufferAddress(output_buf);
    const jlong in_cap = env->GetDirectBufferCapacity(input_buf);
    const jlong out_cap = env->GetDirectBufferCapacity(output_buf);
    if (!in_addr || !out_addr || in_cap < static_cast<jlong>(in_pos) + byte_len ||
        out_cap < static_cast<jlong>(out_pos) + byte_len) {
        return;
    }
    float state[5] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    env->GetFloatArrayRegion(state_arr, 0, 5, state);
    const auto *in_ptr =
        reinterpret_cast<const float *>(static_cast<const uint8_t *>(in_addr) + in_pos);
    auto *out_ptr = reinterpret_cast<float *>(static_cast<uint8_t *>(out_addr) + out_pos);
    process_pcm_float_stereo_impl(in_ptr, out_ptr, frames, swap == JNI_TRUE, mono == JNI_TRUE,
                                  night_strength, balance, gain_linear, hp_coeff, attack_coeff,
                                  release_coeff, state);
    env->SetFloatArrayRegion(state_arr, 0, 5, state);
}
