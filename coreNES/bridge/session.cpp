// SPDX-License-Identifier: GPL-3.0-or-later
#include "android_output.h"
#include "api/NstApiEmulator.hpp"
#include "api/NstApiCheats.hpp"
#include "typed_cheats.h"
#include "api/NstApiFds.hpp"
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
  Session() { output.defaultAspect = 4.0 / 3; }
  Emulator emulator;
  Input::Controllers input;
  std::vector<uint32_t> pixels = std::vector<uint32_t>(256 * 240);
  std::string battery;
  std::string diskPatch;
  bool diskPatchInvalid = false;
  unsigned selectedDiskSide = 0;
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
  case User::File::LOAD_FDS: {
    std::ifstream patch(s->diskPatch, std::ios::binary);
    if (patch && NES_FAILED(f.SetPatchContent(patch))) s->diskPatchInvalid = true;
    else if (!patch && access(s->diskPatch.c_str(), F_OK) == 0) s->diskPatchInvalid = true;
    break;
  }
  case User::File::SAVE_FDS: {
    std::ostringstream patch(std::ios::binary);
    if (NES_SUCCEEDED(f.GetPatchContent(User::File::PATCH_UPS, patch))) {
      auto bytes = patch.str();
      if (!bytes.empty()) writeBytes(s->diskPatch, bytes.data(), bytes.size());
    }
    break;
  }
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
static bool replaceCheats(Session* s,const std::vector<TypedCheatInput>& inputs) {
  if(!s || !s->loaded)return false;
  std::vector<Cheats::Code> staged;
  for(const auto& block:inputs)for(const auto& line:block.lines) {
    Cheats::Code code;
    if(block.format=="nes-game-genie") {
      if(!cheatLineMatches(line,"[APZLGITYEOXUKSVN]{6}([APZLGITYEOXUKSVN]{2})?") || NES_FAILED(Cheats::GameGenieDecode(line.c_str(),code)))return false;
    } else if(block.format=="nes-raw-address-value") {
      if(!cheatLineMatches(line,"[0-9A-F]{4}:[0-9A-F]{2}(:[0-9A-F]{2})?"))return false;
      code.address=std::stoul(line.substr(0,4),nullptr,16);code.value=std::stoul(line.substr(5,2),nullptr,16);
      code.useCompare=line.size()==10; if(code.useCompare)code.compare=std::stoul(line.substr(8,2),nullptr,16);
    } else return false;
    staged.push_back(code);
  }
  Cheats api(s->emulator); std::vector<Cheats::Code> previous;
  for(unsigned i=0;i<api.NumCodes();++i){Cheats::Code c;if(NES_FAILED(api.GetCode(i,c)))return false;previous.push_back(c);}
  if(NES_FAILED(api.ClearCodes()))return false;
  for(const auto& code:staged)if(NES_FAILED(api.SetCode(code))) {
    api.ClearCodes();for(const auto& old:previous)api.SetCode(old);return false;
  }
  return true;
}
extern "C" JNIEXPORT jboolean JNICALL JNI(nativeCheats)(JNIEnv* env,jobject,jlong handle,jobjectArray formats,jobjectArray codes) {
  std::vector<TypedCheatInput> inputs; return typedCheatInputs(env,formats,codes,inputs) && replaceCheats(reinterpret_cast<Session*>(handle),inputs);
}
static Session *createSession(const std::string &path,
                              const std::string &battery,
                              const std::string &platform, bool audio = true,
                              const std::string &firmware = {}) {
  if (occupied.exchange(true))
    return 0;
  auto *s = new Session;
  s->battery = battery;
  s->diskPatch = battery + ".fds.ups";
  auto bytes = readBytes(path);
  if (bytes.empty()) {
    delete s;
    return 0;
  }
  s->rom = hashBytes(bytes.data(), bytes.size());
  User::fileIoCallback.Set(files, s);
  Fds drive(s->emulator);
  drive.SetBIOS(nullptr);
  if (!firmware.empty()) {
    auto biosBytes = readBytes(firmware);
    if (biosBytes.size() == 8192) {
      std::istringstream bios(std::string(reinterpret_cast<const char *>(biosBytes.data()), biosBytes.size()),
                              std::ios::binary);
      if (NES_FAILED(drive.SetBIOS(&bios))) { delete s; return 0; }
    }
  }
  std::ifstream file(path, std::ios::binary);
  Machine machine(s->emulator);
  if (NES_FAILED(machine.Load(file, Machine::FAVORED_NES_NTSC)) || s->diskPatchInvalid) {
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
  if (machine.Is(Machine::DISK) && NES_FAILED(drive.InsertDisk(0, 0))) {
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
                                                     jstring platform,
                                                     jstring firmware) {
  return (jlong)createSession(utf(e, path), utf(e, battery), utf(e, platform), true, utf(e, firmware));
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
static bool applyDiskOption(Session *s, const std::string &key, const std::string &value) {
  if (!Machine(s->emulator).Is(Machine::DISK)) return false;
  Fds drive(s->emulator);
  if (key == "fds_ejected" && (value == "true" || value == "false")) {
    if (value == "true") {
      if (drive.IsAnyDiskInserted())
        s->selectedDiskSide = drive.GetCurrentDisk() * 2 + drive.GetCurrentDiskSide();
      return NES_SUCCEEDED(drive.EjectDisk());
    }
    return NES_SUCCEEDED(drive.InsertDisk(s->selectedDiskSide / 2, s->selectedDiskSide % 2));
  }
  if (key == "fds_disk_side" && !value.empty() && value.size() <= 3 &&
      value.find_first_not_of("0123456789") == std::string::npos) {
    unsigned side = std::stoul(value);
    if (side >= drive.GetNumSides()) return false;
    const bool inserted = drive.IsAnyDiskInserted();
    if (inserted && NES_FAILED(drive.EjectDisk())) return false;
    if (inserted && NES_FAILED(drive.InsertDisk(side / 2, side % 2))) return false;
    s->selectedDiskSide = side;
    return true;
  }
  return false;
}
extern "C" JNIEXPORT jboolean JNICALL JNI(nativeOption)(JNIEnv *e, jobject,
                                                        jlong h, jstring key,
                                                        jstring value) {
  auto *s = (Session *)h;
  auto k = utf(e, key), v = utf(e, value);
  if (k == "aspect_ratio") return s->output.setAspectOption(v);
  if (k == "fds_disk_side" || k == "fds_ejected") return applyDiskOption(s, k, v);
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
