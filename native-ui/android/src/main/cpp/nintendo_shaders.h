#pragma once

namespace nintendoui {

inline constexpr const char* kQuadVertexShader = R"(#version 300 es
layout(location = 0) in vec2 a_pos;
layout(location = 1) in vec2 a_uv;
layout(location = 2) in vec4 a_color;

uniform vec2 u_screen;

out vec2 v_uv;
out vec4 v_color;

void main() {
    vec2 ndc = vec2(
        (a_pos.x / u_screen.x) * 2.0 - 1.0,
        1.0 - (a_pos.y / u_screen.y) * 2.0
    );
    gl_Position = vec4(ndc, 0.0, 1.0);
    v_uv = a_uv;
    v_color = a_color;
}
)";

inline constexpr const char* kQuadFragmentShader = R"(#version 300 es
precision mediump float;

uniform sampler2D u_texture;

in vec2 v_uv;
in vec4 v_color;

out vec4 o_color;

void main() {
    vec4 tex = texture(u_texture, v_uv);
    o_color = tex * v_color;
}
)";

inline constexpr const char* kFullQuadVertexShader = R"(#version 300 es
layout(location = 0) in vec2 a_pos;
out vec2 v_uv;

void main() {
    v_uv = a_pos * 0.5 + 0.5;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
)";

// Luminous Nintendo Console Backdrop + 6 Floating Translucent Beveled Glass Prisms
inline constexpr const char* kNintendoBgFragmentShader = R"(#version 300 es
precision highp float;

in vec2 v_uv;
uniform vec2 u_screen;
uniform int u_theme;
uniform float u_time;
uniform vec4 u_prisms[6]; // xy = center virtual px, z = half-size, w = rotAngleRad

out vec4 o_color;

float hash21(vec2 p) {
    p = mod(p, 64.0);
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float sdBevelBox(vec2 p, vec2 halfSize, float r) {
    vec2 q = abs(p) - halfSize + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

// Translucent beveled glass Nintendo crystal tile/prism (100% distinct from PS/DC spheres)
vec4 evalGlassPrism(vec2 px, vec4 prism, vec3 tintCol, vec3 rimCol, float isLightBg) {
    vec2 d = px - prism.xy;
    float s = prism.z;
    float maxBound = s * 1.48;
    if (abs(d.x) > maxBound || abs(d.y) > maxBound) {
        return vec4(0.0);
    }
    float c = cos(prism.w);
    float sn = sin(prism.w);
    vec2 lp = vec2(c * d.x + sn * d.y, -sn * d.x + c * d.y);

    float dist = sdBevelBox(lp, vec2(s * 0.86, s * 0.86), s * 0.22);
    float aa = 1.6;
    if (dist > aa) return vec4(0.0);

    float mask = 1.0 - smoothstep(-aa, aa, dist);
    vec2 nxy = clamp(lp / max(s * 0.86, 1.0), -1.0, 1.0);

    // Isometric beveled prism face normal + top-left studio light
    float bevelEdge = smoothstep(-s * 0.34, 0.0, dist);
    float topSheen = pow(clamp(1.0 - (nxy.y * 0.5 + 0.5), 0.0, 1.0), 2.4);
    float diagFacet = smoothstep(-0.35, 0.35, nxy.x * 0.65 + nxy.y * 0.75);
    float spec = pow(clamp(dot(normalize(vec2(-0.55, -0.82)), nxy * 0.5 + vec2(-0.2, -0.35)), 0.0, 1.0), 7.0);

    vec3 faceCol = mix(tintCol * 0.78, rimCol, diagFacet * 0.35 + bevelEdge * 0.55);
    faceCol += vec3(1.0) * (topSheen * 0.30 + spec * 0.24 + bevelEdge * 0.22);

    float alpha = mix(0.16, 0.52, bevelEdge * 0.78 + topSheen * 0.30);
    if (isLightBg > 0.5) {
        alpha = mix(0.18, 0.56, bevelEdge * 0.80 + topSheen * 0.28);
    }
    return vec4(faceCol, mask * alpha);
}

void main() {
    vec2 px = vec2(v_uv.x * u_screen.x, (1.0 - v_uv.y) * u_screen.y);
    vec2 norm = (px / max(u_screen, vec2(1.0))) - 0.5;
    vec2 normScaled = norm * vec2(1.12, 1.32);
    float rad2 = dot(normScaled, normScaled);
    float rad = sqrt(rad2);
    float bloom = exp(-rad2 * 2.0);

    vec3 bg = vec3(0.0);
    vec3 prismTint = vec3(0.12, 0.68, 0.94);
    vec3 prismRim = vec3(0.70, 0.92, 1.0);
    float isLight = 0.0;

    // Nintendo Wii / Switch dual sweeping wave ribbons
    float wPhase = v_uv.x * 3.4 + u_time * 0.22;
    float wY = 0.54 + 0.13 * sin(wPhase) + 0.04 * cos(wPhase * 2.2);
    float ribbon = exp(-pow((v_uv.y - wY) * 4.4, 2.0));
    float wY2 = 0.44 - 0.10 * cos(wPhase * 0.85 + 1.4);
    float ribbon2 = exp(-pow((v_uv.y - wY2) * 5.2, 2.0));

    if (u_theme == 0) {
        // Theme 0: Wii Pearl (luminous white ceramic + iconic Wii cyan horizon ribbon)
        isLight = 1.0;
        vec3 outerIce = vec3(0.81, 0.88, 0.95);
        vec3 midPearl = vec3(0.92, 0.96, 0.99);
        vec3 centerGlow = vec3(0.985, 0.992, 1.0);
        bg = mix(outerIce, midPearl, smoothstep(1.15, 0.15, rad));
        bg = mix(bg, centerGlow, bloom * 0.88);
        bg += vec3(0.02, 0.065, 0.095) * ribbon;
        bg += vec3(0.025, 0.045, 0.075) * ribbon2;
        prismTint = vec3(0.18, 0.66, 0.90);
        prismRim = vec3(0.68, 0.92, 1.0);
    } else if (u_theme == 1) {
        // Theme 1: GameCube Indigo (royal metallic purple-indigo + silver-violet prisms)
        vec3 outerViolet = vec3(0.055, 0.042, 0.145);
        vec3 innerIndigo = vec3(0.165, 0.130, 0.395);
        bg = mix(outerViolet, innerIndigo, bloom * 0.92);
        bg += vec3(0.06, 0.045, 0.14) * ribbon;
        bg += vec3(0.03, 0.055, 0.11) * ribbon2;
        prismTint = vec3(0.36, 0.30, 0.78);
        prismRim = vec3(0.76, 0.72, 1.0);
    } else if (u_theme == 2) {
        // Theme 2: Switch OLED Midnight (obsidian slate + Joy-Con Neon Cyan & Neon Red glow)
        vec3 outerOnyx = vec3(0.035, 0.042, 0.058);
        vec3 innerSlate = vec3(0.085, 0.105, 0.155);
        bg = mix(outerOnyx, innerSlate, bloom * 0.90);
        bg += vec3(0.008, 0.078, 0.110) * ribbon * smoothstep(0.8, 0.1, v_uv.x);
        bg += vec3(0.110, 0.022, 0.036) * ribbon2 * smoothstep(0.2, 0.9, v_uv.x);
        prismTint = vec3(0.14, 0.64, 0.86);
        prismRim = vec3(1.00, 0.34, 0.42);
    } else if (u_theme == 3) {
        // Theme 3: Famicom Crimson & Gold (lacquer crimson satin + gold champagne ribbons)
        vec3 outerRed = vec3(0.24, 0.028, 0.050);
        vec3 innerCrimson = vec3(0.47, 0.065, 0.105);
        bg = mix(outerRed, innerCrimson, bloom * 0.92);
        bg += vec3(0.14, 0.085, 0.028) * ribbon;
        bg += vec3(0.09, 0.030, 0.035) * ribbon2;
        prismTint = vec3(0.76, 0.16, 0.22);
        prismRim = vec3(0.96, 0.78, 0.38);
    } else {
        // Theme 4: Game Boy Classic (authentic DMG olive-emerald dot-matrix LCD glow)
        isLight = 1.0;
        vec3 outerLcd = vec3(0.49, 0.57, 0.25);
        vec3 innerLcd = vec3(0.66, 0.74, 0.37);
        bg = mix(outerLcd, innerLcd, bloom * 0.86);
        vec2 dotCell = fract(px * 0.28);
        float dotGrid = step(0.18, dotCell.x) * step(0.18, dotCell.y);
        bg *= mix(0.93, 1.02, dotGrid);
        bg += vec3(0.025, 0.035, 0.015) * ribbon;
        prismTint = vec3(0.20, 0.36, 0.14);
        prismRim = vec3(0.80, 0.88, 0.48);
    }

    // Subtle Nintendo Horizon Hairline Grid (clean console channel scan)
    float arcDist = abs(length((v_uv - vec2(0.5, 1.20)) * vec2(1.0, 1.50)) - 0.76);
    float arcLine = exp(-arcDist * 195.0) * (isLight > 0.5 ? 0.065 : 0.095);
    bg += prismRim * arcLine;

    // 6 Floating beveled glass Nintendo prisms
    for (int i = 0; i < 6; ++i) {
        vec4 p = evalGlassPrism(px, u_prisms[i], prismTint, prismRim, isLight);
        bg = mix(bg, p.rgb, p.a);
    }

    // Anti-banding dither on large high-DPI tablets
    float dither = (hash21(gl_FragCoord.xy) - 0.5) / 255.0;
    o_color = vec4(clamp(bg + dither, 0.0, 1.0), 1.0);
}
)";

inline constexpr const char* kRectVertexShader = R"(#version 300 es
layout(location = 0) in vec2 a_pos;

uniform vec2 u_screen;
uniform vec4 u_rect;

out vec2 v_uv;

void main() {
    vec2 uv = vec2(a_pos.x * 0.5 + 0.5, 0.5 - a_pos.y * 0.5);
    vec2 pos = u_rect.xy + uv * u_rect.zw;
    vec2 ndc = vec2(
        (pos.x / u_screen.x) * 2.0 - 1.0,
        1.0 - (pos.y / u_screen.y) * 2.0
    );
    gl_Position = vec4(ndc, 0.0, 1.0);
    v_uv = uv;
}
)";

// Analytic Rotating Isometric Nintendo 3D Prism Cube Emblem
inline constexpr const char* kNintendoPrismFragmentShader = R"(#version 300 es
precision highp float;

in vec2 v_uv;

uniform float u_progress; // 0..1 unfold/build progress
uniform float u_rot;      // rotation phase rad
uniform float u_alpha;
uniform vec4 u_colorA;    // primary accent
uniform vec4 u_colorB;    // secondary accent

out vec4 o_color;

float sdIsoHex(vec2 p, float r) {
    vec2 q = abs(p);
    return max(q.x * 0.866025 + q.y * 0.5, q.y) - r;
}

float sdSegment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);
    return length(pa - ba * h);
}

void main() {
    vec2 p = v_uv * 2.0 - 1.0;
    float prog = clamp(u_progress, 0.0, 1.0);
    if (prog <= 0.01) discard;

    // Gentle orbital tilt oscillation
    float ang = sin(u_rot) * 0.22;
    float c = cos(ang), s = sin(ang);
    vec2 rp = vec2(c * p.x - s * p.y, s * p.x + c * p.y);

    float outerR = 0.82 * mix(0.45, 1.0, prog);
    float dOuter = sdIsoHex(rp, outerR);
    float aa = 0.022;
    if (dOuter > aa) discard;

    float maskOuter = 1.0 - smoothstep(-aa, aa, dOuter);

    // 3 Isometric cube faces: Top (bright), Left (mid/A), Right (deep/B)
    float sector = atan(rp.y, rp.x);
    vec3 faceCol;
    if (sector < -0.5236 && sector > -2.6180) {
        // Top illuminated facet
        faceCol = mix(u_colorA.rgb, vec3(1.0), 0.52) * (0.94 + 0.08 * cos(u_rot * 1.5));
    } else if (rp.x < 0.0) {
        // Left facet
        faceCol = mix(u_colorA.rgb, u_colorB.rgb, 0.25) * 0.92;
    } else {
        // Right shaded facet
        faceCol = mix(u_colorB.rgb, u_colorA.rgb * 0.55, 0.45);
    }

    // Crisp inner Nintendo cube "G" / Prism groove carve lines
    float ridge1 = sdSegment(rp, vec2(0.0), vec2(0.0, outerR * 0.92));
    float ridge2 = sdSegment(rp, vec2(0.0), vec2(-outerR * 0.80, -outerR * 0.46));
    float ridge3 = sdSegment(rp, vec2(0.0), vec2(outerR * 0.80, -outerR * 0.46));
    float groove = min(ridge1, min(ridge2, ridge3));
    float ridgeMask = 1.0 - smoothstep(0.018, 0.055, groove);
    faceCol = mix(faceCol, vec3(1.0), ridgeMask * 0.62 * prog);

    // Center inner core cube reveal as progress completes
    float innerScale = clamp((prog - 0.35) / 0.65, 0.0, 1.0);
    if (innerScale > 0.01) {
        float dInner = sdIsoHex(rp, outerR * 0.34 * innerScale);
        float innerEdge = exp(-abs(dInner) * 32.0);
        faceCol = mix(faceCol, vec3(1.0), innerEdge * 0.45);
    }

    // Outer specular bezel rim
    float rimGlow = exp(-abs(dOuter) * 28.0) * 0.38;
    faceCol += vec3(1.0) * rimGlow;

    o_color = vec4(faceCol, maskOuter * u_alpha);
}
)";

// Frosted Glass Acrylic Panel / Pill / Card Shader
inline constexpr const char* kNintendoPanelFragmentShader = R"(#version 300 es
precision highp float;

in vec2 v_uv;

uniform vec2 u_size;
uniform float u_radius;
uniform float u_border_px;
uniform vec4 u_top_col;
uniform vec4 u_bot_col;
uniform vec4 u_border_col;
uniform vec4 u_glow_col; // xyz = glow color, w = glow strength
uniform float u_shimmer; // < 0 off, >= 0 phase 0..1

out vec4 o_color;

float sdRoundedBox(vec2 p, vec2 halfSize, float r) {
    vec2 q = abs(p) - halfSize + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec2 px = v_uv * u_size;
    vec2 center = u_size * 0.5;
    float r = clamp(u_radius, 0.0, min(center.x, center.y) - 0.5);
    float d = sdRoundedBox(px - center, center, r);

    float aa = 1.0;
    float outerAlpha = 1.0 - smoothstep(-aa, aa, d);
    if (outerAlpha <= 0.002) {
        discard;
    }

    vec4 fill = mix(u_top_col, u_bot_col, v_uv.y);

    // Top Nintendo ceramic-glass lip sheen
    float glassTop = (1.0 - smoothstep(0.0, 0.32, v_uv.y)) * 0.14;
    fill.rgb += vec3(glassTop);

    // Soft radial inner aura on focused cards
    if (u_glow_col.w > 0.002) {
        vec2 ell = (v_uv - vec2(0.5, 0.45)) * vec2(1.35, 1.85);
        float glow = exp(-dot(ell, ell) * 2.0);
        fill.rgb = mix(fill.rgb, u_glow_col.rgb, glow * u_glow_col.w);
    }

    // Moving crystal gleam on focused cards & active orbital nodes
    if (u_shimmer >= 0.0) {
        float diag = (v_uv.x * 0.72 + v_uv.y * 0.28) - u_shimmer;
        float gleam = exp(-diag * diag * 30.0) * 0.13;
        fill.rgb += vec3(gleam);
    }

    if (u_border_px > 0.05) {
        float innerMask = 1.0 - smoothstep(-u_border_px - aa, -u_border_px + aa, d);
        vec3 col = mix(u_border_col.rgb, fill.rgb, innerMask);
        float alpha = mix(u_border_col.a, fill.a, innerMask) * outerAlpha;
        o_color = vec4(col, alpha);
    } else {
        o_color = vec4(fill.rgb, fill.a * outerAlpha);
    }
}
)";

// 7 Procedural 3D-Shaded Nintendo Hardware Icons
// 0=OpticalDisc(GC/Wii), 1=MemoryCard(GC/SD), 2=SaveStar, 3=Gear, 4=GamePakCartridge, 5=HandheldDualScreen, 6=TumblingCube
inline constexpr const char* kNintendoIconFragmentShader = R"(#version 300 es
precision highp float;

in vec2 v_uv;

uniform int u_kind;
uniform float u_angle;
uniform float u_alpha;
uniform vec4 u_accent;
uniform int u_light_ui;

out vec4 o_color;

float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec2 p = v_uv * 2.0 - 1.0;
    vec4 res = vec4(0.0);

    if (u_kind == 0) {
        // 0: Nintendo Wii / GameCube Optical Disc with Holographic Rainbow Spin
        float r = length(p);
        float aa = 0.022;
        if (r > 0.96 || r < 0.15) discard;
        float mask = (1.0 - smoothstep(0.96 - aa, 0.96, r)) * smoothstep(0.15, 0.15 + aa, r);
        float ang = atan(p.y, p.x) + u_angle;
        vec3 col = vec3(0.86, 0.91, 0.96);
        if (r > 0.90) {
            col = vec3(0.62, 0.70, 0.80);
        } else if (r > 0.34) {
            float c1 = pow(max(0.0, cos(ang * 2.0)), 5.0);
            float c2 = pow(max(0.0, cos(ang * 2.0 + 1.57)), 6.0);
            float track = 0.03 * sin(r * 120.0);
            col = vec3(0.84, 0.90, 0.97) + vec3(track);
            col = mix(col, u_accent.rgb, c1 * 0.48);
            col += vec3(0.28, 0.76, 1.0) * c2 * 0.35;
            float ring8cm = exp(-pow((r - 0.62) * 65.0, 2.0)) * 0.20;
            col -= vec3(ring8cm);
        } else if (r > 0.24) {
            col = mix(u_accent.rgb, vec3(0.88, 0.94, 1.0), 0.55);
        } else {
            col = vec3(0.94, 0.97, 1.0);
        }
        res = vec4(col, mask);
    } else if (u_kind == 1) {
        // 1: Nintendo GameCube / SD Memory Card with Label Badge & Grip Notches
        float bodyD = sdRoundBox(p, vec2(0.62, 0.76), 0.11);
        if (bodyD > 0.03) discard;
        float mask = 1.0 - smoothstep(-0.02, 0.02, bodyD);

        vec3 shellCol = (u_light_ui > 0)
            ? mix(vec3(0.86, 0.90, 0.96), vec3(0.68, 0.74, 0.86), v_uv.y)
            : mix(vec3(0.32, 0.36, 0.52), vec3(0.19, 0.22, 0.35), v_uv.y);
        float border = 1.0 - smoothstep(-0.06, -0.02, bodyD);
        vec3 col = mix(u_accent.rgb * 0.85 + vec3(0.15), shellCol, border);

        // Front Label Sticker with accent stripe
        float lblD = sdRoundBox(p - vec2(0.0, 0.12), vec2(0.44, 0.44), 0.05);
        if (lblD < 0.03) {
            float lMask = 1.0 - smoothstep(-0.015, 0.015, lblD);
            vec3 lCol = mix(vec3(0.95, 0.97, 1.0), u_accent.rgb, step(-0.18, p.y) * 0.0 + (p.y < -0.02 ? 0.95 : 0.08));
            col = mix(col, lCol, lMask);
        }
        // Top grip ridges + glowing status LED
        for (int g = -1; g <= 1; ++g) {
            float ridge = sdRoundBox(p - vec2(float(g) * 0.22, -0.55), vec2(0.06, 0.10), 0.02);
            col = mix(col, shellCol * 0.72, 1.0 - smoothstep(-0.015, 0.015, ridge));
        }
        float ledGlow = 1.0 - smoothstep(0.04, 0.08, length(p - vec2(0.42, -0.54)));
        col = mix(col, vec3(0.20, 1.0, 0.62) * (0.75 + 0.25 * sin(u_angle * 2.5)), ledGlow);
        res = vec4(col, mask);
    } else if (u_kind == 2) {
        // 2: Nintendo Save Star Crystal
        float a = atan(p.y, p.x) + u_angle * 0.28;
        float r = length(p);
        float starR = 0.54 + 0.26 * cos(a * 5.0 - 1.5708);
        float d = r - starR;
        if (d > 0.03) discard;
        float mask = 1.0 - smoothstep(-0.02, 0.02, d);
        float facet = 0.68 + 0.32 * cos(a * 5.0);
        float core = exp(-r * r * 5.0) * 0.45;
        vec3 col = mix(u_accent.rgb, vec3(1.0, 0.86, 0.35), 0.62) * facet + vec3(core);
        res = vec4(col, mask);
    } else if (u_kind == 3) {
        // 3: Precision Settings Gear
        float r = length(p);
        float ang = atan(p.y, p.x) + u_angle * 0.45;
        float teeth = 0.095 * smoothstep(-0.25, 0.35, cos(ang * 8.0));
        float outer = r - (0.68 + teeth);
        float hole = 0.25 - r;
        float d = max(outer, hole);
        if (d > 0.03) discard;
        float mask = 1.0 - smoothstep(-0.02, 0.02, d);
        float sheen = 0.72 + 0.28 * dot(normalize(p + 0.001), vec2(-0.6, -0.8));
        vec3 col = mix(vec3(0.50, 0.66, 0.90), u_accent.rgb, 0.45) * sheen;
        float ring = exp(-pow((r - 0.44) * 28.0, 2.0)) * 0.35;
        col += vec3(ring);
        res = vec4(col, mask);
    } else if (u_kind == 4) {
        // 4: Nintendo Game Pak Cartridge (NES / SNES / N64 / GBA)
        float body = sdRoundBox(p - vec2(0.0, 0.04), vec2(0.70, 0.68), 0.08);
        float notch = sdRoundBox(p - vec2(0.0, -0.58), vec2(0.46, 0.14), 0.04);
        float d = min(body, notch);
        if (d > 0.03) discard;
        float mask = 1.0 - smoothstep(-0.02, 0.02, d);
        vec3 col = mix(vec3(0.42, 0.45, 0.52), vec3(0.58, 0.62, 0.70), 1.0 - v_uv.y);
        float lbl = sdRoundBox(p - vec2(0.0, 0.10), vec2(0.48, 0.40), 0.05);
        float lblM = 1.0 - smoothstep(-0.015, 0.015, lbl);
        col = mix(col, mix(u_accent.rgb, vec3(0.95, 0.97, 1.0), 0.22 * (1.0 - v_uv.y)), lblM);
        res = vec4(col, mask);
    } else if (u_kind == 5) {
        // 5: Nintendo DS / 3DS Dual-Screen Handheld
        float topScr = sdRoundBox(p - vec2(0.0, -0.34), vec2(0.62, 0.34), 0.06);
        float botScr = sdRoundBox(p - vec2(0.0, 0.38), vec2(0.62, 0.32), 0.06);
        float hinge = sdRoundBox(p - vec2(0.0, 0.02), vec2(0.68, 0.06), 0.03);
        float d = min(hinge, min(topScr, botScr));
        if (d > 0.03) discard;
        float mask = 1.0 - smoothstep(-0.02, 0.02, d);
        vec3 col = mix(vec3(0.24, 0.28, 0.38), vec3(0.42, 0.48, 0.58), 1.0 - v_uv.y);
        float scr1 = sdRoundBox(p - vec2(0.0, -0.34), vec2(0.46, 0.22), 0.03);
        float scr2 = sdRoundBox(p - vec2(0.0, 0.38), vec2(0.36, 0.22), 0.03);
        float sm = 1.0 - smoothstep(-0.015, 0.015, min(scr1, scr2));
        col = mix(col, mix(u_accent.rgb, vec3(0.75, 0.94, 1.0), 0.48), sm);
        res = vec4(col, mask);
    } else {
        // 6: Luminous Tumbling Crystal Cube for Boot Stage 1
        float c = cos(u_angle), s = sin(u_angle);
        vec2 rp = vec2(c * p.x - s * p.y, s * p.x + c * p.y);
        float d = sdRoundBox(rp, vec2(0.62, 0.62), 0.16);
        if (d > 0.03) discard;
        float mask = 1.0 - smoothstep(-0.02, 0.02, d);
        float diag = smoothstep(-0.5, 0.5, rp.x + rp.y);
        float rim = exp(-abs(d) * 20.0) * 0.45;
        vec3 col = mix(u_accent.rgb, vec3(1.0), 0.32 * (1.0 - diag) + rim);
        res = vec4(col, mask);
    }

    o_color = vec4(res.rgb, res.a * u_alpha);
}
)";

// Subtle LCD Dot-Matrix / Classic CRT Scanlines Overlay
inline constexpr const char* kNintendoScanlineFragmentShader = R"(#version 300 es
precision highp float;

in vec2 v_uv;
uniform vec2 u_viewport_px;
uniform float u_strength;

out vec4 o_color;

void main() {
    float yPx = v_uv.y * u_viewport_px.y;
    float line = 0.5 - 0.5 * cos(yPx * 3.14159265 * 0.65);
    float dark = line * u_strength;
    vec2 d = v_uv - 0.5;
    float vig = dot(d, d) * u_strength * 0.60;
    float alpha = clamp(dark + vig, 0.0, 0.45);
    o_color = vec4(0.0, 0.01, 0.03, alpha);
}
)";

} // namespace nintendoui
