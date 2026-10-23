// SPDX-License-Identifier: GPL-3.0-or-later
// Retain the pinned upstream frontend-independent ABI, with an explicit loaded
// acknowledgement: retro_load_game alone does not report a failed core load.
#include "target-libretro/libretro.cpp"
extern "C" bool emucored_bsnes_loaded() { return emulator && emulator->loaded(); }
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
