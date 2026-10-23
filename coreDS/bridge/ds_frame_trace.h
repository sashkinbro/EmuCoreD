#pragma once

namespace EmuCoreDS {
// Poll only at the existing sampled cadence while tracing is off/exhausted.
// Mode 2 polls each active frame so switching the property off stops the burst.
class DsFrameTrace {
public:
    constexpr bool shouldPoll(bool sampled_frame) const {
        return sampled_frame || (mode_ == 2 && remaining_ > 0);
    }
    constexpr void observe(int mode) {
        if (mode != mode_) {
            mode_ = mode;
            remaining_ = mode == 2 ? 90 : 0;
        }
    }
    constexpr bool take(bool sampled_frame) {
        if (mode_ == 1) return sampled_frame;
        if (mode_ != 2 || remaining_ == 0) return false;
        --remaining_;
        return true;
    }
    constexpr bool isCapturing() const { return mode_ == 1 || (mode_ == 2 && remaining_ > 0); }
    constexpr void reset() { mode_ = 0; remaining_ = 0; }
private:
    int mode_ = 0;
    int remaining_ = 0;
};
} // namespace EmuCoreDS
