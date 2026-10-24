// SPDX-License-Identifier: GPL-3.0-or-later
#include "android_output.h"
#include "libretro.h"
#include "typed_cheats.h"
#include <map>
#include <memory>
#include <mutex>
extern "C" bool emucored_bsnes_loaded();
extern "C" bool emucored_bsnes_valid_cheat(const char*);
extern "C" bool emucored_bsnes_load_game(const char* path);
namespace {
struct Session {
    AndroidOutput output;
    Session() { output.defaultAspect = 4.0 / 3; }
    uint64_t romHash = 0;
    std::string directory;
    std::map<std::string, std::string> options{{"bsnes_ppu_fast","ON"}, {"bsnes_dsp_fast","ON"}, {"bsnes_entropy","Low"}};
    uint16_t buttons[4]{};
    std::vector<uint32_t> rgba;
    bool optionsChanged = true;
};
Session* active = nullptr;
std::mutex ownership;
bool environment(unsigned command, void* data) {
    if (!active) return false;
    switch (command) {
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: return *static_cast<retro_pixel_format*>(data) == RETRO_PIXEL_FORMAT_XRGB8888;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        *static_cast<const char**>(data) = active->directory.c_str(); return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        auto* variable = static_cast<retro_variable*>(data);
        // Upstream publishes the shorter SA1/SFX keys but queries legacy aliases.
        std::string key = variable->key;
        if (key == "bsnes_cpu_sa1_overclock") key = "bsnes_sa1_overclock";
        if (key == "bsnes_cpu_sfx_overclock") key = "bsnes_sfx_overclock";
        auto found = active->options.find(key);
        if (found == active->options.end()) return false;
        variable->value = found->second.c_str(); return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *static_cast<bool*>(data) = active->optionsChanged; active->optionsChanged = false; return true;
    case RETRO_ENVIRONMENT_GET_FASTFORWARDING: *static_cast<bool*>(data) = false; return true;
    default: return false;
    }
}
void video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!active || !data || data == RETRO_HW_FRAME_BUFFER_VALID || !width || !height || width > 4096 || height > 4096) return;
    active->rgba.resize(size_t(width)*height);
    for (unsigned y=0; y<height; ++y) {
        auto* row = reinterpret_cast<const uint32_t*>(static_cast<const uint8_t*>(data)+y*pitch);
        for (unsigned x=0; x<width; ++x) {
            const uint32_t p=row[x];
            active->rgba[y*width+x] = 0xFF000000u | ((p&255)<<16) | (p&0xFF00) | ((p>>16)&255);
        }
    }
    active->output.present(active->rgba.data(), width, height);
}
size_t audio(const int16_t* samples, size_t frames) { if(active) active->output.audioWrite(samples,frames); return frames; }
void poll() {}
int16_t input(unsigned player, unsigned device, unsigned, unsigned button) {
    return active && player<4 && device==RETRO_DEVICE_JOYPAD && button<16 && (active->buttons[player]&(1u<<button)) ? 1 : 0;
}
}
#define JNI(name) extern "C" JNIEXPORT name JNICALL
JNI(jboolean) Java_com_sbro_emucored_core_snes_BsnesSessionEngine_nativeCheats(JNIEnv* env,jobject,jlong handle,jobjectArray formats,jobjectArray codes) {
    auto* session=reinterpret_cast<Session*>(handle);if(!session || session!=active)return false;
    std::vector<TypedCheatInput> inputs; if(!typedCheatInputs(env,formats,codes,inputs))return false;
    std::vector<std::string> staged;
    for(const auto& block:inputs)for(auto line:block.lines) {
        const char* pattern=block.format=="snes-game-genie"?"[0-9A-F]{4}-[0-9A-F]{4}":
            block.format=="snes-pro-action-replay"?"[0-9A-F]{8}":block.format=="snes-raw-address-value"?"[0-9A-F]{6}:[0-9A-F]{2}":nullptr;
        if(!pattern || !cheatLineMatches(line,pattern))return false;
        for(auto& ch:line){if(ch==':')ch='=';else ch=std::tolower(static_cast<unsigned char>(ch));}
        if(!emucored_bsnes_valid_cheat(line.c_str()))return false;
        staged.push_back(std::move(line));
    }
    retro_cheat_reset(); unsigned index=0;
    for(const auto& line:staged)retro_cheat_set(index++,true,line.c_str());
    return true;
}
JNI(jlong) Java_com_sbro_emucored_core_snes_BsnesSessionEngine_nativeCreate(JNIEnv* env,jobject,jstring path,jstring directory) {
    std::scoped_lock lock(ownership);
    if(active) return 0;
    auto rom=readBytes(utf(env,path)); if(rom.size()<32768) return 0;
    auto session=std::make_unique<Session>(); session->directory=utf(env,directory);
    session->romHash=hashBytes(rom.data(),rom.size()); active=session.get();
    retro_set_environment(environment); retro_set_video_refresh(video); retro_set_audio_sample_batch(audio);
    retro_set_input_poll(poll); retro_set_input_state(input); retro_init();
    const auto source=utf(env,path);
    if(!emucored_bsnes_load_game(source.c_str())) { retro_deinit(); active=nullptr; return 0; }
    retro_cheat_reset();
    if(!session->output.initAudio(48000)) { retro_unload_game(); retro_deinit(); active=nullptr; return 0; }
    return reinterpret_cast<jlong>(session.release());
}
JNI(void) Java_com_sbro_emucored_core_snes_BsnesSessionEngine_nativeDestroy(JNIEnv*,jobject,jlong handle) {
    std::scoped_lock lock(ownership);
    auto* session=reinterpret_cast<Session*>(handle); if(!session || session!=active) return;
    retro_cheat_reset(); retro_unload_game(); retro_deinit(); active=nullptr; delete session;
}
JNI(jboolean) Java_com_sbro_emucored_core_snes_BsnesSessionEngine_nativeFrame(JNIEnv*,jobject,jlong handle) {
    if(reinterpret_cast<Session*>(handle)!=active || !active || !emucored_bsnes_loaded()) return false;
    retro_run(); return true;
}
JNI(jdouble) Java_com_sbro_emucored_core_snes_BsnesSessionEngine_nativeRate(JNIEnv*,jobject,jlong handle) {
    if(reinterpret_cast<Session*>(handle)!=active || !active) return 60;
    retro_system_av_info info{}; retro_get_system_av_info(&info); return info.timing.fps;
}
JNI(void) Java_com_sbro_emucored_core_snes_BsnesSessionEngine_nativeSurface(JNIEnv* env,jobject,jlong handle,jobject surface) {
    if(auto* s=reinterpret_cast<Session*>(handle); s && s==active) s->output.attach(env,surface);
}
JNI(void) Java_com_sbro_emucored_core_snes_BsnesSessionEngine_nativePause(JNIEnv*,jobject,jlong handle,jboolean paused) {
    if(auto* s=reinterpret_cast<Session*>(handle); s && s==active) s->output.pause(paused);
}
JNI(void) Java_com_sbro_emucored_core_snes_BsnesSessionEngine_nativeButton(JNIEnv*,jobject,jlong handle,jint player,jint button,jboolean pressed) {
    auto* s=reinterpret_cast<Session*>(handle); if(!s || s!=active || player<0 || player>=4) return;
    // EngineButton enum A,B,X,Y,START,SELECT,L,R,...UP,DOWN,LEFT,RIGHT.
    constexpr int mapping[]={8,0,9,1,3,2,10,11,-1,-1,4,5,6,7};
    if(button<0 || button>=int(sizeof(mapping)/sizeof(mapping[0])) || mapping[button]<0) return;
    const auto bit=uint16_t(1u<<mapping[button]); if(pressed) s->buttons[player]|=bit; else s->buttons[player]&=~bit;
}
JNI(jboolean) Java_com_sbro_emucored_core_snes_BsnesSessionEngine_nativeStateFile(JNIEnv* env,jobject,jlong handle,jstring path,jboolean save) {
    auto* s=reinterpret_cast<Session*>(handle); if(!s || s!=active) return false;
    const auto file=utf(env,path);
    if(save) { const auto size=retro_serialize_size(); if(size==0 || size>64*1024*1024) return false;
        std::vector<uint8_t> bytes(size); return retro_serialize(bytes.data(),bytes.size()) && saveEnvelope(file,s->romHash,bytes); }
    auto bytes=loadEnvelope(file,s->romHash); return !bytes.empty() && retro_unserialize(bytes.data(),bytes.size());
}
JNI(jboolean) Java_com_sbro_emucored_core_snes_BsnesSessionEngine_nativeOption(JNIEnv* env,jobject,jlong handle,jstring key,jstring value) {
    auto* s=reinterpret_cast<Session*>(handle); if(!s || s!=active) return false;
    const auto k=utf(env,key),v=utf(env,value);
    if(k=="aspect_ratio") return s->output.setAspectOption(v);
    if(k=="audio_enabled" && (v=="true" || v=="false")) { s->output.muted=v=="false"; return true; }
    if(k=="video_filter" && (v=="nearest" || v=="linear")) { s->output.linear=v=="linear"; return true; }
    static const std::map<std::string,std::vector<std::string>> allowed={
        {"bsnes_ppu_fast",{"ON","OFF"}},
        {"bsnes_dsp_fast",{"ON","OFF"}},
        {"bsnes_entropy",{"Low","High","None"}},
        {"bsnes_blur_emulation",{"ON","OFF"}},
        {"bsnes_ppu_no_sprite_limit",{"ON","OFF"}},
        {"bsnes_ppu_show_overscan",{"ON","OFF"}},
        {"bsnes_hotfixes",{"ON","OFF"}},
        {"bsnes_mode7_scale",{"1x","2x","3x","4x","5x","6x","7x","8x"}},
        {"bsnes_cpu_fastmath",{"ON","OFF"}},
        {"bsnes_ppu_deinterlace",{"ON","OFF"}},
        {"bsnes_ppu_no_vram_blocking",{"ON","OFF"}},
        {"bsnes_mode7_perspective",{"ON","OFF"}},
        {"bsnes_mode7_supersample",{"ON","OFF"}},
        {"bsnes_mode7_mosaic",{"ON","OFF"}},
        {"bsnes_dsp_cubic",{"ON","OFF"}},
        {"bsnes_dsp_echo_shadow",{"ON","OFF"}},
        {"bsnes_coprocessor_delayed_sync",{"ON","OFF"}},
        {"bsnes_coprocessor_prefer_hle",{"ON","OFF"}},
        {"bsnes_cpu_overclock",{"10","20","30","40","50","60","70","80","90","100","110","120","130","140","150","160","170","180","190","200","210","220","230","240","250","260","270","280","290","300","310","320","330","340","350","360","370","380","390","400"}},
        {"bsnes_sa1_overclock",{"10","20","30","40","50","60","70","80","90","100","110","120","130","140","150","160","170","180","190","200","210","220","230","240","250","260","270","280","290","300","310","320","330","340","350","360","370","380","390","400"}},
        {"bsnes_sfx_overclock",{"10","20","30","40","50","60","70","80","90","100","110","120","130","140","150","160","170","180","190","200","210","220","230","240","250","260","270","280","290","300","310","320","330","340","350","360","370","380","390","400","410","420","430","440","450","460","470","480","490","500","510","520","530","540","550","560","570","580","590","600","610","620","630","640","650","660","670","680","690","700","710","720","730","740","750","760","770","780","790","800"}},
        {"bsnes_run_ahead_frames",{"OFF","1","2","3","4"}},
        {"bsnes_video_filter",{"None","NTSC (RF)","NTSC (Composite)","NTSC (S-Video)","NTSC (RGB)"}}};
    auto found=allowed.find(k); if(found==allowed.end() || std::find(found->second.begin(),found->second.end(),v)==found->second.end()) return false;
    s->options[k]=v; s->optionsChanged=true; return true;
}
