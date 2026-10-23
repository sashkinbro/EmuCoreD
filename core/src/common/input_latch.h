#pragma once

#include <atomic>
#include <cstdint>
#include <tuple>

namespace Common {

// Preserve a press that starts and ends between guest input polls.
class ButtonPressLatch {
public:
    void SetPressed(bool pressed) {
        status.store(pressed);
        if (pressed)
            pressed_since_last_read.store(true);
    }

    bool GetStatus() const {
        const bool pressed = pressed_since_last_read.exchange(false);
        return status.load() || pressed;
    }

private:
    std::atomic<bool> status{false};
    mutable std::atomic<bool> pressed_since_last_read{false};
};

// Call under the touch-state mutex. Each device retains its own read cursor.
class TouchPressLatch {
public:
    std::uint64_t CurrentPressCount() const {
        return press_count;
    }

    void OnPress(bool already_pressed) {
        if (!already_pressed)
            ++press_count;
    }

    void OnRelease(bool was_pressed, float x, float y) {
        if (was_pressed) {
            last_press_x = x;
            last_press_y = y;
        }
    }

    std::tuple<float, float, bool> Sample(std::uint64_t& seen_press_count,
                                        float x, float y, bool pressed) const {
        const bool unseen = seen_press_count != press_count;
        seen_press_count = press_count;
        if (!pressed && unseen)
            return {last_press_x, last_press_y, true};
        return {x, y, pressed};
    }

private:
    std::uint64_t press_count = 0;
    float last_press_x = 0.f;
    float last_press_y = 0.f;
};

} // namespace Common
