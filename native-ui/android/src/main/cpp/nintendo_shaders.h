#pragma once

namespace nintendoui::shaders {

inline constexpr const char* kQuadVert = R"(#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;

uniform vec4 uRect; // x, y, w, h in NDC or pixel space converted by uScreen
uniform vec2 uScreen;

out vec2 vUv;
out vec2 vLocalPx;
out vec2 vSizePx;

void main() {
    vec2 px = uRect.xy + aPos * uRect.zw;
    vec2 ndc = (px / uScreen) * 2.0 - 1.0;
    ndc.y = -ndc.y;
    gl_Position = vec4(ndc, 0.0, 1.0);
    vUv = aUv;
    vLocalPx = aPos * uRect.zw;
    vSizePx = uRect.zw;
}
)";

// Ambient Nintendo backdrop shader with soft dual-radial gradients, subtle wave / grid motif,
// and an ultra-cheap Eco branch for low-end devices.
inline constexpr const char* kBackdropFrag = R"(#version 300 es
precision mediump float;
in vec2 vUv;
in vec2 vLocalPx;
in vec2 vSizePx;

uniform vec4 uBgTop;
uniform vec4 uBgBottom;
uniform vec4 uAccentA;
uniform vec4 uAccentB;
uniform float uTime;
uniform int uEcoMode;
uniform int uThemeStyle; // 0=Wii, 1=GC, 2=Famicom, 3=Switch, 4=GB

out vec4 fragColor;

void main() {
    float gradY = clamp(vUv.y, 0.0, 1.0);
    vec3 col = mix(uBgTop.rgb, uBgBottom.rgb, smoothstep(0.0, 1.0, gradY));

    if (uEcoMode == 0) {
        vec2 p = vUv - vec2(0.5);
        float driftA = sin(uTime * 0.45) * 0.08;
        float driftB = cos(uTime * 0.35) * 0.06;
        float d1 = length(vUv - vec2(0.20 + driftA, 0.24 + driftB));
        float d2 = length(vUv - vec2(0.82 - driftA, 0.78 - driftB));
        col += uAccentA.rgb * exp(-d1 * 3.4) * 0.16;
        col += uAccentB.rgb * exp(-d2 * 3.2) * 0.12;

        if (uThemeStyle == 0) {
            // Wii Pearl: delicate horizontal scan-channel sheen
            float band = sin(vLocalPx.y * 0.18 - uTime * 0.8) * 0.5 + 0.5;
            col += vec3(0.015) * band;
        } else if (uThemeStyle == 1) {
            // GameCube Indigo: soft geometric diagonal tiles
            float g = abs(fract((vLocalPx.x + vLocalPx.y) * 0.012) - 0.5) * 2.0;
            col += uAccentA.rgb * smoothstep(0.92, 1.0, g) * 0.06;
        } else if (uThemeStyle == 4) {
            // Game Boy Classic: subtle dot-matrix pixel grid
            vec2 cell = fract(vLocalPx * 0.25);
            float dotMask = step(0.14, cell.x) * step(0.14, cell.y);
            col *= mix(0.95, 1.01, dotMask);
        } else {
            // Subtle Nintendo channel ribbon
            float ribbon = smoothstep(0.18, 0.0, abs(p.y + sin(p.x * 3.5 + uTime * 0.5) * 0.05));
            col += uAccentA.rgb * ribbon * 0.05;
        }
    }

    fragColor = vec4(col, 1.0);
}
)";

// SDF rounded rectangle with optional border, gradient, inner highlight, and drop shadow.
inline constexpr const char* kRoundedRectFrag = R"(#version 300 es
precision mediump float;
in vec2 vUv;
in vec2 vLocalPx;
in vec2 vSizePx;

uniform vec4 uColorTop;
uniform vec4 uColorBottom;
uniform vec4 uBorderColor;
uniform float uRadius;
uniform float uBorderWidth;
uniform float uGlow;

out vec4 fragColor;

float roundedBoxSdf(vec2 p, vec2 halfSize, float r) {
    vec2 q = abs(p) - halfSize + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec2 halfSize = vSizePx * 0.5;
    vec2 center = vLocalPx - halfSize;
    float r = min(uRadius, min(halfSize.x, halfSize.y));
    float d = roundedBoxSdf(center, halfSize - 1.5, max(0.0, r - 1.5));

    float alpha = 1.0 - smoothstep(-0.75, 0.75, d);
    if (alpha <= 0.002 && uGlow <= 0.01) discard;

    vec4 fill = mix(uColorTop, uColorBottom, clamp(vUv.y, 0.0, 1.0));
    float borderMix = 0.0;
    if (uBorderWidth > 0.05) {
        float inner = roundedBoxSdf(center, halfSize - 1.5 - uBorderWidth, max(0.0, r - 1.5 - uBorderWidth));
        borderMix = smoothstep(-0.75, 0.75, inner);
    }

    // Top Nintendo ceramic edge sheen
    float topSheen = (1.0 - smoothstep(0.0, 0.22, vUv.y)) * 0.07;
    vec3 rgb = mix(fill.rgb + topSheen, uBorderColor.rgb, borderMix * uBorderColor.a);
    float outAlpha = fill.a * alpha;

    if (uGlow > 0.01 && d > 0.0) {
        float glowAlpha = exp(-d * 0.28) * uGlow * 0.45;
        rgb = mix(rgb, uBorderColor.rgb, 0.6);
        outAlpha = max(outAlpha, glowAlpha);
    }

    if (outAlpha <= 0.002) discard;
    fragColor = vec4(rgb, outAlpha);
}
)";

// Rounded-corner textured image shader (for game boxart, save-state previews, and SDF text).
inline constexpr const char* kTexturedRectFrag = R"(#version 300 es
precision mediump float;
in vec2 vUv;
in vec2 vLocalPx;
in vec2 vSizePx;

uniform sampler2D uTex;
uniform vec4 uTint;
uniform float uRadius;
uniform int uUseRounded;
uniform int uCoverMode; // 0=fit/stretch UV, 1=preserve aspect via uTexAspect inside box

out vec4 fragColor;

float roundedBoxSdf(vec2 p, vec2 halfSize, float r) {
    vec2 q = abs(p) - halfSize + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec4 texCol = texture(uTex, vUv) * uTint;
    if (uUseRounded != 0) {
        vec2 halfSize = vSizePx * 0.5;
        float r = min(uRadius, min(halfSize.x, halfSize.y));
        float d = roundedBoxSdf(vLocalPx - halfSize, halfSize, r);
        float mask = 1.0 - smoothstep(-0.75, 0.75, d);
        texCol.a *= mask;
    }
    if (texCol.a <= 0.002) discard;
    fragColor = texCol;
}
)";

} // namespace nintendoui::shaders
