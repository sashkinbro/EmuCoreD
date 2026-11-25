// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <aaudio/AAudio.h>
#include <algorithm>
#include <android/native_window_jni.h>
#include <atomic>
#include <cstring>
#include <dlfcn.h>
#include <fstream>
#include <jni.h>
#include <string>
#include <unistd.h>
#include <vector>
#include "display_geometry.h"
struct AndroidOutput {
  ANativeWindow *window = nullptr;
  AAudioStream *audio = nullptr;
  EGLDisplay display = EGL_NO_DISPLAY;
  EGLSurface surface = EGL_NO_SURFACE;
  EGLContext context = EGL_NO_CONTEXT;
  bool displayInitialized = false;
  GLuint program = 0, texture = 0;
  GLint uBgra = -1;
  int texWidth = 0, texHeight = 0;
  int lastLinear = -1;
  bool muted = false, linear = false;
  double defaultAspect = 0, displayAspect = 0;
  bool setAspectOption(const std::string& value) {
    if (value == "auto") displayAspect = 0;
    else if (value == "pixel") displayAspect = -1;
    else if (value == "4:3") displayAspect = 4.0 / 3;
    else if (value == "16:9") displayAspect = 16.0 / 9;
    else return false;
    return true;
  }
  static constexpr uint32_t audioCapacity = 16384;
  int audioChannels = 2;
  int audioRate = 48000;
  int inputSampleRate = 0;
  double resamplePhase = 0.0;
  int16_t prevSample[2]{0, 0};
  bool hasPrevSample = false;
  int16_t audioQueue[audioCapacity * 2]{};
  int16_t audioLastSample[2]{0, 0};
  std::atomic<uint32_t> audioRead{0}, audioWritten{0};
  std::atomic<uint32_t> audioFlushGen{0}, audioDiscardUntil{0};
  uint32_t audioFlushSeen = 0;
  static aaudio_data_callback_result_t
  audioCallback(AAudioStream *, void *user, void *buffer, int32_t count) {
    auto *s = (AndroidOutput *)user;
    auto *out = (int16_t *)buffer;
    const int channels = std::clamp(s->audioChannels, 1, 2);
    const uint32_t gen = s->audioFlushGen.load(std::memory_order_acquire);
    uint32_t r = s->audioRead.load(std::memory_order_relaxed);
    const uint32_t w = s->audioWritten.load(std::memory_order_acquire);
    if (gen != s->audioFlushSeen) {
      s->audioFlushSeen = gen;
      s->audioLastSample[0] = 0;
      s->audioLastSample[1] = 0;
      const uint32_t discardUntil = s->audioDiscardUntil.load(std::memory_order_acquire);
      const uint32_t toDiscard = discardUntil - r;
      const uint32_t avail = w - r;
      r = (toDiscard <= avail) ? (r + toDiscard) : w;
      s->audioRead.store(r, std::memory_order_release);
    }
    uint32_t n = std::min<uint32_t>(count, w - r);
    for (uint32_t i = 0; i < n; i++) {
      for (int c = 0; c < channels; c++) {
        const int16_t sample = s->audioQueue[((r + i) % audioCapacity) * channels + c];
        out[i * channels + c] = sample;
        s->audioLastSample[c] = sample;
      }
    }
    // Smooth exponential decay on underrun to avoid audible clicks/pops.
    for (uint32_t i = n; i < static_cast<uint32_t>(count); i++) {
      for (int c = 0; c < channels; c++) {
        s->audioLastSample[c] = static_cast<int16_t>((s->audioLastSample[c] * 240) / 256);
        out[i * channels + c] = s->audioLastSample[c];
      }
    }
    s->audioRead.store(r + n, std::memory_order_release);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
  }

  ~AndroidOutput() {
    detach();
    if (audio)
      AAudioStream_close(audio);
  }
  void resetGraphics() {
    if (displayInitialized) {
      eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
      if (surface != EGL_NO_SURFACE)
        eglDestroySurface(display, surface);
      if (context != EGL_NO_CONTEXT)
        eglDestroyContext(display, context);
    }
    display = EGL_NO_DISPLAY;
    surface = EGL_NO_SURFACE;
    context = EGL_NO_CONTEXT;
    displayInitialized = false;
    program = texture = 0;
    uBgra = -1;
    texWidth = texHeight = 0;
    lastLinear = -1;
  }
  void detach() {
    resetGraphics();
    if (window)
      ANativeWindow_release(window);
    window = nullptr;
  }
  void attach(JNIEnv *e, jobject s) {
    ANativeWindow *next = s ? ANativeWindow_fromSurface(e, s) : nullptr;
    if (next == window) {
      if (next)
        ANativeWindow_release(next);
      return;
    }
    detach();
    window = next;
  }
  void setInputSampleRate(int rate) {
    if (rate > 0 && rate != inputSampleRate) {
      inputSampleRate = rate;
    }
  }
  void flushAudio() {
    const uint32_t w = audioWritten.load(std::memory_order_acquire);
    audioDiscardUntil.store(w, std::memory_order_release);
    audioFlushGen.fetch_add(1, std::memory_order_release);
    resamplePhase = 0.0;
    hasPrevSample = false;
    prevSample[0] = 0;
    prevSample[1] = 0;
    if (audio) {
      const aaudio_stream_state_t state = AAudioStream_getState(audio);
      if (state == AAUDIO_STREAM_STATE_PAUSED || state == AAUDIO_STREAM_STATE_STOPPED) {
        AAudioStream_requestFlush(audio);
      }
    }
  }
  bool initAudio(int rate, int channels = 2) {
    if (audio) {
      AAudioStream_close(audio);
      audio = nullptr;
    }
    audioChannels = std::clamp(channels, 1, 2);
    audioRate = rate > 0 ? rate : 48000;
    if (inputSampleRate <= 0)
      inputSampleRate = audioRate;
    audioRead.store(0, std::memory_order_relaxed);
    audioWritten.store(0, std::memory_order_relaxed);
    audioFlushGen.store(0, std::memory_order_relaxed);
    audioDiscardUntil.store(0, std::memory_order_relaxed);
    audioFlushSeen = 0;
    audioLastSample[0] = 0;
    audioLastSample[1] = 0;
    resamplePhase = 0.0;
    hasPrevSample = false;
    prevSample[0] = 0;
    prevSample[1] = 0;
    AAudioStreamBuilder *b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK)
      return false;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(b, audioRate);
    AAudioStreamBuilder_setChannelCount(b, audioChannels);
    if (auto *setUsage = reinterpret_cast<void (*)(AAudioStreamBuilder *, int32_t)>(
            dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setUsage"))) {
      setUsage(b, 14 /* AAUDIO_USAGE_GAME */);
    }
    AAudioStreamBuilder_setPerformanceMode(b,
                                           AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(b, audioCallback, this);
    auto r = AAudioStreamBuilder_openStream(b, &audio);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK || !audio) {
      audio = nullptr;
      return false;
    }
    const int32_t actualRate = AAudioStream_getSampleRate(audio);
    if (actualRate > 0)
      audioRate = actualRate;
    const int32_t burst = AAudioStream_getFramesPerBurst(audio);
    const int32_t cap = AAudioStream_getBufferCapacityInFrames(audio);
    if (burst > 0 && cap > 0) {
      const int32_t target = std::max(burst * 4, audioRate / 30);
      AAudioStream_setBufferSizeInFrames(audio, std::min(cap, target));
    }
    const uint32_t prefill = std::min<uint32_t>(audioRate / 50, audioCapacity / 4);
    for (uint32_t i = 0; i < prefill * audioChannels; i++)
      audioQueue[i] = 0;
    audioWritten.store(prefill, std::memory_order_release);
    if (AAudioStream_requestStart(audio) != AAUDIO_OK) {
      AAudioStream_close(audio);
      audio = nullptr;
      return false;
    }
    return true;
  }
  void audioWrite(const int16_t *data, int frames) {
    if (!data || frames <= 0)
      return;
    if (!audio) {
      if (!initAudio(audioRate, audioChannels)) return;
    }
    if (AAudioStream_getState(audio) == AAUDIO_STREAM_STATE_DISCONNECTED) {
      if (!initAudio(audioRate, audioChannels)) return;
    }
    if (muted)
      return;
    uint32_t w = audioWritten.load(std::memory_order_relaxed);
    const uint32_t r = audioRead.load(std::memory_order_acquire);
    const uint32_t queued = w - r;
    const int channels = std::clamp(audioChannels, 1, 2);
    const int inRate = inputSampleRate > 0 ? inputSampleRate : audioRate;
    const int outRate = audioRate > 0 ? audioRate : 48000;
    const uint32_t maxQueue = std::min<uint32_t>(
        audioCapacity - 64, static_cast<uint32_t>((outRate * 9) / 100));
    if (queued >= maxQueue || queued >= audioCapacity - 1) {
      for (int c = 0; c < channels; c++)
        prevSample[c] = data[(frames - 1) * channels + c];
      hasPrevSample = true;
      resamplePhase = 0.0;
      return;
    }
    if (!hasPrevSample) {
      for (int c = 0; c < channels; c++)
        prevSample[c] = data[c];
      hasPrevSample = true;
    }
    const double targetFill = outRate * 0.045;
    const double fillError =
        (static_cast<double>(queued) - targetFill) / std::max<double>(1.0, outRate * 0.1);
    const double skew = std::clamp(1.0 + fillError * 0.005, 0.995, 1.005);
    const double step = (static_cast<double>(inRate) / static_cast<double>(outRate)) * skew;
    const uint32_t room = std::min(audioCapacity - queued, maxQueue - queued);
    uint32_t outCount = 0;
    while (resamplePhase < static_cast<double>(frames) && outCount < room) {
      const int idx = std::clamp(static_cast<int>(resamplePhase), 0, frames - 1);
      const double frac = resamplePhase - static_cast<double>(idx);
      for (int c = 0; c < channels; c++) {
        const int16_t s0 = (idx == 0) ? prevSample[c] : data[(idx - 1) * channels + c];
        const int16_t s1 = data[idx * channels + c];
        const double interp = static_cast<double>(s0) +
                              (static_cast<double>(s1) - static_cast<double>(s0)) * frac;
        const int clamped = std::clamp(static_cast<int>(interp + (interp >= 0 ? 0.5 : -0.5)), -32768, 32767);
        audioQueue[((w + outCount) % audioCapacity) * channels + c] = static_cast<int16_t>(clamped);
      }
      outCount++;
      resamplePhase += step;
    }
    for (int c = 0; c < channels; c++)
      prevSample[c] = data[(frames - 1) * channels + c];
    if (outCount >= room) {
      resamplePhase = 0.0;
    } else {
      resamplePhase -= static_cast<double>(frames);
      if (resamplePhase < 0.0) resamplePhase = 0.0;
    }
    if (outCount > 0)
      audioWritten.store(w + outCount, std::memory_order_release);
  }
  void pause(bool p) {
    if (!audio) return;
    const aaudio_stream_state_t state = AAudioStream_getState(audio);
    if (p) {
      if (state == AAUDIO_STREAM_STATE_STARTED || state == AAUDIO_STREAM_STATE_STARTING) {
        if (AAudioStream_requestPause(audio) == AAUDIO_OK) {
          aaudio_stream_state_t nextState = state;
          AAudioStream_waitForStateChange(audio, state, &nextState, 50000000LL);
        }
      }
      flushAudio();
    } else {
      if (state != AAUDIO_STREAM_STATE_STARTED && state != AAUDIO_STREAM_STATE_STARTING &&
          state != AAUDIO_STREAM_STATE_DISCONNECTED && state != AAUDIO_STREAM_STATE_CLOSED) {
        AAudioStream_requestStart(audio);
      }
    }
  }
  GLuint shader(GLenum type, const char *source) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &source, nullptr);
    glCompileShader(s);
    GLint compiled = GL_FALSE;
    glGetShaderiv(s, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) { glDeleteShader(s); return 0; }
    return s;
  }
  bool initGL() {
    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    auto fail = [this] { resetGraphics(); return false; };
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr))
      return fail();
    displayInitialized = true;
    EGLint attrs[] = {EGL_RENDERABLE_TYPE,
                      EGL_OPENGL_ES2_BIT,
                      EGL_SURFACE_TYPE,
                      EGL_WINDOW_BIT,
                      EGL_RED_SIZE,
                      8,
                      EGL_GREEN_SIZE,
                      8,
                      EGL_BLUE_SIZE,
                      8,
                      EGL_NONE};
    EGLConfig cfg;
    EGLint n;
    if (!eglChooseConfig(display, attrs, &cfg, 1, &n) || !n)
      return fail();
    EGLint format;
    if (!eglGetConfigAttrib(display, cfg, EGL_NATIVE_VISUAL_ID, &format) ||
        ANativeWindow_setBuffersGeometry(window, 0, 0, format) != 0) return fail();
    EGLint ctx[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    context = eglCreateContext(display, cfg, EGL_NO_CONTEXT, ctx);
    if (context == EGL_NO_CONTEXT) return fail();
    surface = eglCreateWindowSurface(display, cfg, window, nullptr);
    if (surface == EGL_NO_SURFACE || !eglMakeCurrent(display, surface, surface, context))
      return fail();
    eglSwapInterval(display, 0);
    GLuint v = shader(GL_VERTEX_SHADER,
                      "attribute vec2 p;attribute vec2 t;varying vec2 uv;void "
                      "main(){gl_Position=vec4(p,0.,1.);uv=t;}");
    GLuint f = shader(
        GL_FRAGMENT_SHADER,
        "precision mediump float;varying vec2 uv;uniform sampler2D image;uniform int u_bgra;void "
        "main(){vec4 c=texture2D(image,uv);gl_FragColor=vec4(u_bgra!=0?c.bgr:c.rgb,1.);}");
    if (!v || !f) { if(v) glDeleteShader(v); if(f) glDeleteShader(f); return fail(); }
    program = glCreateProgram();
    glAttachShader(program, v);
    glAttachShader(program, f);
    glBindAttribLocation(program, 0, "p");
    glBindAttribLocation(program, 1, "t");
    glLinkProgram(program);
    glDeleteShader(v);
    glDeleteShader(f);
    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) return fail();
    uBgra = glGetUniformLocation(program, "u_bgra");
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return true;
  }
  void present(const uint32_t *pixels, int w, int h, bool bgra = false) {
    if (!window || !pixels || w <= 0 || h <= 0)
      return;
    if (display == EGL_NO_DISPLAY && !initGL())
      return;
    if (eglGetCurrentContext() != context || eglGetCurrentSurface(EGL_DRAW) != surface) {
      if (!eglMakeCurrent(display, surface, surface, context)) {
        resetGraphics();
        return;
      }
    }
    EGLint sw = 0, sh = 0;
    eglQuerySurface(display, surface, EGL_WIDTH, &sw);
    eglQuerySurface(display, surface, EGL_HEIGHT, &sh);
    if (sw <= 0 || sh <= 0)
      return;
    glViewport(0, 0, sw, sh);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    const double ratio = displayAspect > 0 ? displayAspect :
        (displayAspect == 0 && defaultAspect > 0 ? defaultAspect : double(w) / h);
    const auto viewport = displayViewport(sw, sh, ratio);
    if (!viewport.width || !viewport.height) return;
    glViewport(viewport.x, viewport.y, viewport.width, viewport.height);
    glUseProgram(program);
    if (uBgra >= 0) glUniform1i(uBgra, bgra ? 1 : 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);
    const int wantLinear = linear ? 1 : 0;
    if (lastLinear != wantLinear) {
      const GLint filter = linear ? GL_LINEAR : GL_NEAREST;
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
      lastLinear = wantLinear;
    }
    if (texWidth != w || texHeight != h) {
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
      texWidth = w;
      texHeight = h;
    } else {
      glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    }
    const GLfloat p[] = {-1, -1, 1, -1, -1, 1, 1, 1},
                  uv[] = {0, 1, 1, 1, 0, 0, 1, 0};
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, p);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 0, uv);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (!eglSwapBuffers(display, surface)) { resetGraphics(); return; }
  }
};
static std::string utf(JNIEnv *e, jstring s) {
  if (!s)
    return {};
  const char *p = e->GetStringUTFChars(s, nullptr);
  std::string r(p);
  e->ReleaseStringUTFChars(s, p);
  return r;
}
static std::vector<uint8_t> readBytes(const std::string &path) {
  std::ifstream f(path, std::ios::binary | std::ios::ate);
  if (!f)
    return {};
  auto n = f.tellg();
  if (n <= 0 || n > 64 * 1024 * 1024)
    return {};
  std::vector<uint8_t> b((size_t)n);
  f.seekg(0);
  if (!f.read((char *)b.data(), n))
    return {};
  return b;
}
static bool writeBytes(const std::string &path, const void *bytes,
                       size_t size) {
  std::string tmp = path + ".tmp";
  FILE *f = fopen(tmp.c_str(), "wb");
  if (!f)
    return false;
  bool ok = fwrite(bytes, 1, size, f) == size && fflush(f) == 0 &&
            fsync(fileno(f)) == 0;
  ok = fclose(f) == 0 && ok;
  if (ok)
    ok = rename(tmp.c_str(), path.c_str()) == 0;
  if (!ok)
    remove(tmp.c_str());
  return ok;
}
static uint64_t hashBytes(const uint8_t *p, size_t n) {
  uint64_t h = 1469598103934665603ULL;
  while (n--) {
    h ^= *p++;
    h *= 1099511628211ULL;
  }
  return h;
}
static bool writeBytesIfChanged(const std::string &path, const void *bytes,
                                size_t size, uint64_t &lastHash,
                                size_t &lastSize) {
  if (!bytes || size == 0)
    return true;
  const uint64_t h = hashBytes(static_cast<const uint8_t *>(bytes), size);
  if (size == lastSize && h == lastHash)
    return true;
  if (!writeBytes(path, bytes, size))
    return false;
  lastHash = h;
  lastSize = size;
  return true;
}
struct StateHeader {
  uint64_t magic = 0x3145544154534345ULL, rom = 0, checksum = 0, size = 0;
};
static bool saveEnvelope(const std::string &p, uint64_t rom,
                         const std::vector<uint8_t> &b) {
  StateHeader h;
  h.rom = rom;
  h.size = b.size();
  h.checksum = hashBytes(b.data(), b.size());
  std::vector<uint8_t> all(sizeof(h) + b.size());
  memcpy(all.data(), &h, sizeof(h));
  memcpy(all.data() + sizeof(h), b.data(), b.size());
  return writeBytes(p, all.data(), all.size());
}
static std::vector<uint8_t> loadEnvelope(const std::string &p, uint64_t rom) {
  auto b = readBytes(p);
  if (b.size() < sizeof(StateHeader))
    return {};
  StateHeader h;
  memcpy(&h, b.data(), sizeof(h));
  if (h.magic != StateHeader{}.magic || h.rom != rom ||
      h.size != b.size() - sizeof(h) ||
      h.checksum != hashBytes(b.data() + sizeof(h), h.size))
    return {};
  return {b.begin() + sizeof(h), b.end()};
}
