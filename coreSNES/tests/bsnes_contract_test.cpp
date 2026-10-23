// SPDX-License-Identifier: GPL-3.0-or-later
#include <algorithm>
#include <array>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <vector>
#include "libretro.h"

extern "C" bool emucored_bsnes_loaded();
extern "C" bool emucored_bsnes_load_game(const char*);
extern "C" unsigned emucored_bsnes_bus_read(unsigned);
extern "C" void emucored_bsnes_bus_write(unsigned, unsigned);
#define CHECK(x) do { if (!(x)) { std::fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); return 1; } } while (0)
static unsigned frames, audio_frames;
static uint32_t center_pixel;
static const char* directory;
static bool environment(unsigned command, void* data) {
    if (command == RETRO_ENVIRONMENT_SET_PIXEL_FORMAT) return *static_cast<retro_pixel_format*>(data) == RETRO_PIXEL_FORMAT_XRGB8888;
    if (command == RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY || command == RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY) {
        *static_cast<const char**>(data) = directory; return true;
    }
    if (command == RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE) { *static_cast<bool*>(data) = false; return true; }
    return false;
}
static void video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data || data == RETRO_HW_FRAME_BUFFER_VALID) return;
    ++frames;
    center_pixel = static_cast<const uint32_t*>(data)[(height / 2) * (pitch / 4) + width / 2];
}
static size_t audio(const int16_t*, size_t count) { audio_frames += count; return count; }
static void poll() {}
static int16_t input(unsigned, unsigned, unsigned, unsigned) { return 0; }

int main(int argc, char** argv) {
    CHECK(argc == 2);
    directory = argv[1];
    const std::string path = std::string(directory) + "/original-snes-fixture.sfc";
    const std::string renamed = std::string(directory) + "/different-cache-filename.sfc";
    std::array<unsigned char, 32768> rom{};
    // Original 65816 program: unblank the screen with red backdrop, then loop.
    const unsigned char code[] = {0x78,0x18,0xFB,0xC2,0x10,0xE2,0x20,
        0xA9,0x80,0x8D,0x00,0x21,0xA9,0x00,0x8D,0x21,0x21,
        0xA9,0x1F,0x8D,0x22,0x21,0xA9,0x00,0x8D,0x22,0x21,
        0xA9,0x0F,0x8D,0x00,0x21,0x80,0xFE};
    std::copy(std::begin(code), std::end(code), rom.begin());
    std::memcpy(rom.data() + 0x7FC0, "EMUCORE SNES TEST     ", 21);
    rom[0x7FD5] = 0x20; rom[0x7FD6] = 2; rom[0x7FD7] = 5; rom[0x7FD8] = 1; rom[0x7FD9] = 1;
    rom[0x7FDC] = 0xCB; rom[0x7FDD] = 0xED; rom[0x7FDE] = 0x34; rom[0x7FDF] = 0x12;
    rom[0x7FFC] = 0; rom[0x7FFD] = 0x80;
    { std::ofstream file(path, std::ios::binary); file.write(reinterpret_cast<const char*>(rom.data()), rom.size()); CHECK(file.good()); }
    { std::ofstream file(renamed, std::ios::binary); file.write(reinterpret_cast<const char*>(rom.data()), rom.size()); CHECK(file.good()); }
    const std::string battery = std::string(directory) + "/game.srm";
    std::remove(battery.c_str());
    for (unsigned cycle = 0; cycle < 2; ++cycle) {
        frames = audio_frames = 0;
        retro_set_environment(environment); retro_set_video_refresh(video);
        retro_set_audio_sample_batch(audio); retro_set_input_poll(poll); retro_set_input_state(input);
        retro_init();
        CHECK(emucored_bsnes_load_game((cycle ? renamed : path).c_str()) && emucored_bsnes_loaded());
        if (cycle) CHECK(emucored_bsnes_bus_read(0x700000) == 0x42);
        for (unsigned frame = 0; frame < 8; ++frame) retro_run();
        CHECK(frames == 8 && audio_frames > 0);
        CHECK(((center_pixel >> 16) & 255) > 200 && (center_pixel & 255) < 40);
        std::vector<unsigned char> state(retro_serialize_size()); CHECK(state.size() > 1024);
        CHECK(retro_serialize(state.data(), state.size()));
        retro_run(); CHECK(retro_unserialize(state.data(), state.size()));
        retro_run(); CHECK(((center_pixel >> 16) & 255) > 200);
        std::fill(state.begin(), state.end(), 0); CHECK(!retro_unserialize(state.data(), state.size()));
        emucored_bsnes_bus_write(0x700000, 0x42);
        retro_unload_game(); CHECK(!emucored_bsnes_loaded()); retro_deinit();
        { std::ifstream file(battery, std::ios::binary); CHECK(file.get() == 0x42); }
    }
    std::remove(path.c_str());
    std::remove(renamed.c_str()); std::remove(battery.c_str());
    std::puts("PASS bsnes: original homebrew, rendered pixels, PCM, state restore, corrupt state rejection, recreation");
}
