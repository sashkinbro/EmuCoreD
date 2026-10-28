// SPDX-License-Identifier: GPL-3.0-or-later
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fstream>
#include <map>
#include <string>
#include <vector>
#include "libretro.h"

namespace {
retro_hw_render_callback hardware{};
std::map<std::string, std::string> options;
unsigned frames = 0, nonblack = 0;
const char* system_dir = "/data/local/tmp/codex-n64-test";
void log(retro_log_level, const char* format, ...) {
    va_list args; va_start(args, format); std::vfprintf(stderr, format, args); va_end(args);
}
uintptr_t framebuffer() { return 0; }
retro_proc_address_t proc(const char* name) {
    return reinterpret_cast<retro_proc_address_t>(eglGetProcAddress(name));
}
bool environment(unsigned command, void* data) {
    switch (command) {
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *static_cast<const char**>(data) = system_dir; return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        static_cast<retro_log_callback*>(data)->log = log; return true;
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
            options[variables->key] = value.substr(0, value.find('|'));
        }
        return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        auto* variable = static_cast<retro_variable*>(data);
        const auto found = options.find(variable->key);
        if (found == options.end()) return false;
        variable->value = found->second.c_str(); return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *static_cast<bool*>(data) = false; return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        *static_cast<bool*>(data) = true; return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: return true;
    case RETRO_ENVIRONMENT_SET_HW_RENDER: {
        auto* callback = static_cast<retro_hw_render_callback*>(data);
        if (callback->context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
            callback->context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION) return false;
        callback->get_current_framebuffer = framebuffer;
        callback->get_proc_address = proc;
        hardware = *callback; return true;
    }
    default: return false;
    }
}
void video(const void* data, unsigned width, unsigned height, size_t) {
    if (data != RETRO_HW_FRAME_BUFFER_VALID || !width || !height) return;
    ++frames;
    if (frames % 60 == 0) {
        std::vector<unsigned char> pixels(640 * 480 * 4);
        glReadPixels(0, 0, 640, 480, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
        for (size_t i = 0; i < pixels.size(); i += 4) {
            if (pixels[i] || pixels[i+1] || pixels[i+2]) { ++nonblack; break; }
        }
    }
}
size_t audio(const int16_t*, size_t count) { return count; }
void poll() {}
int16_t input(unsigned, unsigned, unsigned, unsigned) { return 0; }
}

int main(int argc, char** argv) {
    if (argc != 3) return 1;
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) return 2;
    const EGLint attributes[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE,
        EGL_OPENGL_ES3_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_DEPTH_SIZE, 24, EGL_NONE};
    EGLConfig config; EGLint count;
    if (!eglChooseConfig(display, attributes, &config, 1, &count) || count != 1) return 3;
    const EGLint pbuffer[] = {EGL_WIDTH, 640, EGL_HEIGHT, 480, EGL_NONE};
    const EGLint context_attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLSurface surface = eglCreatePbufferSurface(display, config, pbuffer);
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attributes);
    if (surface == EGL_NO_SURFACE || context == EGL_NO_CONTEXT ||
        !eglMakeCurrent(display, surface, surface, context)) return 4;
    void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) { std::fprintf(stderr, "%s\n", dlerror()); return 5; }
#define LOAD(name) auto name = reinterpret_cast<decltype(&::name)>(dlsym(library, #name)); if (!name) return 6
    LOAD(retro_set_environment); LOAD(retro_set_video_refresh); LOAD(retro_set_audio_sample_batch);
    LOAD(retro_set_input_poll); LOAD(retro_set_input_state); LOAD(retro_init); LOAD(retro_deinit);
    LOAD(retro_load_game); LOAD(retro_unload_game); LOAD(retro_run);
    LOAD(retro_serialize_size); LOAD(retro_serialize); LOAD(retro_unserialize);
    std::ifstream input_file(argv[2], std::ios::binary | std::ios::ate);
    if (!input_file) return 7;
    const auto length = input_file.tellg();
    if (length <= 0 || length > 64 * 1024 * 1024) return 8;
    std::vector<char> bytes(static_cast<size_t>(length)); input_file.seekg(0);
    if (!input_file.read(bytes.data(), length)) return 9;
    retro_set_environment(environment); retro_set_video_refresh(video); retro_set_audio_sample_batch(audio);
    retro_set_input_poll(poll); retro_set_input_state(input); retro_init();
    const retro_game_info game{argv[2], bytes.data(), bytes.size(), nullptr};
    if (!retro_load_game(&game) || !hardware.context_reset) return 10;
    hardware.context_reset();
    for (unsigned i = 0; i < 360; ++i) retro_run();
    const size_t state_size = retro_serialize_size();
    if (!state_size || state_size > 64 * 1024 * 1024) return 12;
    std::vector<unsigned char> state(state_size);
    if (!retro_serialize(state.data(), state.size()) || !retro_unserialize(state.data(), state.size())) return 13;
    for (unsigned i = 0; i < 30; ++i) retro_run();
    if (hardware.context_destroy) hardware.context_destroy();
    retro_unload_game(); retro_deinit(); dlclose(library);
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display, context); eglDestroySurface(display, surface); eglTerminate(display);
    std::printf("frames=%u nonblack_samples=%u\n", frames, nonblack);
    return frames >= 60 && nonblack ? 0 : 11;
}
