// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <aaudio/AAudio.h>
#include <algorithm>
#include <android/native_window_jni.h>
#include <atomic>
#include <cstring>
#include <fstream>
#include <jni.h>
#include <string>
#include <unistd.h>
#include <vector>
struct AndroidOutput {
  ANativeWindow *window = nullptr;
  AAudioStream *audio = nullptr;
  EGLDisplay display = EGL_NO_DISPLAY;
  EGLSurface surface = EGL_NO_SURFACE;
  EGLContext context = EGL_NO_CONTEXT;
  bool displayInitialized = false;
  GLuint program = 0, texture = 0;
  bool muted = false, linear = false;
  static constexpr uint32_t audioCapacity = 16384;
  int audioChannels = 2;
  int audioRate = 48000;
  int16_t audioQueue[audioCapacity * 2]{};
  std::atomic<uint32_t> audioRead{0}, audioWritten{0};
  static aaudio_data_callback_result_t
  audioCallback(AAudioStream *, void *user, void *buffer, int32_t count) {
    auto *s = (AndroidOutput *)user;
    auto *out = (int16_t *)buffer;
    auto r = s->audioRead.load(std::memory_order_relaxed),
         w = s->audioWritten.load(std::memory_order_acquire);
    uint32_t n = std::min<uint32_t>(count, w - r);
    for (uint32_t i = 0; i < n; i++)
      for (int c = 0; c < s->audioChannels; c++)
        out[i * s->audioChannels + c] =
            s->audioQueue[((r + i) % audioCapacity) * s->audioChannels + c];
    std::fill(out + n * s->audioChannels, out + count * s->audioChannels, 0);
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
      eglTerminate(display);
    }
    display = EGL_NO_DISPLAY;
    surface = EGL_NO_SURFACE;
    context = EGL_NO_CONTEXT;
    displayInitialized = false;
    program = texture = 0;
  }
  void detach() {
    resetGraphics();
    if (window)
      ANativeWindow_release(window);
    window = nullptr;
  }
  void attach(JNIEnv *e, jobject s) {
    detach();
    if (s)
      window = ANativeWindow_fromSurface(e, s);
  }
  bool initAudio(int rate, int channels = 2) {
    if (audio) {
      AAudioStream_close(audio);
      audio = nullptr;
    }
    audioChannels = channels;
    audioRate = rate;
    audioRead = 0;
    audioWritten = 0;
    AAudioStreamBuilder *b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK)
      return false;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(b, rate);
    AAudioStreamBuilder_setChannelCount(b, channels);
    AAudioStreamBuilder_setPerformanceMode(b,
                                           AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(b, audioCallback, this);
    auto r = AAudioStreamBuilder_openStream(b, &audio);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) {
      audio = nullptr;
      return false;
    }
    return AAudioStream_requestStart(audio) == AAUDIO_OK;
  }
  void audioWrite(const int16_t *data, int frames) {
    if (!audio)
      return;
    if (AAudioStream_getState(audio) == AAUDIO_STREAM_STATE_DISCONNECTED) {
      initAudio(audioRate, audioChannels);
      return;
    }
    if (muted)
      return;
    auto w = audioWritten.load(std::memory_order_relaxed),
         r = audioRead.load(std::memory_order_acquire);
    uint32_t n = std::min<uint32_t>(frames, audioCapacity - (w - r));
    for (uint32_t i = 0; i < n; i++)
      for (int c = 0; c < audioChannels; c++)
        audioQueue[((w + i) % audioCapacity) * audioChannels + c] =
            data[i * audioChannels + c];
    audioWritten.store(w + n, std::memory_order_release);
  }
  void pause(bool p) {
    if (audio) {
      if (p)
        AAudioStream_requestPause(audio);
      else
        AAudioStream_requestStart(audio);
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
        "precision mediump float;varying vec2 uv;uniform sampler2D image;void "
        "main(){gl_FragColor=vec4(texture2D(image,uv).rgb,1.);}");
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
    glGenTextures(1, &texture);
    return true;
  }
  void present(const uint32_t *pixels, int w, int h) {
    if (!window)
      return;
    if (display == EGL_NO_DISPLAY && !initGL())
      return;
    if (!eglMakeCurrent(display, surface, surface, context)) {
      resetGraphics(); return;
    }
    EGLint sw = 0, sh = 0;
    eglQuerySurface(display, surface, EGL_WIDTH, &sw);
    eglQuerySurface(display, surface, EGL_HEIGHT, &sh);
    glViewport(0, 0, sw, sh);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    int vw = sw, vh = sw * h / w;
    if (vh > sh) {
      vh = sh;
      vw = sh * w / h;
    }
    glViewport((sw - vw) / 2, (sh - vh) / 2, vw, vh);
    glUseProgram(program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER,
                    linear ? GL_LINEAR : GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER,
                    linear ? GL_LINEAR : GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                 pixels);
    const GLfloat p[] = {-1, -1, 1, -1, -1, 1, 1, 1},
                  uv[] = {0, 1, 1, 1, 0, 0, 1, 0};
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, p);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 0, uv);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (!eglSwapBuffers(display, surface)) { resetGraphics(); return; }
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
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
