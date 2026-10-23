#pragma once

#include <cstdint>

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <mutex>
#include "ds_presenter_color.h"

namespace EmuCoreDS {

class DsPresenter {
public:
    DsPresenter() = default;
    ~DsPresenter();

    bool SetSurface(ANativeWindow* window);
    // Release the graphics API connection while retaining the acquired native window.
    void ReleaseGraphicsContext();
    void Release();
    void Present(const uint32_t* pixels, int width, int height, DsPixelFormat format);

private:
    bool CreateContextLocked();
    void DestroyContextLocked();
    bool EnsureProgramLocked();
    void DrawLocked(const uint32_t* pixels, int width, int height, DsPixelFormat format);

    std::mutex mutex_;
    ANativeWindow* window_ = nullptr;
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    EGLConfig config_ = nullptr;
    GLuint program_ = 0;
    GLint format_uniform_ = -1;
    GLuint texture_ = 0;
    GLint texture_size_[2] = {0, 0};
};

} // namespace EmuCoreDS
