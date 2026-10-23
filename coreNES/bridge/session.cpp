// SPDX-License-Identifier: GPL-3.0-or-later
#include "android_output.h"
#include "api/NstApiEmulator.hpp"
#include "api/NstApiInput.hpp"
#include "api/NstApiMachine.hpp"
#include "api/NstApiSound.hpp"
#include "api/NstApiUser.hpp"
#include "api/NstApiVideo.hpp"
#include <atomic>
#include <sstream>
using namespace Nes::Api;
static std::atomic<bool> occupied{false};
struct Session {
  AndroidOutput output;
  Emulator emulator;
  Input::Controllers input;
  std::vector<uint32_t> pixels = std::vector<uint32_t>(256 * 240);
  std::string battery;
  uint64_t rom = 0;
  bool loaded = false;
  ~Session() {
    if (loaded) {
      Machine(emulator).Power(false);
      Machine(emulator).Unload();
    }
    User::fileIoCallback.Set(nullptr, nullptr);
    occupied = false;
  }
};
static void NST_CALLBACK files(void *data, User::File &f) {
  auto *s = (Session *)data;
  switch (f.GetAction()) {
  case User::File::LOAD_BATTERY:
  case User::File::LOAD_EEPROM: {
    auto b = readBytes(s->battery);
    if (!b.empty())
      f.SetContent(b.data(), b.size());
    break;
  }
  case User::File::SAVE_BATTERY:
  case User::File::SAVE_EEPROM: {
    const void *p = nullptr;
    Nes::ulong n = 0;
    if (NES_SUCCEEDED(f.GetContent(p, n)) && p && n)
      writeBytes(s->battery, p, n);
    break;
  }
  default:
    break;
  }
}
#define JNI(name) Java_com_sbro_emucored_core_nes_NestopiaSessionEngine_##name
static Session *createSession(const std::string &path,
                              const std::string &battery,
                              const std::string &platform, bool audio = true) {
  if (occupied.exchange(true))
    return 0;
  auto *s = new Session;
  s->battery = battery;
  auto bytes = readBytes(path);
  if (bytes.empty()) {
    delete s;
    return 0;
  }
  s->rom = hashBytes(bytes.data(), bytes.size());
  User::fileIoCallback.Set(files, s);
  std::ifstream file(path, std::ios::binary);
  Machine machine(s->emulator);
  if (NES_FAILED(machine.Load(file, Machine::FAVORED_NES_NTSC))) {
    delete s;
    return 0;
  }
  s->loaded = true;
  Video::RenderState r;
  r.width = 256;
  r.height = 240;
  r.bits.count = 32;
  r.bits.mask.r = 0xff;
  r.bits.mask.g = 0xff00;
  r.bits.mask.b = 0xff0000;
  r.filter = Video::RenderState::FILTER_NONE;
  if (NES_FAILED(Video(s->emulator).SetRenderState(r))) {
    delete s;
    return 0;
  }
  Sound(s->emulator).SetSampleRate(48000);
  Input(s->emulator).ConnectController(0, Input::PAD1);
  Input(s->emulator).ConnectController(1, Input::PAD2);
  if (NES_FAILED(machine.Power(true))) {
    delete s;
    return 0;
  }
  if (audio)
    s->output.initAudio(48000, 1);
  return s;
}
extern "C" JNIEXPORT jlong JNICALL JNI(nativeCreate)(JNIEnv *e, jobject,
                                                     jstring path,
                                                     jstring battery,
                                                     jstring platform) {
  return (jlong)createSession(utf(e, path), utf(e, battery), utf(e, platform));
}
extern "C" JNIEXPORT void JNICALL JNI(nativeDestroy)(JNIEnv *, jobject,
                                                     jlong h) {
  delete (Session *)h;
}
extern "C" JNIEXPORT jboolean JNICALL JNI(nativeFrame)(JNIEnv *, jobject,
                                                       jlong h) {
  auto *s = (Session *)h;
  Video::Output v(s->pixels.data(), 256 * 4);
  int16_t pcm[2048];
  Sound::Output a(pcm, 2048);
  if (NES_FAILED(s->emulator.Execute(&v, &a, &s->input)))
    return false;
  s->output.present(s->pixels.data(), 256, 240);
  s->output.audioWrite(pcm, a.length[0]);
  return true;
}
extern "C" JNIEXPORT jdouble JNICALL JNI(nativeRate)(JNIEnv *, jobject,
                                                     jlong h) {
  return Machine(((Session *)h)->emulator).GetMode() == Machine::PAL
             ? 50.0069789
             : 60.0988139;
}
extern "C" JNIEXPORT void JNICALL JNI(nativeSurface)(JNIEnv *e, jobject,
                                                     jlong h, jobject surface) {
  ((Session *)h)->output.attach(e, surface);
}
extern "C" JNIEXPORT void JNICALL JNI(nativePause)(JNIEnv *, jobject, jlong h,
                                                   jboolean p) {
  ((Session *)h)->output.pause(p);
}
extern "C" JNIEXPORT void JNICALL JNI(nativeButton)(JNIEnv *, jobject, jlong h,
                                                    jint player, jint b,
                                                    jboolean p) {
  if (player < 0 || player > 1)
    return;
  unsigned key = 0;
  switch (b) {
  case 0:
    key = 1;
    break;
  case 1:
    key = 2;
    break;
  case 4:
    key = 8;
    break;
  case 5:
    key = 4;
    break;
  case 10:
    key = 16;
    break;
  case 11:
    key = 32;
    break;
  case 12:
    key = 64;
    break;
  case 13:
    key = 128;
    break;
  }
  auto &buttons = ((Session *)h)->input.pad[player].buttons;
  if (p)
    buttons |= key;
  else
    buttons &= ~key;
}
static std::vector<uint8_t> state(Session *s) {
  std::ostringstream out(std::ios::binary);
  if (NES_FAILED(Machine(s->emulator).SaveState(out, Machine::NO_COMPRESSION)))
    return {};
  auto b = out.str();
  return {b.begin(), b.end()};
}
static bool restore(Session *s, const std::vector<uint8_t> &b) {
  std::istringstream in(std::string(b.begin(), b.end()), std::ios::binary);
  return NES_SUCCEEDED(Machine(s->emulator).LoadState(in));
}
extern "C" JNIEXPORT jboolean JNICALL JNI(nativeStateFile)(JNIEnv *e, jobject,
                                                           jlong h,
                                                           jstring path,
                                                           jboolean save) {
  auto *s = (Session *)h;
  if (save) {
    auto b = state(s);
    return !b.empty() && saveEnvelope(utf(e, path), s->rom, b);
  }
  auto b = loadEnvelope(utf(e, path), s->rom);
  if (b.empty())
    return false;
  auto previous = state(s);
  if (restore(s, b))
    return true;
  if (!previous.empty())
    restore(s, previous);
  return false;
}
extern "C" JNIEXPORT jboolean JNICALL JNI(nativeOption)(JNIEnv *e, jobject,
                                                        jlong h, jstring key,
                                                        jstring value) {
  auto *s = (Session *)h;
  auto k = utf(e, key), v = utf(e, value);
  if (k == "audio_enabled" && (v == "true" || v == "false")) {
    s->output.muted = v == "false";
    return true;
  }
  if (k == "video_filter" && (v == "nearest" || v == "linear")) {
    s->output.linear = v == "linear";
    return true;
  }
  if (k == "unlimited_sprites" && (v == "true" || v == "false")) {
    Video(s->emulator).EnableUnlimSprites(v == "true");
    return true;
  }
  return false;
}
