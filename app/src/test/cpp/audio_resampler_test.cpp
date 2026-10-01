#include "../../main/cpp/audio_resampler.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

static void TestClockDrift(double drift, double playbackRate = 1.0) {
    constexpr size_t capacity = 16384;
    std::vector<int16_t> ring(capacity * 2);
    int16_t out[1024 * 2];
    size_t read = 0, write = 0;
    emucored::AudioResampler resampler;
    double nextFrame = 0, nextCallback = 0, carry = 0;
    while (nextCallback < 120) {
        if (nextFrame <= nextCallback) {
            carry += 44100.0 / 59.94;
            const size_t count = static_cast<size_t>(carry);
            carry -= count;
            const size_t available = (write + capacity - read) % capacity;
            assert(available + count < capacity);
            for (size_t i = 0; i < count; ++i) {
                ring[write * 2] = 12000;
                ring[write * 2 + 1] = -12000;
                write = (write + 1) % capacity;
            }
            nextFrame += 1.0 / (59.94 * playbackRate);
        } else {
            const auto count = resampler.Read(ring.data(), capacity, read, write, 4096, out, 1024, playbackRate);
            if (nextCallback > 1.0) assert(count == 1024);
            for (size_t i = 0; i < count; ++i) {
                assert(out[i * 2] == 12000 && out[i * 2 + 1] == -12000);
            }
            nextCallback += 1024.0 / (44100.0 * (1.0 + drift));
        }
    }
}

static void TestUnderrunPadding() {
    constexpr size_t capacity = 128;
    int16_t ring[capacity * 2]{};
    int16_t out[32 * 2]{};
    size_t read = 0, write = 0;
    for (size_t i = 0; i < 60; ++i) {
        ring[write * 2] = 12000;
        ring[write * 2 + 1] = -12000;
        write = (write + 1) % capacity;
    }
    emucored::AudioResampler resampler;
    // Prefill once, then a callback that runs out mid-way pads the remainder
    // with the last sample instead of muting or resetting.
    assert(resampler.Read(ring, capacity, read, write, 8, out, 32) == 32);
    const size_t partial = resampler.Read(ring, capacity, read, write, 8, out, 32);
    assert(partial > 0 && partial < 32);
    assert(out[partial * 2] == 12000 && out[31 * 2] == 12000);

    // The converter is not reset by the shortfall: an empty ring keeps
    // emitting the last sample rather than dropping to silence.
    read = write;
    const int16_t tail = out[0];
    assert(resampler.Read(ring, capacity, read, write, 8, out, 16) == 0);
    assert(out[0] == tail && out[15 * 2] == tail);

    // A reset re-arms the one-time prefill, so a fresh stream starts clean.
    resampler.Reset();
    read = 0;
    write = 48;
    assert(resampler.Read(ring, capacity, read, write, 8, out, 16) == 16);
}

// Device streams often run at 48 kHz while the core mixes at 44.1 kHz. The
// callback then consumes ring frames at coreRate/deviceRate; without that ratio
// the queue drains and every callback underruns.
static void TestDeviceRateConversion(double productionScale, size_t callbackFrames = 1024,
                                     double deviceRate = 48000.0) {
    constexpr size_t capacity = 48000;
    constexpr size_t target = 1536;
    std::vector<int16_t> ring(capacity * 2);
    int16_t out[1024 * 2];
    size_t read = 0, write = 0;
    emucored::AudioResampler resampler;
    const double frameSeconds = 1.0 / 59.94;
    const double callbackSeconds = static_cast<double>(callbackFrames) / deviceRate;
    const double baseRatio = 44100.0 / deviceRate;
    double emuTime = 0.0, deviceTime = 0.0, carry = 0.0;
    size_t underruns = 0, maxLevel = 0;
    while (deviceTime < 120.0) {
        const size_t available = (write + capacity - read) % capacity;
        if (available > maxLevel) maxLevel = available;
        if (emuTime <= deviceTime) {
            carry += 44100.0 / 59.94 * productionScale;
            const size_t count = static_cast<size_t>(carry);
            carry -= static_cast<double>(count);
            assert(available + count < capacity);
            for (size_t i = 0; i < count; ++i) {
                ring[write * 2] = 12000;
                ring[write * 2 + 1] = -12000;
                write = (write + 1) % capacity;
            }
            emuTime += frameSeconds;
        } else {
            const size_t sourced = resampler.Read(ring.data(), capacity, read, write, target,
                out, callbackFrames, baseRatio);
            // The one-time prefill may pad for a moment; steady state must not.
            if (deviceTime > 2.0 && sourced < callbackFrames) ++underruns;
            for (size_t i = 0; i < sourced; ++i) {
                assert(out[i * 2] == 12000 && out[i * 2 + 1] == -12000);
            }
            deviceTime += callbackSeconds;
        }
    }
    assert(underruns == 0);
    assert(maxLevel < capacity / 2);
}

int main() {
    TestClockDrift(-0.008);
    TestClockDrift(0);
    TestClockDrift(0.008);
    TestClockDrift(0.008, 50.0 / 59.94);
    TestClockDrift(-0.008, 2.0);
    TestUnderrunPadding();
    TestDeviceRateConversion(1.0);
    TestDeviceRateConversion(0.997);
    TestDeviceRateConversion(1.003);
    TestDeviceRateConversion(1.0, 192);
    TestDeviceRateConversion(0.997, 512);
    std::puts("Audio resampler: drift, ring wrap, underrun padding, prefill and rate conversion passed");
}
