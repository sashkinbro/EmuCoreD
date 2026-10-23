// Standalone Android GLES3 sentinel for the production DS presentation shader.
// NDK: clang++ --target=aarch64-linux-android28 -std=c++17 -fPIE -pie
//      coreDS/tests/ds_presenter_color_test.cpp -lEGL -lGLESv3 -o ds-presenter-color-test
#include "../bridge/ds_presenter_color.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstdlib>

static void Require(bool condition, const char* message) {
    if (!condition) { std::fprintf(stderr, "FAIL: %s (EGL=%x GL=%x)\n", message, eglGetError(), glGetError()); std::exit(1); }
}

static GLuint Compile(GLenum type, const char* source) {
    const GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint status = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (!status) {
        char diagnostic[2048]{};
        glGetShaderInfoLog(shader, sizeof(diagnostic), nullptr, diagnostic);
        std::fprintf(stderr, "%s\n", diagnostic);
    }
    Require(status == GL_TRUE, "production shader compiles");
    return shader;
}

int main() {
    const EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    Require(display != EGL_NO_DISPLAY && eglInitialize(display, nullptr, nullptr), "initialize EGL");
    const EGLint attributes[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig config{};
    EGLint count = 0;
    Require(eglChooseConfig(display, attributes, &config, 1, &count) && count > 0, "choose RGBA8 pbuffer");
    const EGLint size[] = {EGL_WIDTH, 3, EGL_HEIGHT, 1, EGL_NONE};
    const EGLint version[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    const EGLSurface surface = eglCreatePbufferSurface(display, config, size);
    const EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, version);
    Require(surface != EGL_NO_SURFACE && context != EGL_NO_CONTEXT && eglMakeCurrent(display, surface, surface, context), "make GLES3 context current");
    const char* vertex = R"(#version 300 es
out vec2 v_uv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    v_uv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
})";
    const GLuint vs = Compile(GL_VERTEX_SHADER, vertex);
    const GLuint fs = Compile(GL_FRAGMENT_SHADER, EmuCoreDS::kDsPresenterFragmentShader);
    const GLuint program = glCreateProgram();
    glAttachShader(program, vs); glAttachShader(program, fs); glLinkProgram(program);
    GLint linked = 0; glGetProgramiv(program, GL_LINK_STATUS, &linked);
    Require(linked == GL_TRUE, "link production fragment shader");
    glUseProgram(program);
    glUniform1i(glGetUniformLocation(program, "u_texture"), 0);
    GLuint texture = 0; glGenTextures(1, &texture); glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glViewport(0, 0, 3, 1);
    glDisable(GL_DITHER); glDisable(GL_BLEND);
    // Literal RGB sentinels: red car, blue, gold Chevrolet logo. Neither end is neutral.
    const std::array<uint8_t, 12> expected = {225, 35, 20, 255, 18, 52, 229, 255, 212, 175, 55, 255};
    for (const auto format : {EmuCoreDS::DsPixelFormat::Bgra8, EmuCoreDS::DsPixelFormat::Rgba8}) {
        auto input = expected;
        if (format == EmuCoreDS::DsPixelFormat::Bgra8) {
            for (size_t i = 0; i < input.size(); i += 4) { const auto red = input[i]; input[i] = input[i + 2]; input[i + 2] = red; }
        }
        glUniform1i(glGetUniformLocation(program, "u_bgra"), format == EmuCoreDS::DsPixelFormat::Bgra8);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 3, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, input.data());
        glDrawArrays(GL_TRIANGLES, 0, 3);
        std::array<uint8_t, 12> actual{};
        glReadPixels(0, 0, 3, 1, GL_RGBA, GL_UNSIGNED_BYTE, actual.data());
        Require(glGetError() == GL_NO_ERROR, "upload/draw/readback succeeds");
        for (size_t i = 0; i < expected.size(); ++i) {
            if (std::abs(int(actual[i]) - int(expected[i])) > 1) {
                std::fprintf(stderr, "FAIL: %s pixel=%zu channel=%zu expected=%u actual=%u\n", format == EmuCoreDS::DsPixelFormat::Bgra8 ? "software BGRA" : "Vulkan RGBA", i / 4, i % 4, expected[i], actual[i]);
                return 1;
            }
        }
        std::printf("PASS: %s red/blue/gold sentinels\n", format == EmuCoreDS::DsPixelFormat::Bgra8 ? "software BGRA" : "Vulkan RGBA");
    }
    glDeleteTextures(1, &texture); glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs);
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(display, surface); eglDestroyContext(display, context); eglTerminate(display);
    return 0;
}
