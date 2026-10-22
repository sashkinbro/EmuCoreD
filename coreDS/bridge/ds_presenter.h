#pragma once

#include <cstdint>

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <mutex>

namespace EmuCoreDS {

class DsPresenter {
public:
    DsPresenter() = default;
    ~DsPresenter();

    bool SetSurface(ANativeWindow* window);
    void Release();
    void Present(const uint32_t* pixels, int width, int height);

private:
    bool CreateContextLocked();
    void DestroyContextLocked();
    bool EnsureProgramLocked();
    void DrawLocked(const uint32_t* pixels, int width, int height);

    std::mutex mutex_;
    ANativeWindow* window_ = nullptr;
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    EGLConfig config_ = nullptr;
    GLuint program_ = 0;
    GLuint texture_ = 0;
    GLint texture_size_[2] = {0, 0};
};

} // namespace EmuCoreDS
