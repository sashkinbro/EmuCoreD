#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>

namespace emucored {

// Stereo resampler mirroring Azahar's StereoResampler (Core/HW/StereoResampler.cpp).
//
// The ring holds frames at the core's 44.1 kHz rate; Read() emits device-rate
// frames for the AAudio callback. Three behaviors matter for glitch-free audio:
//
//  * A queue level filter plus a +/-600 Hz input-rate shift follows device clock
//    drift (Azahar's control loop). It regulates the level left *after* each
//    callback: that cushion, not the pre-callback peak, is what keeps the next
//    callback from underrunning.
//  * Underruns are padded with the last emitted sample. The converter state is
//    never reset by a shortfall, so a frame-time spike costs a few samples
//    instead of a silence gap.
//  * The source rate is scaled by base_ratio, so a 48 kHz device stream consumes
//    the 44.1 kHz ring at the matching rate.
class AudioResampler {
public:
    void Reset() {
        fraction_ = 0.0;
        level_ = 0.0;
        ratio_ = 1.0;
        primed_ = false;
        last_left_ = 0;
        last_right_ = 0;
    }

    // Writes `frames` interleaved stereo samples at the device rate and returns
    // how many of them were sourced from the ring (the rest are padding).
    size_t Read(const int16_t* ring, size_t capacity, size_t& read, size_t write,
                size_t target, int16_t* out, size_t frames, double base_ratio = 1.0) {
        base_ratio = std::clamp(base_ratio, 0.25, 2.0);
        size_t available = (write + capacity - read) % capacity;
        // Frames this callback is expected to take out of the ring. Prefill this
        // much once per stream; afterwards a shortfall only pads rather than
        // muting until the ring refills.
        const size_t callback_need =
            static_cast<size_t>(base_ratio * static_cast<double>(frames)) + 2;
        if (!primed_) {
            if (available < std::max(target, size_t{2}) + callback_need) {
                std::fill(out, out + frames * 2, int16_t{0});
                return 0;
            }
            primed_ = true;
            level_ = static_cast<double>(available) - static_cast<double>(callback_need);
            ratio_ = base_ratio;
        }

        size_t produced = 0;
        while (produced < frames && available >= 2) {
            const size_t next = (read + 1) % capacity;
            for (size_t channel = 0; channel < 2; ++channel) {
                const double current = ring[read * 2 + channel];
                const double following = ring[next * 2 + channel];
                out[produced * 2 + channel] = static_cast<int16_t>(current + (following - current) * fraction_);
            }
            last_left_ = out[produced * 2];
            last_right_ = out[produced * 2 + 1];
            fraction_ += ratio_;
            const size_t consumed = static_cast<size_t>(fraction_);
            fraction_ -= static_cast<double>(consumed);
            read = (read + consumed) % capacity;
            available -= consumed;
            ++produced;
        }

        // Same control loop as Azahar: low-pass the level left in the queue,
        // then shift the source rate by up to +/-600 Hz to hold it at `target`.
        level_ += (static_cast<double>(available) - level_) / kControlAverage;
        const double offset = std::clamp((level_ - static_cast<double>(target)) * kControlFactor,
            -kMaxFreqShift, kMaxFreqShift);
        ratio_ = std::clamp(base_ratio * (1.0 + offset / kCoreRate), 0.1, 3.0);

        const size_t sourced = produced;
        for (; produced < frames; ++produced) {
            out[produced * 2] = last_left_;
            out[produced * 2 + 1] = last_right_;
        }
        return sourced;
    }

private:
    // Same control constants as Azahar's StereoResampler.
    static constexpr double kCoreRate = 44100.0;
    static constexpr double kMaxFreqShift = 600.0;
    static constexpr double kControlFactor = 0.2;
    static constexpr double kControlAverage = 32.0;

    double fraction_ = 0.0;
    double level_ = 0.0;
    double ratio_ = 1.0;
    bool primed_ = false;
    int16_t last_left_ = 0;
    int16_t last_right_ = 0;
};

}  // namespace emucored
