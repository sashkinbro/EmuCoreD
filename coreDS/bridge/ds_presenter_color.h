#pragma once

namespace EmuCoreDS {

// Byte order in memory, independent of the packed uint32_t spelling.
enum class DsPixelFormat { Bgra8, Rgba8 };

inline constexpr const char* kDsPresenterFragmentShader = R"(#version 300 es
precision mediump float;
uniform sampler2D u_texture;
uniform bool u_bgra;
in vec2 v_uv;
out vec4 fragColor;
void main() {
    vec4 color = texture(u_texture, v_uv);
    fragColor = u_bgra ? color.bgra : color;
}
)";

} // namespace EmuCoreDS
