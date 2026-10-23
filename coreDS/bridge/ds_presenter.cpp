#include "ds_presenter.h"

#include <android/log.h>
#include <EGL/eglext.h>
#include <cstring>

namespace EmuCoreDS {

namespace {
constexpr const char* kVertexShader = R"(#version 300 es
layout(location = 0) in vec2 a_position;
layout(location = 1) in vec2 a_uv;
out vec2 v_uv;
void main() {
    v_uv = a_uv;
    gl_Position = vec4(a_position, 0.0, 1.0);
}
)";

GLuint CompileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        char log[1024]{};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        __android_log_print(ANDROID_LOG_ERROR, "EmuCoreDS", "Shader compile failed: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}
} // namespace

DsPresenter::~DsPresenter() {
    Release();
}

bool DsPresenter::SetSurface(ANativeWindow* window) {
    std::lock_guard lock(mutex_);
    if (window_ == window) return true;
    DestroyContextLocked();
    if (window_) ANativeWindow_release(window_);
    window_ = window;
    if (window_) ANativeWindow_acquire(window_);
    return true;
}

void DsPresenter::Release() {
    std::lock_guard lock(mutex_);
    DestroyContextLocked();
    if (window_) ANativeWindow_release(window_);
    window_ = nullptr;
}

bool DsPresenter::CreateContextLocked() {
    if (!window_) return false;
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) return false;
    EGLint major = 0;
    EGLint minor = 0;
    if (!eglInitialize(display_, &major, &minor)) return false;

    constexpr EGLint config_attributes[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 0,
        EGL_STENCIL_SIZE, 0,
        EGL_NONE
    };
    EGLint config_count = 0;
    if (!eglChooseConfig(display_, config_attributes, &config_, 1, &config_count) ||
        config_count < 1) {
        DestroyContextLocked();
        return false;
    }

    constexpr EGLint context_attributes[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, context_attributes);
    if (context_ == EGL_NO_CONTEXT) {
        DestroyContextLocked();
        return false;
    }
    surface_ = eglCreateWindowSurface(display_, config_, window_, nullptr);
    if (surface_ == EGL_NO_SURFACE) {
        DestroyContextLocked();
        return false;
    }
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        DestroyContextLocked();
        return false;
    }
    return true;
}

void DsPresenter::DestroyContextLocked() {
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
        if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
        eglTerminate(display_);
    }
    display_ = EGL_NO_DISPLAY;
    context_ = EGL_NO_CONTEXT;
    surface_ = EGL_NO_SURFACE;
    config_ = nullptr;
    program_ = 0;
    format_uniform_ = -1;
    texture_ = 0;
    texture_size_[0] = 0;
    texture_size_[1] = 0;
}

bool DsPresenter::EnsureProgramLocked() {
    if (program_ != 0) return true;
    const GLuint vertex = CompileShader(GL_VERTEX_SHADER, kVertexShader);
    const GLuint fragment = CompileShader(GL_FRAGMENT_SHADER, kDsPresenterFragmentShader);
    if (vertex == 0 || fragment == 0) {
        if (vertex) glDeleteShader(vertex);
        if (fragment) glDeleteShader(fragment);
        return false;
    }
    program_ = glCreateProgram();
    glAttachShader(program_, vertex);
    glAttachShader(program_, fragment);
    glLinkProgram(program_);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = GL_FALSE;
    glGetProgramiv(program_, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[1024]{};
        glGetProgramInfoLog(program_, sizeof(log), nullptr, log);
        __android_log_print(ANDROID_LOG_ERROR, "EmuCoreDS", "Program link failed: %s", log);
        glDeleteProgram(program_);
        program_ = 0;
        return false;
    }
    glUseProgram(program_);
    glUniform1i(glGetUniformLocation(program_, "u_texture"), 0);
    format_uniform_ = glGetUniformLocation(program_, "u_bgra");
    glGenTextures(1, &texture_);
    glBindTexture(GL_TEXTURE_2D, texture_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return true;
}

void DsPresenter::DrawLocked(const uint32_t* pixels, int width, int height, DsPixelFormat format) {
    if (!window_ || display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT ||
        surface_ == EGL_NO_SURFACE) {
        return;
    }
    if (!pixels || width <= 0 || height <= 0) return;
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) return;
    if (!EnsureProgramLocked()) return;
    glUseProgram(program_);
    // Software melonDS frames contain BGRA bytes; Vulkan readback already
    // contains RGBA. Keep the distinction at the presentation boundary.
    glUniform1i(format_uniform_, format == DsPixelFormat::Bgra8 ? 1 : 0);

    if (texture_size_[0] != width || texture_size_[1] != height) {
        glBindTexture(GL_TEXTURE_2D, texture_);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA,
                     GL_UNSIGNED_BYTE, nullptr);
        texture_size_[0] = width;
        texture_size_[1] = height;
    }
    glBindTexture(GL_TEXTURE_2D, texture_);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE,
                    pixels);

    EGLint surface_width = 0;
    EGLint surface_height = 0;
    eglQuerySurface(display_, surface_, EGL_WIDTH, &surface_width);
    eglQuerySurface(display_, surface_, EGL_HEIGHT, &surface_height);
    if (surface_width <= 0 || surface_height <= 0) return;

    const float target_aspect = static_cast<float>(width) / static_cast<float>(height);
    int viewport_width = surface_width;
    int viewport_height = static_cast<int>(surface_width / target_aspect);
    if (viewport_height > surface_height) {
        viewport_height = surface_height;
        viewport_width = static_cast<int>(surface_height * target_aspect);
    }
    const int viewport_x = (surface_width - viewport_width) / 2;
    const int viewport_y = (surface_height - viewport_height) / 2;

    glViewport(viewport_x, viewport_y, viewport_width, viewport_height);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);

    const GLfloat vertices[] = {
        -1.0f, -1.0f, 0.0f, 1.0f,
         1.0f, -1.0f, 1.0f, 1.0f,
        -1.0f,  1.0f, 0.0f, 0.0f,
         1.0f,  1.0f, 1.0f, 0.0f,
    };
    GLuint vbo = 0;
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STREAM_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat),
                          reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat),
                          reinterpret_cast<void*>(2 * sizeof(GLfloat)));
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glDeleteBuffers(1, &vbo);
    eglSwapBuffers(display_, surface_);
    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}

void DsPresenter::Present(const uint32_t* pixels, int width, int height, DsPixelFormat format) {
    std::lock_guard lock(mutex_);
    if (!window_) return;
    if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT ||
        surface_ == EGL_NO_SURFACE) {
        if (!CreateContextLocked()) return;
    }
    DrawLocked(pixels, width, height, format);
}

} // namespace EmuCoreDS
