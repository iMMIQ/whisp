// SPDX-License-Identifier: LGPL-3.0-or-later
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <jni.h>
#include <vector>

#include "dsp_bridge.cpp"

namespace {
struct FakeDirectBuf {
    std::vector<uint8_t> data;
};

struct FakeFloatArray {
    std::vector<float> values;
};

void *jni_direct_addr(JNIEnv *, jobject buf) {
    auto *b = reinterpret_cast<FakeDirectBuf *>(buf);
    return b ? b->data.data() : nullptr;
}

jlong jni_direct_cap(JNIEnv *, jobject buf) {
    auto *b = reinterpret_cast<FakeDirectBuf *>(buf);
    return b ? static_cast<jlong>(b->data.size()) : -1;
}

jsize jni_array_len(JNIEnv *, jarray arr) {
    auto *a = reinterpret_cast<FakeFloatArray *>(arr);
    return a ? static_cast<jsize>(a->values.size()) : 0;
}

void jni_get_float_region(JNIEnv *, jfloatArray arr, jsize start, jsize len, jfloat *buf) {
    auto *a = reinterpret_cast<FakeFloatArray *>(arr);
    if (!a || start < 0 || len < 0 || static_cast<size_t>(start + len) > a->values.size())
        return;
    std::memcpy(buf, a->values.data() + start, static_cast<size_t>(len) * sizeof(float));
}

void jni_set_float_region(JNIEnv *, jfloatArray arr, jsize start, jsize len, const jfloat *buf) {
    auto *a = reinterpret_cast<FakeFloatArray *>(arr);
    if (!a || start < 0 || len < 0 || static_cast<size_t>(start + len) > a->values.size())
        return;
    std::memcpy(a->values.data() + start, buf, static_cast<size_t>(len) * sizeof(float));
}
} // namespace

int main() {
    JNINativeInterface_ table{};
    table.GetDirectBufferAddress = jni_direct_addr;
    table.GetDirectBufferCapacity = jni_direct_cap;
    table.GetArrayLength = jni_array_len;
    table.GetFloatArrayRegion = jni_get_float_region;
    table.SetFloatArrayRegion = jni_set_float_region;
    JNIEnv env{&table};

    FakeFloatArray state{std::vector<float>(5, 0.0f)};
    auto *state_ref = reinterpret_cast<jfloatArray>(&state);

    // 1. PCM16 stereo channel swap across 12 frames (covers NEON 8-frame + 4-frame tail).
    FakeDirectBuf in16{std::vector<uint8_t>(12 * 4)};
    FakeDirectBuf out16{std::vector<uint8_t>(12 * 4)};
    auto *in_s = reinterpret_cast<int16_t *>(in16.data.data());
    auto *out_s = reinterpret_cast<int16_t *>(out16.data.data());
    for (int i = 0; i < 12; ++i) {
        in_s[i * 2] = static_cast<int16_t>(1000 + i * 10);
        in_s[i * 2 + 1] = static_cast<int16_t>(-2000 - i * 10);
    }
    Java_app_whisp_playback_DspNative_processPcm16Stereo(
        &env, nullptr, reinterpret_cast<jobject>(&in16), 0, reinterpret_cast<jobject>(&out16), 0,
        12, JNI_TRUE, JNI_FALSE, 0, 0.0f, 1.0f, 0.0f, 0.05f, 0.002f, state_ref);
    for (int i = 0; i < 12; ++i) {
        assert(out_s[i * 2] == static_cast<int16_t>(-2000 - i * 10));
        assert(out_s[i * 2 + 1] == static_cast<int16_t>(1000 + i * 10));
    }

    // 2. PCM16 mono downmix.
    Java_app_whisp_playback_DspNative_processPcm16Stereo(
        &env, nullptr, reinterpret_cast<jobject>(&in16), 0, reinterpret_cast<jobject>(&out16), 0,
        12, JNI_FALSE, JNI_TRUE, 0, 0.0f, 1.0f, 0.0f, 0.05f, 0.002f, state_ref);
    for (int i = 0; i < 12; ++i) {
        assert(out_s[i * 2] == out_s[i * 2 + 1]);
        assert(std::abs(out_s[i * 2] - (-500)) <= 2);
    }

    // 3. PCM16 night dynamics lifts whispers and limits loud bursts (strength 2 & 3).
    FakeDirectBuf loud_in{std::vector<uint8_t>(200 * 4)};
    FakeDirectBuf loud_out{std::vector<uint8_t>(200 * 4)};
    auto *li = reinterpret_cast<int16_t *>(loud_in.data.data());
    auto *lo = reinterpret_cast<int16_t *>(loud_out.data.data());
    for (int i = 0; i < 200; ++i) {
        li[i * 2] = 32000;
        li[i * 2 + 1] = 32000;
    }
    state.values.assign(5, 0.0f);
    Java_app_whisp_playback_DspNative_processPcm16Stereo(
        &env, nullptr, reinterpret_cast<jobject>(&loud_in), 0, reinterpret_cast<jobject>(&loud_out),
        0, 200, JNI_FALSE, JNI_FALSE, 2, 0.0f, 1.0f, 0.0f, 0.0056f, 0.00012f, state_ref);
    assert(state.values[0] > 0.5f);
    const int std_peak = std::abs(lo[199 * 2]);
    assert(std_peak < 29000);

    state.values.assign(5, 0.0f);
    Java_app_whisp_playback_DspNative_processPcm16Stereo(
        &env, nullptr, reinterpret_cast<jobject>(&loud_in), 0, reinterpret_cast<jobject>(&loud_out),
        0, 200, JNI_FALSE, JNI_FALSE, 3, 0.0f, 1.0f, 0.0f, 0.0056f, 0.00012f, state_ref);
    const int strong_peak = std::abs(lo[199 * 2]);
    assert(strong_peak < std_peak);

    // 4. Stereo balance attenuates opposite channel cleanly, and per-work gain boosts quietly.
    FakeDirectBuf bal_in{std::vector<uint8_t>(8 * 4)};
    FakeDirectBuf bal_out{std::vector<uint8_t>(8 * 4)};
    auto *bi = reinterpret_cast<int16_t *>(bal_in.data.data());
    auto *bo = reinterpret_cast<int16_t *>(bal_out.data.data());
    for (int i = 0; i < 8; ++i) {
        bi[i * 2] = 4000;
        bi[i * 2 + 1] = 4000;
    }
    state.values.assign(5, 0.0f);
    Java_app_whisp_playback_DspNative_processPcm16Stereo(
        &env, nullptr, reinterpret_cast<jobject>(&bal_in), 0, reinterpret_cast<jobject>(&bal_out),
        0, 8, JNI_FALSE, JNI_FALSE, 0, -0.5f, 2.0f, 0.0f, 0.05f, 0.002f, state_ref);
    // gain=2.0 -> 8000, balance=-0.5 -> left=1.0 (8000), right=0.5 (4000)
    assert(std::abs(bo[0] - 8000) <= 4);
    assert(std::abs(bo[1] - 4000) <= 4);

    // 5. High-pass filter attenuates DC / rumble while passing high-frequency signal.
    FakeDirectBuf dc_in{std::vector<uint8_t>(500 * 4)};
    FakeDirectBuf dc_out{std::vector<uint8_t>(500 * 4)};
    auto *di = reinterpret_cast<int16_t *>(dc_in.data.data());
    auto *do_ = reinterpret_cast<int16_t *>(dc_out.data.data());
    for (int i = 0; i < 500; ++i) {
        di[i * 2] = 10000;
        di[i * 2 + 1] = 10000;
    }
    state.values.assign(5, 0.0f);
    Java_app_whisp_playback_DspNative_processPcm16Stereo(
        &env, nullptr, reinterpret_cast<jobject>(&dc_in), 0, reinterpret_cast<jobject>(&dc_out), 0,
        500, JNI_FALSE, JNI_FALSE, 0, 0.0f, 1.0f, 0.985f, 0.05f, 0.002f, state_ref);
    assert(std::abs(do_[499 * 2]) < 500);

    // 6. Float stereo mono & swap.
    FakeDirectBuf inf{std::vector<uint8_t>(8 * 8)};
    FakeDirectBuf outf{std::vector<uint8_t>(8 * 8)};
    auto *in_f = reinterpret_cast<float *>(inf.data.data());
    auto *out_f = reinterpret_cast<float *>(outf.data.data());
    for (int i = 0; i < 8; ++i) {
        in_f[i * 2] = 0.6f;
        in_f[i * 2 + 1] = -0.2f;
    }
    state.values.assign(5, 0.0f);
    Java_app_whisp_playback_DspNative_processPcmFloatStereo(
        &env, nullptr, reinterpret_cast<jobject>(&inf), 0, reinterpret_cast<jobject>(&outf), 0, 8,
        JNI_FALSE, JNI_TRUE, 0, 0.0f, 1.0f, 0.0f, 0.05f, 0.002f, state_ref);
    for (int i = 0; i < 8; ++i) {
        assert(std::fabs(out_f[i * 2] - 0.2f) < 0.001f);
        assert(std::fabs(out_f[i * 2 + 1] - 0.2f) < 0.001f);
    }

    std::puts("dsp_bridge_test passed");
    return 0;
}
