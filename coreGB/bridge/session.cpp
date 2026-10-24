// SPDX-License-Identifier: GPL-3.0-or-later
#include "android_output.h"
#include "gb.h"
#include "open_boot_roms.h"
#include "typed_cheats.h"
#include <sys/stat.h>
#include <charconv>
struct Session {
  std::vector<TypedCheatInput> activeCheats;
  AndroidOutput output;
  GB_gameboy_t *gb = nullptr;
  std::vector<uint32_t> pixels = std::vector<uint32_t>(160 * 144);
  std::vector<int16_t> samples;
  std::string battery;
  uint64_t rom = 0;
  bool batteryReady = false;
  ~Session() {
    if (gb) {
      int n = GB_save_battery_size(gb);
      if (batteryReady && n > 0) {
        std::vector<uint8_t> b(n);
        if (!GB_save_battery_to_buffer(gb, b.data(), b.size()))
          writeBytes(battery, b.data(), b.size());
      }
      GB_dealloc(gb);
    }
  }
};
static bool replaceCheats(Session* s,const std::vector<TypedCheatInput>& codes) {
  if(!s || !s->gb)return false;
  auto* temporary=GB_init(GB_alloc(),GB_MODEL_CGB_E);
  if(!temporary)return false;
  bool valid=true;
  for(const auto& block:codes) {
    const char* pattern=block.format=="gb-gameshark"?"[0-9A-F]{8}":block.format=="gb-game-genie"?"[0-9A-F]{3}-[0-9A-F]{3}(-[0-9A-F]{3})?":nullptr;
    if(!pattern) {valid=false;break;}
    for(const auto& line:block.lines) if(!cheatLineMatches(line,pattern) || !GB_import_cheat(temporary,line.c_str(),"",true)) {valid=false;break;}
    if(!valid)break;
  }
  GB_dealloc(temporary); if(!valid)return false;
  const auto previous=s->activeCheats;
  auto install=[&](const std::vector<TypedCheatInput>& list) {
    GB_remove_all_cheats(s->gb);
    for(const auto& block:list)for(const auto& line:block.lines)if(!GB_import_cheat(s->gb,line.c_str(),"",true))return false;
    GB_set_cheats_enabled(s->gb,!list.empty()); return true;
  };
  if(!install(codes)) {install(previous);return false;}
  s->activeCheats=codes; return true;
}
// Accept the canonical formats emitted by this pinned engine. Reject incomplete
// reads before handing any data to SameBoy (its loader silently accepts short
// RAM).
static bool loadValidatedBattery(Session *s) {
  struct stat info{};
  if (stat(s->battery.c_str(), &info) != 0)
    return errno == ENOENT;
  if (!S_ISREG(info.st_mode))
    return false;
  const int expected = GB_save_battery_size(s->gb);
  if (expected < 0 || info.st_size != expected)
    return false;
  auto bytes = readBytes(s->battery);
  if (bytes.size() != static_cast<size_t>(expected))
    return false;
  size_t ramSize = 0;
  GB_get_direct_access(s->gb, GB_DIRECT_ACCESS_CART_RAM, &ramSize, nullptr);
  if (ramSize > bytes.size())
    return false;
  const size_t extra = bytes.size() - ramSize;
  const uint8_t *rtc = bytes.data() + ramSize;
  auto timestamp = [](const uint8_t *p) {
    uint64_t value = 0;
    for (int i = 7; i >= 0; i--)
      value = (value << 8) | p[i];
    return value;
  };
  auto validTimestamp = [&](const uint8_t *p) {
    auto t = timestamp(p);
    return t >= 852076800ULL && t <= static_cast<uint64_t>(time(nullptr));
  };
  if (extra == 48) {
    // VBA64: two five-register RTC banks padded to 32-bit words, then LE64
    // time.
    for (size_t bank : {size_t(0), size_t(20)}) {
      for (size_t reg = 0; reg < 5; reg++)
        for (size_t pad = 1; pad < 4; pad++)
          if (rtc[bank + reg * 4 + pad])
            return false;
      if ((rtc[bank] & ~63) || (rtc[bank + 4] & ~63) || (rtc[bank + 8] & ~31) ||
          (rtc[bank + 16] & ~0xc1))
        return false;
    }
    if (!validTimestamp(rtc + 40))
      return false;
  } else if (extra == 20) {
    // TPP1 packed header and timestamp.
    if (memcmp(rtc, "TPP1", 4) || rtc[4] != 1 || rtc[5] != 0 || rtc[7] != 0 ||
        !validTimestamp(rtc + 8))
      return false;
  } else if (extra == sizeof(GB_huc3_rtc_time_t)) {
    if (!validTimestamp(rtc) || rtc[16] > 1)
      return false;
  } else if (extra != 0)
    return false;
  if (!bytes.empty())
    GB_load_battery_from_buffer(s->gb, bytes.data(), bytes.size());
  return true;
}
static uint32_t rgb(GB_gameboy_t *, uint8_t r, uint8_t g, uint8_t b) {
  return 0xff000000u | r | (g << 8) | (b << 16);
}
static void sample(GB_gameboy_t *g, GB_sample_t *p) {
  auto *s = (Session *)GB_get_user_data(g);
  if (s->samples.size() < 16384) {
    s->samples.push_back(p->left);
    s->samples.push_back(p->right);
  }
}
#define JNI(name) Java_com_sbro_emucored_core_gb_SameBoySessionEngine_##name
extern "C" JNIEXPORT jboolean JNICALL JNI(nativeCheats)(JNIEnv* env,jobject,jlong handle,jobjectArray formats,jobjectArray codes) {
  std::vector<TypedCheatInput> inputs; return typedCheatInputs(env,formats,codes,inputs) && replaceCheats(reinterpret_cast<Session*>(handle),inputs);
}
static Session *createSession(const std::string &path,
                              const std::string &battery,
                              const std::string &platform, bool audio = true) {
  auto bytes = readBytes(path);
  if (bytes.size() < 0x150)
    return 0;
  uint8_t checksum = 0;
  for (size_t i = 0x134; i <= 0x14c; i++)
    checksum = checksum - bytes[i] - 1;
  if (checksum != bytes[0x14d])
    return 0;
  auto *s = new Session;
  s->battery = battery;
  s->rom = hashBytes(bytes.data(), bytes.size());
  bool cgb = platform == "GBC";
  s->gb = GB_init(GB_alloc(), cgb ? GB_MODEL_CGB_E : GB_MODEL_DMG_B);
  if (!s->gb) {
    delete s;
    return 0;
  }
  if (GB_load_rom(s->gb, path.c_str()) || bytes.size() < 0x8000) {
    delete s;
    return 0;
  }
  GB_load_boot_rom_from_buffer(s->gb, cgb ? cgb_boot : dmg_boot,
                               cgb ? sizeof(cgb_boot) : sizeof(dmg_boot));
  GB_set_user_data(s->gb, s);
  GB_set_rgb_encode_callback(s->gb, rgb);
  GB_set_pixels_output(s->gb, s->pixels.data());
  GB_set_turbo_mode(s->gb, true, true);
  GB_set_sample_rate(s->gb, 48000);
  GB_apu_set_sample_callback(s->gb, sample);
  if (!loadValidatedBattery(s)) {
    delete s;
    return 0;
  }
  if (audio)
    s->output.initAudio(48000);
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
  s->samples.clear();
  GB_run_frame(s->gb);
  s->output.present(s->pixels.data(), 160, 144);
  s->output.audioWrite(s->samples.data(), s->samples.size() / 2);
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
  if (player)
    return;
  int k = -1;
  switch (b) {
  case 0:
    k = GB_KEY_A;
    break;
  case 1:
    k = GB_KEY_B;
    break;
  case 4:
    k = GB_KEY_START;
    break;
  case 5:
    k = GB_KEY_SELECT;
    break;
  case 10:
    k = GB_KEY_UP;
    break;
  case 11:
    k = GB_KEY_DOWN;
    break;
  case 12:
    k = GB_KEY_LEFT;
    break;
  case 13:
    k = GB_KEY_RIGHT;
    break;
  }
  if (k >= 0)
    GB_set_key_state(((Session *)h)->gb, (GB_key_t)k, p);
}
extern "C" JNIEXPORT jboolean JNICALL JNI(nativeStateFile)(JNIEnv *e, jobject,
                                                           jlong h,
                                                           jstring path,
                                                           jboolean save) {
  auto *s = (Session *)h;
  std::vector<uint8_t> b;
  if (save) {
    b.resize(GB_get_save_state_size(s->gb));
    GB_save_state_to_buffer(s->gb, b.data());
    return saveEnvelope(utf(e, path), s->rom, b);
  }
  b = loadEnvelope(utf(e, path), s->rom);
  return !b.empty() &&
         GB_load_state_from_buffer(s->gb, b.data(), b.size()) == 0;
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
  if (k == "color_correction" && (v == "true" || v == "false")) {
    GB_set_color_correction_mode(s->gb, v == "true"
                                            ? GB_COLOR_CORRECTION_CORRECT_CURVES
                                            : GB_COLOR_CORRECTION_DISABLED);
    return true;
  }
  if (k == "palette") {
    const GB_palette_t* palette = nullptr;
    if (v == "dmg") palette = &GB_PALETTE_DMG;
    else if (v == "grey") palette = &GB_PALETTE_GREY;
    else if (v == "pocket") palette = &GB_PALETTE_MGB;
    else if (v == "light") palette = &GB_PALETTE_GBL;
    else return false;
    GB_set_palette(s->gb, palette);
    return true;
  }
  if (k == "highpass_filter") {
    if (v == "off") GB_set_highpass_filter_mode(s->gb, GB_HIGHPASS_OFF);
    else if (v == "accurate") GB_set_highpass_filter_mode(s->gb, GB_HIGHPASS_ACCURATE);
    else if (v == "remove_dc") GB_set_highpass_filter_mode(s->gb, GB_HIGHPASS_REMOVE_DC_OFFSET);
    else return false;
    return true;
  }
  if (k == "interference_volume") {
    int volume = -1;
    auto parsed = std::from_chars(v.data(), v.data() + v.size(), volume);
    if (parsed.ec != std::errc{} || parsed.ptr != v.data() + v.size() || volume < 0 || volume > 100) return false;
    GB_set_interference_volume(s->gb, volume / 100.0);
    return true;
  }
  return false;
}
