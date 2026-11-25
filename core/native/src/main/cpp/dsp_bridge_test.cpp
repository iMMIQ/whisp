// SPDX-License-Identifier: LGPL-3.0-or-later
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <jni.h>
#include <vector>

#include "dsp_bridge.cpp"

namespace {
struct FakeDirectBuf {
    std::vector<uint8_t> data;
};

void *jni_direct_addr(JNIEnv *, jobject buf) {
    auto *b = reinterpret_cast<FakeDirectBuf *>(buf);
    return b ? b->data.data() : nullptr;
}

jlong jni_direct_cap(JNIEnv *, jobject buf) {
    auto *b = reinterpret_cast<FakeDirectBuf *>(buf);
    return b ? static_cast<jlong>(b->data.size()) : -1;
}
} // namespace

int main() {
    JNINativeInterface_ table{};
    table.GetDirectBufferAddress = jni_direct_addr;
    table.GetDirectBufferCapacity = jni_direct_cap;
    JNIEnv env{&table};

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
        12, JNI_TRUE, JNI_FALSE, JNI_FALSE, 0.05f, 0.002f, 0.0f);
    for (int i = 0; i < 12; ++i) {
        assert(out_s[i * 2] == static_cast<int16_t>(-2000 - i * 10));
        assert(out_s[i * 2 + 1] == static_cast<int16_t>(1000 + i * 10));
    }

    // 2. PCM16 mono downmix.
    Java_app_whisp_playback_DspNative_processPcm16Stereo(
        &env, nullptr, reinterpret_cast<jobject>(&in16), 0, reinterpret_cast<jobject>(&out16), 0,
        12, JNI_FALSE, JNI_TRUE, JNI_FALSE, 0.05f, 0.002f, 0.0f);
    for (int i = 0; i < 12; ++i) {
        assert(out_s[i * 2] == out_s[i * 2 + 1]);
        assert(std::abs(out_s[i * 2] - (-500)) <= 2);
    }

    // 3. PCM16 night dynamics lifts whispers and limits loud bursts.
    FakeDirectBuf loud_in{std::vector<uint8_t>(200 * 4)};
    FakeDirectBuf loud_out{std::vector<uint8_t>(200 * 4)};
    auto *li = reinterpret_cast<int16_t *>(loud_in.data.data());
    auto *lo = reinterpret_cast<int16_t *>(loud_out.data.data());
    for (int i = 0; i < 200; ++i) {
        li[i * 2] = 32000;
        li[i * 2 + 1] = 32000;
    }
    const float env_after = Java_app_whisp_playback_DspNative_processPcm16Stereo(
        &env, nullptr, reinterpret_cast<jobject>(&loud_in), 0, reinterpret_cast<jobject>(&loud_out),
        0, 200, JNI_FALSE, JNI_FALSE, JNI_TRUE, 0.0056f, 0.00012f, 0.0f);
    assert(env_after > 0.5f);
    assert(std::abs(lo[199 * 2]) < 29000);

    // 4. Float stereo mono & swap.
    FakeDirectBuf inf{std::vector<uint8_t>(8 * 8)};
    FakeDirectBuf outf{std::vector<uint8_t>(8 * 8)};
    auto *in_f = reinterpret_cast<float *>(inf.data.data());
    auto *out_f = reinterpret_cast<float *>(outf.data.data());
    for (int i = 0; i < 8; ++i) {
        in_f[i * 2] = 0.6f;
        in_f[i * 2 + 1] = -0.2f;
    }
    Java_app_whisp_playback_DspNative_processPcmFloatStereo(
        &env, nullptr, reinterpret_cast<jobject>(&inf), 0, reinterpret_cast<jobject>(&outf), 0, 8,
        JNI_FALSE, JNI_TRUE, JNI_FALSE, 0.05f, 0.002f, 0.0f);
    for (int i = 0; i < 8; ++i) {
        assert(std::fabs(out_f[i * 2] - 0.2f) < 0.001f);
        assert(std::fabs(out_f[i * 2 + 1] - 0.2f) < 0.001f);
    }

    std::puts("dsp_bridge_test passed");
    return 0;
}
