// SPDX-License-Identifier: GPL-3.0-or-later
// Retain the pinned upstream frontend-independent ABI, with an explicit loaded
// acknowledgement: retro_load_game alone does not report a failed core load.
#include "target-libretro/libretro.cpp"

struct EmuCoreProgram : Program {
    auto open(uint id, string name, vfs::file::mode mode, bool required) -> shared_pointer<vfs::file> override {
        if (id == ::SuperFamicom::ID::SuperFamicom && (name == "time.rtc" || name.endsWith(".data.ram"))) {
            string save_path;
            auto suffix = Location::suffix(base_name);
            auto base = Location::base(base_name.transform("\\", "/"));
            const string ext = (name == "time.rtc") ? string(".rtc") : string(".", name);
            const char *save = nullptr;
            if (environ_cb && environ_cb(RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY, &save) && save)
                save_path = { string(save).transform("\\", "/"), "/", base.trimRight(suffix, 1L), ext };
            else
                save_path = { base_name.trimRight(suffix, 1L), ext };
            return vfs::fs::file::open(save_path, mode);
        }
        return Program::open(id, name, mode, required);
    }
};

extern "C" void emucored_bsnes_init() {
    audio_buffer_index = 0;
    audio_buffer_max = AUDIOBUFSIZE;
    emulator = new SuperFamicom::Interface;
    program = new EmuCoreProgram;
}
extern "C" void emucored_bsnes_deinit() {
    audio_buffer_index = 0;
    audio_buffer_max = AUDIOBUFSIZE;
    delete program;
    program = nullptr;
    delete emulator;
    emulator = nullptr;
}
extern "C" void emucored_bsnes_sync_audio_buffer() {
    audio_buffer_index = 0;
    audio_buffer_max = (emulator && emulator->loaded() && retro_get_region() == RETRO_REGION_NTSC)
        ? (SAMPLERATE / 60) * 2
        : AUDIOBUFSIZE;
}
extern "C" bool emucored_bsnes_loaded() { return emulator && emulator->loaded(); }
extern "C" void emucored_bsnes_save_sram() {
    if (emulator && emulator->loaded() && program) {
        program->save();
    }
}
extern "C" bool emucored_bsnes_valid_cheat(const char* code) {
    string decoded(code); return decodeSNES(decoded);
}
extern "C" bool emucored_bsnes_load_game(const char* path) {
    retro_pixel_format format = RETRO_PIXEL_FORMAT_XRGB8888;
    if (!environ_cb(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &format)) return false;
    emulator->configure("Audio/Frequency", SAMPLERATE);
    program->filterRender = &Filter::None::render;
    program->filterSize = &Filter::None::size;
    program->updateVideoPalette();
    flush_variables();
    program->superFamicom.location = string(path);
    // GET_SAVE_DIRECTORY already points at this game's stable identity hash.
    // The physical source may be renamed every time SAF/cache is refreshed.
    program->base_name = "game.sfc";
    program->load();
    if (!emulator->loaded()) return false;
    emulator->connect(SuperFamicom::ID::Port::Controller1, SuperFamicom::ID::Device::Gamepad);
    emulator->connect(SuperFamicom::ID::Port::Controller2, SuperFamicom::ID::Device::Gamepad);
    return true;
}
