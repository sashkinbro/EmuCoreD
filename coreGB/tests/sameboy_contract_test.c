// SPDX-License-Identifier: GPL-3.0-or-later
#include "gb.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(condition) do { if (!(condition)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #condition); return 1; } } while (0)

static uint32_t encode_rgb(GB_gameboy_t* gb, uint8_t red, uint8_t green, uint8_t blue) {
    (void)gb;
    /* RGBA bytes on little-endian Android, matching the future native presenter. */
    return 0xFF000000u | red | ((uint32_t)green << 8) | ((uint32_t)blue << 16);
}

static int exercise(GB_model_t model) {
    GB_gameboy_t* gb = GB_init(GB_alloc(), model);
    CHECK(gb && GB_is_inited(gb));
    CHECK(GB_is_cgb(gb) == (model == GB_MODEL_CGB_E));
    unsigned char* rom = calloc(0x8000, 1);
    CHECK(rom);
    /* Original cartridge program: enable LCD then spin. */
    rom[0x100] = 0xC3; rom[0x101] = 0x50; rom[0x102] = 0x01;
    memcpy(rom + 0x134, "EMUCORE TEST", 12);
    rom[0x143] = model == GB_MODEL_CGB_E ? 0x80 : 0;
    unsigned char checksum = 0;
    for (int i = 0x134; i <= 0x14C; i++) checksum = checksum - rom[i] - 1;
    rom[0x14D] = checksum;
    const unsigned char program[] = {0x3E, 0x91, 0xE0, 0x40, 0x18, 0xFE};
    memcpy(rom + 0x150, program, sizeof(program));
    GB_load_rom_from_buffer(gb, rom, 0x8000);
    free(rom);
    /* Original boot stub disables its own mapping at PC=0x100; no proprietary BIOS. */
    unsigned char boot[0x900] = {0};
    boot[0] = 0xC3; boot[1] = 0xFC; boot[2] = 0;
    boot[0xFC] = 0x3E; boot[0xFD] = 1; boot[0xFE] = 0xE0; boot[0xFF] = 0x50;
    GB_load_boot_rom_from_buffer(gb, boot, sizeof(boot));
    uint32_t* video = malloc(160 * 144 * sizeof(uint32_t));
    CHECK(video);
    for (int i = 0; i < 160 * 144; i++) video[i] = 0x12345678;
    GB_set_rgb_encode_callback(gb, encode_rgb);
    GB_set_color_correction_mode(gb, GB_COLOR_CORRECTION_DISABLED);
    if (model == GB_MODEL_CGB_E) {
        GB_write_memory(gb, 0xFF68, 0x80);
        GB_write_memory(gb, 0xFF69, 0x1F);
        GB_write_memory(gb, 0xFF69, 0x00);
    }
    GB_set_pixels_output(gb, video);
    GB_set_turbo_mode(gb, true, true);
    for (int i = 0; i < 4; i++) CHECK(GB_run_frame(gb) > 0);
    CHECK(video[160 * 72] != 0x12345678);
    CHECK(GB_get_screen_width(gb) == 160 && GB_get_screen_height(gb) == 144);
    CHECK(GB_is_cgb_in_cgb_mode(gb) == (model == GB_MODEL_CGB_E));
    if (model == GB_MODEL_CGB_E) {
        CHECK((video[160 * 72] & 0xFF) > 240);
        CHECK(((video[160 * 72] >> 16) & 0xFF) < 10);
    }
    GB_write_memory(gb, 0xFF00, 0x10); /* select buttons */
    GB_set_key_state(gb, GB_KEY_A, true);
    /* SameBoy models physical contact bounce for up to 0xFFF guest ticks.
     * Sample after an emulated frame, without disabling hardware behavior. */
    GB_run_frame(gb);
    CHECK((GB_read_memory(gb, 0xFF00) & 1) == 0);
    GB_set_key_state(gb, GB_KEY_A, false);
    GB_run_frame(gb);
    CHECK((GB_read_memory(gb, 0xFF00) & 1) == 1);
    GB_write_memory(gb, 0xC040, 0x5A);
    const size_t state_size = GB_get_save_state_size(gb);
    CHECK(state_size > 1024);
    unsigned char* state = malloc(state_size);
    CHECK(state);
    GB_save_state_to_buffer(gb, state);
    GB_write_memory(gb, 0xC040, 0xA5);
    GB_run_frame(gb);
    CHECK(GB_load_state_from_buffer(gb, state, state_size) == 0);
    CHECK(GB_read_memory(gb, 0xC040) == 0x5A);
    CHECK(GB_load_state_from_buffer(gb, state, 8) != 0);
    CHECK(GB_read_memory(gb, 0xC040) == 0x5A);
    free(state);
    free(video);
    GB_dealloc(gb);
    return 0;
}

int main(void) {
    CHECK(exercise(GB_MODEL_DMG_B) == 0);
    CHECK(exercise(GB_MODEL_CGB_E) == 0);
    CHECK(exercise(GB_MODEL_DMG_B) == 0);
    puts("PASS SameBoy: GB/GBC frames, pixels, input, memory/state restore, truncated-state rejection, recreation");
    return 0;
}
