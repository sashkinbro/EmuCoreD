#pragma once

#include "nintendo_renderer.h"
#include <algorithm>
#include <cmath>

namespace nintendoui {

inline constexpr float kPi = 3.14159265359f;
inline constexpr float kTwoPi = 6.28318530718f;

inline float Clamp01(float v) {
    return std::max(0.0f, std::min(1.0f, v));
}

inline float Lerp(float a, float b, float t) {
    return a + (b - a) * t;
}

inline RgbaColor LerpColor(const RgbaColor& a, const RgbaColor& b, float t) {
    return {
        Lerp(a.r, b.r, t),
        Lerp(a.g, b.g, t),
        Lerp(a.b, b.b, t),
        Lerp(a.a, b.a, t),
    };
}

inline float SmoothStep(float a, float b, float x) {
    float t = Clamp01((x - a) / std::max(0.0001f, b - a));
    return t * t * (3.0f - 2.0f * t);
}

inline float EaseOutCubic(float t) {
    t = Clamp01(t);
    float inv = 1.0f - t;
    return 1.0f - inv * inv * inv;
}

inline float EaseOutBack(float t) {
    t = Clamp01(t);
    const float c1 = 1.70158f;
    const float c3 = c1 + 1.0f;
    float u = t - 1.0f;
    return 1.0f + c3 * u * u * u + c1 * u * u;
}

inline RgbaColor DefaultConsoleAccent(const std::string& id, float alpha = 1.0f) {
    if (id == "3ds") return {0.84f, 0.14f, 0.18f, alpha};      // Nintendo 3DS Flame Red
    if (id == "nds") return {0.26f, 0.48f, 0.78f, alpha};      // Nintendo DS Ice Blue
    if (id == "wii") return {0.00f, 0.62f, 0.84f, alpha};      // Wii Cyan
    if (id == "gamecube") return {0.42f, 0.34f, 0.86f, alpha}; // GameCube Indigo
    if (id == "n64") return {0.14f, 0.60f, 0.32f, alpha};      // N64 Emerald Green
    if (id == "snes") return {0.48f, 0.35f, 0.78f, alpha};     // SNES Violet
    if (id == "nes") return {0.78f, 0.18f, 0.22f, alpha};      // NES / Famicom Crimson
    if (id == "gba") return {0.32f, 0.28f, 0.72f, alpha};      // GBA Indigo
    if (id == "gbc") return {0.88f, 0.48f, 0.10f, alpha};      // GBC Amber Orange
    if (id == "gb") return {0.26f, 0.48f, 0.22f, alpha};       // Game Boy Leaf
    return {0.00f, 0.62f, 0.84f, alpha};
}

} // namespace nintendoui
