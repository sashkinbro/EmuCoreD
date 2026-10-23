// SPDX-License-Identifier: GPL-3.0-or-later
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/gba/core.h>
#include <mgba-util/vfs.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(condition) do { if (!(condition)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #condition); return 1; } } while (0)

/* Original tiny ARM cartridge: jump over the header and loop forever.
 * No Nintendo logo, copyrighted game, or external BIOS is distributed. */
static void create_rom(unsigned char rom[0x200]) {
    memset(rom, 0, 0x200);
    const unsigned char entry[] = {0x2E, 0x00, 0x00, 0xEA};
    const unsigned char loop[] = {0xFE, 0xFF, 0xFF, 0xEA};
    memcpy(rom, entry, sizeof(entry));
    memcpy(rom + 0xA0, "EMUCORE TEST", 12);
    rom[0xB2] = 0x96;
    memcpy(rom + 0xC0, loop, sizeof(loop));
}

static int exercise(void) {
    struct mCore* core = GBACoreCreate();
    CHECK(core && core->init(core));
    mCoreInitConfig(core, NULL);
    core->opts.useBios = false;
    core->opts.skipBios = true;
    unsigned char rom[0x200];
    create_rom(rom);
    struct VFile* file = VFileMemChunk(rom, sizeof(rom));
    CHECK(file && core->loadROM(core, file));
    unsigned width = 0, height = 0;
    core->baseVideoSize(core, &width, &height);
    CHECK(width == 240 && height == 160);
    mColor* video = calloc(240 * 160, sizeof(mColor));
    CHECK(video);
    core->setVideoBuffer(core, video, 240);
    core->reset(core);
    core->busWrite16(core, 0x04000000, 0x0403); /* mode 3, BG2, no forced blank */
    core->busWrite16(core, 0x06000000, 0x001F);
    core->busWrite16(core, 0x06000002, 0x7C00);
    const uint32_t initial_frame = core->frameCounter(core);
    for (int i = 0; i < 3; i++) core->runFrame(core);
    CHECK(core->frameCounter(core) == initial_frame + 3);
    CHECK(video[0] != video[1]);
    CHECK((video[0] & 0xFF) > 240 && ((video[0] >> 16) & 0xFF) < 10);
    CHECK(((video[1] >> 16) & 0xFF) > 240 && (video[1] & 0xFF) < 10);
    core->setKeys(core, 1); /* A */
    CHECK(core->getKeys(core) == 1);
    core->runFrame(core);
    CHECK((core->busRead16(core, 0x04000130) & 1) == 0);
    core->clearKeys(core, 1);
    core->runFrame(core);
    CHECK((core->busRead16(core, 0x04000130) & 1) == 1);
    core->busWrite32(core, 0x02000040, 0x1A2B3C4D);
    const size_t state_size = core->stateSize(core);
    CHECK(state_size > 1024);
    void* state = malloc(state_size);
    CHECK(state && core->saveState(core, state));
    const uint32_t saved_frame = core->frameCounter(core);
    core->busWrite32(core, 0x02000040, 0xDEADBEEF);
    core->runFrame(core);
    CHECK(core->loadState(core, state));
    CHECK(core->busRead32(core, 0x02000040) == 0x1A2B3C4D);
    CHECK(core->frameCounter(core) == saved_frame);
    memset(state, 0, state_size);
    CHECK(!core->loadState(core, state));
    CHECK(core->busRead32(core, 0x02000040) == 0x1A2B3C4D);
    free(state);
    mCoreConfigDeinit(&core->config);
    core->deinit(core);
    free(video);
    return 0;
}

int main(void) {
    /* Recreate the engine to catch process-global teardown/reinitialization mistakes. */
    CHECK(exercise() == 0);
    CHECK(exercise() == 0);
    puts("PASS mGBA: frames, pixels, input, memory/state restore, corrupt-state rejection, recreation");
    return 0;
}
