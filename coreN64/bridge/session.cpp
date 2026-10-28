// SPDX-License-Identifier: GPL-3.0-or-later
#include "android_output.h"
#include "libretro.h"
#include <GLES3/gl3.h>
#include <android/log.h>
#include <cstdarg>
#include <dlfcn.h>
#include <map>
#include <memory>
#include <mutex>

namespace {
struct Session {
    AndroidOutput audio;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLConfig config{};
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface pbuffer = EGL_NO_SURFACE, window_surface = EGL_NO_SURFACE;
    ANativeWindow* window = nullptr;
    GLuint framebuffer = 0, color = 0, depth = 0;
    int width = 640, height = 480;
    retro_hw_render_callback hw{};
    retro_system_av_info av{};
    std::map<std::string, std::string> options;
    std::vector<uint8_t> rom;
    std::string path, directory;
    uint64_t rom_hash = 0;
    uint16_t buttons[4]{};
    int16_t axes[4][2][2]{};
    bool initialized = false, loaded = false, context_ready = false, options_changed = false;

    EGLSurface currentSurface() const { return window_surface == EGL_NO_SURFACE ? pbuffer : window_surface; }
    bool makeCurrent() const {
        return display != EGL_NO_DISPLAY && context != EGL_NO_CONTEXT && currentSurface() != EGL_NO_SURFACE &&
            eglMakeCurrent(display, currentSurface(), currentSurface(), context);
    }
    bool initializeGraphics() {
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) return false;
        const EGLint attributes[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT | EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_NONE};
        EGLint count = 0;
        if (!eglChooseConfig(display, attributes, &config, 1, &count) || !count) return false;
        const EGLint surface_attributes[] = {EGL_WIDTH, 640, EGL_HEIGHT, 480, EGL_NONE};
        const EGLint context_attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        pbuffer = eglCreatePbufferSurface(display, config, surface_attributes);
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attributes);
        return makeCurrent();
    }
    bool initializeFramebuffer() {
        if (!makeCurrent()) return false;
        glGenTextures(1, &color);
        glBindTexture(GL_TEXTURE_2D, color);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glGenRenderbuffers(1, &depth);
        glBindRenderbuffer(GL_RENDERBUFFER, depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
        glGenFramebuffers(1, &framebuffer);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depth);
        return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    }
    void attach(JNIEnv* env, jobject surface) {
        if (display == EGL_NO_DISPLAY) return;
        eglMakeCurrent(display, pbuffer, pbuffer, context);
        if (window_surface != EGL_NO_SURFACE) eglDestroySurface(display, window_surface);
        if (window) ANativeWindow_release(window);
        window_surface = EGL_NO_SURFACE; window = nullptr;
        if (surface) {
            window = ANativeWindow_fromSurface(env, surface);
            if (window) {
                EGLint format = 0;
                if (eglGetConfigAttrib(display, config, EGL_NATIVE_VISUAL_ID, &format) &&
                    ANativeWindow_setBuffersGeometry(window, 0, 0, format) == 0)
                    window_surface = eglCreateWindowSurface(display, config, window, nullptr);
            }
        }
        if (makeCurrent()) eglSwapInterval(display, 0);
    }
    void present() {
        if (window_surface == EGL_NO_SURFACE || !makeCurrent()) return;
        EGLint screen_width = 0, screen_height = 0;
        eglQuerySurface(display, window_surface, EGL_WIDTH, &screen_width);
        eglQuerySurface(display, window_surface, EGL_HEIGHT, &screen_height);
        if (screen_width <= 0 || screen_height <= 0) return;
        const auto viewport = displayViewport(screen_width, screen_height, 4.0 / 3.0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glViewport(0, 0, screen_width, screen_height);
        glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        glBlitFramebuffer(0, 0, width, height, viewport.x, viewport.y,
            viewport.x + viewport.width, viewport.y + viewport.height, GL_COLOR_BUFFER_BIT, GL_LINEAR);
        eglSwapBuffers(display, window_surface);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    }
    void close() {
        makeCurrent();
        if (loaded) {
            void* ram = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
            const size_t size = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
            if (ram && size && size <= 16 * 1024 * 1024) writeBytes(directory + "/battery.srm", ram, size);
            if (context_ready && hw.context_destroy) hw.context_destroy();
            retro_unload_game(); loaded = false;
        }
        if (initialized) { retro_deinit(); initialized = false; }
        if (display != EGL_NO_DISPLAY) {
            if (framebuffer) glDeleteFramebuffers(1, &framebuffer);
            if (depth) glDeleteRenderbuffers(1, &depth);
            if (color) glDeleteTextures(1, &color);
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (window_surface != EGL_NO_SURFACE) eglDestroySurface(display, window_surface);
            if (pbuffer != EGL_NO_SURFACE) eglDestroySurface(display, pbuffer);
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
            eglTerminate(display);
        }
        if (window) ANativeWindow_release(window);
    }
};
Session* active = nullptr;
std::mutex ownership;

void logMessage(retro_log_level level, const char* format, ...) {
    va_list args; va_start(args, format);
    __android_log_vprint(level == RETRO_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_DEBUG,
                         "EmuCoreN64", format, args);
    va_end(args);
}
uintptr_t currentFramebuffer() { return active ? active->framebuffer : 0; }
retro_proc_address_t getProc(const char* name) {
    auto address = eglGetProcAddress(name);
    return address ? reinterpret_cast<retro_proc_address_t>(address)
                   : reinterpret_cast<retro_proc_address_t>(dlsym(RTLD_DEFAULT, name));
}
bool environment(unsigned command, void* data) {
    if (!active) return false;
    switch (command) {
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *static_cast<const char**>(data) = active->directory.c_str(); return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        static_cast<retro_log_callback*>(data)->log = logMessage; return true;
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
        *static_cast<unsigned*>(data) = 0; return true;
    case RETRO_ENVIRONMENT_SET_VARIABLES: {
        auto* variables = static_cast<const retro_variable*>(data);
        for (; variables && variables->key; ++variables) {
            std::string value = variables->value ? variables->value : "";
            const auto semicolon = value.find(';');
            if (semicolon != std::string::npos) value = value.substr(semicolon + 1);
            const auto first = value.find_first_not_of(' ');
            if (first != std::string::npos) value = value.substr(first);
            active->options.emplace(variables->key, value.substr(0, value.find('|')));
        }
        return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        auto* variable = static_cast<retro_variable*>(data);
        auto found = active->options.find(variable->key);
        if (found == active->options.end()) return false;
        variable->value = found->second.c_str(); return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *static_cast<bool*>(data) = active->options_changed; active->options_changed = false; return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE: *static_cast<bool*>(data) = true; return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: return true;
    case RETRO_ENVIRONMENT_SET_HW_RENDER: {
        auto* callback = static_cast<retro_hw_render_callback*>(data);
        if (callback->context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
            callback->context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION) return false;
        callback->get_current_framebuffer = currentFramebuffer;
        callback->get_proc_address = getProc;
        active->hw = *callback; return true;
    }
    default: return false;
    }
}
void video(const void* data, unsigned width, unsigned height, size_t) {
    if (active && data == RETRO_HW_FRAME_BUFFER_VALID && width && height) active->present();
}
size_t audio(const int16_t* samples, size_t frames) {
    if (active && samples && frames <= 65536) active->audio.audioWrite(samples, static_cast<int>(frames));
    return frames;
}
void poll() {}
int16_t input(unsigned player, unsigned device, unsigned index, unsigned id) {
    if (!active || player >= 4) return 0;
    if (device == RETRO_DEVICE_JOYPAD && id < 16) return (active->buttons[player] & (1u << id)) ? 1 : 0;
    if (device == RETRO_DEVICE_ANALOG && index < 2 && id < 2) return active->axes[player][index][id];
    return 0;
}
}

#define JNI(type) extern "C" JNIEXPORT type JNICALL
JNI(jlong) Java_com_sbro_emucored_core_n64_Mupen64Native_nativeCreate(JNIEnv* env, jobject, jstring path, jstring directory) {
    std::scoped_lock lock(ownership);
    if (active) return 0;
    auto session = std::make_unique<Session>();
    session->path = utf(env, path); session->directory = utf(env, directory);
    session->rom = readBytes(session->path);
    if (session->rom.size() < 4096 || !session->initializeGraphics()) { session->close(); return 0; }
    session->rom_hash = hashBytes(session->rom.data(), session->rom.size());
    active = session.get();
    retro_set_environment(environment); retro_set_video_refresh(video); retro_set_audio_sample_batch(audio);
    retro_set_input_poll(poll); retro_set_input_state(input); retro_init(); session->initialized = true;
    retro_game_info game{session->path.c_str(), session->rom.data(), session->rom.size(), nullptr};
    if (!retro_load_game(&game) || !session->hw.context_reset) {
        session->close(); active = nullptr; return 0;
    }
    session->loaded = true;
    retro_get_system_av_info(&session->av);
    session->width = std::clamp<int>(session->av.geometry.base_width, 1, 4096);
    session->height = std::clamp<int>(session->av.geometry.base_height, 1, 4096);
    if (!session->initializeFramebuffer()) { session->close(); active = nullptr; return 0; }
    session->hw.context_reset(); session->context_ready = true;
    auto battery = readBytes(session->directory + "/battery.srm");
    auto* ram = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    const size_t ram_size = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (ram && battery.size() == ram_size) std::memcpy(ram, battery.data(), ram_size);
    const int rate = static_cast<int>(session->av.timing.sample_rate);
    if (rate < 8000 || rate > 192000 || !session->audio.initAudio(rate)) {
        session->close(); active = nullptr; return 0;
    }
    return reinterpret_cast<jlong>(session.release());
}
JNI(void) Java_com_sbro_emucored_core_n64_Mupen64Native_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    std::scoped_lock lock(ownership);
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session || session != active) return;
    session->close(); active = nullptr; delete session;
}
JNI(jboolean) Java_com_sbro_emucored_core_n64_Mupen64Native_nativeFrame(JNIEnv*, jobject, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session || session != active || !session->makeCurrent()) return false;
    retro_run(); return true;
}
JNI(jdouble) Java_com_sbro_emucored_core_n64_Mupen64Native_nativeRate(JNIEnv*, jobject, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    return session && session == active ? session->av.timing.fps : 60.0;
}
JNI(void) Java_com_sbro_emucored_core_n64_Mupen64Native_nativeSurface(JNIEnv* env, jobject, jlong handle, jobject surface) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (session && session == active) session->attach(env, surface);
}
JNI(void) Java_com_sbro_emucored_core_n64_Mupen64Native_nativePause(JNIEnv*, jobject, jlong handle, jboolean paused) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (session && session == active) session->audio.pause(paused);
}
JNI(void) Java_com_sbro_emucored_core_n64_Mupen64Native_nativeButton(JNIEnv*, jobject, jlong handle, jint player, jint button, jboolean pressed) {
    auto* session = reinterpret_cast<Session*>(handle);
    constexpr int mapping[] = {0, 1, 9, 8, 3, -1, 2, 13, 10, 11, 4, 5, 6, 7, 12};
    if (!session || session != active || player < 0 || player >= 4 || button < 0 || button >= 15 || mapping[button] < 0) return;
    const uint16_t bit = 1u << mapping[button];
    if (pressed) session->buttons[player] |= bit; else session->buttons[player] &= ~bit;
}
JNI(void) Java_com_sbro_emucored_core_n64_Mupen64Native_nativeAnalog(JNIEnv*, jobject, jlong handle, jint player, jfloat x, jfloat y, jboolean secondary) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session || session != active || player < 0 || player >= 4) return;
    const int index = secondary ? 1 : 0;
    session->axes[player][index][0] = static_cast<int16_t>(std::clamp(x, -1.0f, 1.0f) * 32767);
    session->axes[player][index][1] = static_cast<int16_t>(-std::clamp(y, -1.0f, 1.0f) * 32767);
}
JNI(jboolean) Java_com_sbro_emucored_core_n64_Mupen64Native_nativeStateFile(JNIEnv* env, jobject, jlong handle, jstring path, jboolean save) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session || session != active || !session->makeCurrent()) return false;
    const auto file = utf(env, path);
    if (save) {
        const size_t size = retro_serialize_size();
        if (!size || size > 64 * 1024 * 1024) return false;
        std::vector<uint8_t> state(size);
        return retro_serialize(state.data(), state.size()) && saveEnvelope(file, session->rom_hash, state);
    }
    const auto state = loadEnvelope(file, session->rom_hash);
    return !state.empty() && retro_unserialize(state.data(), state.size());
}
JNI(jboolean) Java_com_sbro_emucored_core_n64_Mupen64Native_nativeOption(JNIEnv* env, jobject, jlong handle, jstring key, jstring value) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session || session != active) return false;
    const auto option = utf(env, key), setting = utf(env, value);
    if (option == "audio_enabled" && (setting == "true" || setting == "false")) {
        session->audio.muted = setting == "false"; return true;
    }
    return false;
}
