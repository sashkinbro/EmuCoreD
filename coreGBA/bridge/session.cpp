// SPDX-License-Identifier: GPL-3.0-or-later
#include "android_output.h"
#include <mgba-util/audio-buffer.h>
#include <mgba-util/vfs.h>
#include <mgba/core/config.h>
#include <mgba/core/core.h>
#include <mgba/core/cheats.h>
#include <mgba/internal/gba/cheats.h>
#include "typed_cheats.h"
#include <mgba/core/log.h>
#include <mgba/gba/core.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/savedata.h>
#include <charconv>
static void silentLog(mLogger *, int, mLogLevel, const char *, va_list) {}
static mLogger quietLogger{silentLog, nullptr};
struct Session {
  AndroidOutput output;
  mCore *core = nullptr;
  std::vector<uint32_t> pixels = std::vector<uint32_t>(240 * 160);
  std::string battery;
  uint64_t rom = 0;
  VFile *saveFile = nullptr;
  bool batteryReady = false;
  ~Session() {
    if (core) {
      if (batteryReady && saveFile) {
        GBASavedataRTCWrite(&((GBA *)core->board)->memory.savedata);
        auto n = saveFile->size(saveFile);
        if (n > 0) {
          std::vector<uint8_t> data(n);
          saveFile->seek(saveFile, 0, SEEK_SET);
          if (saveFile->read(saveFile, data.data(), n) == n)
            writeBytes(battery, data.data(), n);
        }
      }
      mCoreConfigDeinit(&core->config);
      core->deinit(core);
    }
  }
};
#ifdef EMUCORED_SESSION_CONTRACT
// Fault injection is absent from the production shared library.
static bool (*allowBatteryInitialization)(Session *) = nullptr;
#endif
#define JNI(name) Java_com_sbro_emucored_core_gba_MgbaSessionEngine_##name
static bool replaceCheats(Session* s,const std::vector<TypedCheatInput>& codes) {
  if(!s || !s->core)return false;
  auto* device=s->core->cheatDevice(s->core); if(!device)return false;
  std::vector<mCheatSet*> staged;
  auto discard=[&] {for(auto* set:staged)mCheatSetDeinit(set);};
  for(const auto& block:codes) {
    int type=block.format=="gba-codebreaker"?GBA_CHEAT_CODEBREAKER:block.format=="gba-action-replay"?GBA_CHEAT_PRO_ACTION_REPLAY:block.format=="gba-gameshark"?GBA_CHEAT_GAMESHARK:-1;
    if(type<0){discard();return false;}
    auto* set=device->createSet(device,"EmuCoreD"); if(!set){discard();return false;} staged.push_back(set);
    const char* pattern=type==GBA_CHEAT_CODEBREAKER?"[0-9A-F]{8} [0-9A-F]{4}":"[0-9A-F]{8} [0-9A-F]{8}";
    for(const auto& line:block.lines)if(!cheatLineMatches(line,pattern) || !mCheatAddLine(set,line.c_str(),type)){discard();return false;}
    const auto* parsed=reinterpret_cast<GBACheatSet*>(set);
    if(parsed->incompleteCheat!=size_t(-1) || parsed->incompletePatch || parsed->remainingAddresses){discard();return false;}
  }
  while(mCheatSetsSize(&device->cheats)) {
    auto* old=*mCheatSetsGetPointer(&device->cheats,0); old->enabled=false;
    mCheatRefresh(device,old); mCheatRemoveSet(device,old); mCheatSetDeinit(old);
  }
  for(auto* set:staged){set->enabled=true;mCheatAddSet(device,set);}
  return true;
}
extern "C" JNIEXPORT jboolean JNICALL JNI(nativeCheats)(JNIEnv* env,jobject,jlong handle,jobjectArray formats,jobjectArray codes) {
  std::vector<TypedCheatInput> inputs; return typedCheatInputs(env,formats,codes,inputs) && replaceCheats(reinterpret_cast<Session*>(handle),inputs);
}
static Session *createSession(const std::string &path,
                              const std::string &battery,
                              const std::string &platform, bool audio = true) {
  mLogSetThreadLogger(&quietLogger);
  auto bytes = readBytes(path);
  if (bytes.empty())
    return 0;
  auto *s = new Session;
  s->battery = battery;
  s->rom = hashBytes(bytes.data(), bytes.size());
  s->core = GBACoreCreate();
  if (!s->core || !s->core->init(s->core)) {
    s->core = nullptr;
    delete s;
    return 0;
  }
  mCoreInitConfig(s->core, nullptr);
  s->core->opts.useBios = false;
  s->core->opts.skipBios = true;
  auto *f = VFileOpen(path.c_str(), O_RDONLY);
  if (!f || !s->core->isROM(f) || !s->core->loadROM(s->core, f)) {
    if (f)
      f->close(f);
    delete s;
    return 0;
  }
  s->core->setVideoBuffer(s->core, s->pixels.data(), 240);
  s->core->setAudioBufferSize(s->core, 4096);
  auto save = readBytes(s->battery);
  s->saveFile = VFileMemChunk(save.data(), save.size());
  if (!s->saveFile || !s->core->loadSave(s->core, s->saveFile)) {
    delete s;
    return 0;
  }
#ifdef EMUCORED_SESSION_CONTRACT
  if (allowBatteryInitialization && !allowBatteryInitialization(s)) {
    delete s;
    return 0;
  }
#endif
  s->core->reset(s->core);
  if (audio)
    s->output.initAudio(s->core->audioSampleRate(s->core));
  s->batteryReady = true;
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
  s->core->runFrame(s->core);
  s->output.present(s->pixels.data(), 240, 160);
  int16_t samples[8192];
  auto *a = s->core->getAudioBuffer(s->core);
  size_t n = mAudioBufferRead(a, samples, 4096);
  s->output.audioWrite(samples, n);
  return true;
}
extern "C" JNIEXPORT jdouble JNICALL JNI(nativeRate)(JNIEnv *, jobject) {
  return 59.72750057;
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
  if (player != 0)
    return;
  int key = -1;
  switch (b) {
  case 0:
    key = 0;
    break;
  case 1:
    key = 1;
    break;
  case 4:
    key = 3;
    break;
  case 5:
    key = 2;
    break;
  case 6:
    key = 9;
    break;
  case 7:
    key = 8;
    break;
  case 10:
    key = 6;
    break;
  case 11:
    key = 7;
    break;
  case 12:
    key = 5;
    break;
  case 13:
    key = 4;
    break;
  }
  if (key >= 0) {
    auto *s = (Session *)h;
    if (p)
      s->core->addKeys(s->core, 1 << key);
    else
      s->core->clearKeys(s->core, 1 << key);
  }
}
extern "C" JNIEXPORT jboolean JNICALL JNI(nativeStateFile)(JNIEnv *e, jobject,
                                                           jlong h,
                                                           jstring path,
                                                           jboolean save) {
  auto *s = (Session *)h;
  size_t n = s->core->stateSize(s->core);
  std::vector<uint8_t> b;
  if (save) {
    b.resize(n);
    return s->core->saveState(s->core, b.data()) &&
           saveEnvelope(utf(e, path), s->rom, b);
  }
  b = loadEnvelope(utf(e, path), s->rom);
  return b.size() == n && s->core->loadState(s->core, b.data());
}
extern "C" JNIEXPORT jboolean JNICALL JNI(nativeOption)(JNIEnv *e, jobject,
                                                        jlong h, jstring key,
                                                        jstring value) {
  auto *s = (Session *)h;
  auto k = utf(e, key), v = utf(e, value);
  if (k == "aspect_ratio") return s->output.setAspectOption(v);
  if (k == "audio_enabled" && (v == "true" || v == "false")) {
    s->output.muted = v == "false";
    return true;
  }
  if (k == "video_filter" && (v == "nearest" || v == "linear")) {
    s->output.linear = v == "linear";
    return true;
  }
  if (k == "volume" || k == "frameskip") {
    int n = -1;
    auto parsed = std::from_chars(v.data(), v.data() + v.size(), n);
    const int maximum = k == "volume" ? 100 : 10;
    if (parsed.ec != std::errc{} || parsed.ptr != v.data() + v.size() || n < 0 || n > maximum) return false;
    if (k == "volume") s->core->opts.volume = (n * 256 + 50) / 100;
    else s->core->opts.frameskip = n;
    s->core->reloadConfigOption(s->core, nullptr, nullptr);
    return true;
  }
  if (k == "idle_optimization") {
    auto* gba = static_cast<GBA*>(s->core->board);
    if (v == "ignore") gba->idleOptimization = IDLE_LOOP_IGNORE;
    else if (v == "remove") gba->idleOptimization = IDLE_LOOP_REMOVE;
    else if (v == "detect") gba->idleOptimization = gba->idleLoop == GBA_IDLE_LOOP_NONE ? IDLE_LOOP_DETECT : IDLE_LOOP_REMOVE;
    else return false;
    return true;
  }
  if (k == "allow_opposing_directions" && (v == "true" || v == "false")) {
    static_cast<GBA*>(s->core->board)->allowOpposingDirections = v == "true";
    return true;
  }
  return false;
}
