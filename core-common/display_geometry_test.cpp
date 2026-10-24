// SPDX-License-Identifier: GPL-3.0-or-later
#include "display_geometry.h"
#include <cstdio>
#include <limits>

#define CHECK(condition) do { if (!(condition)) { std::fprintf(stderr, "FAIL %d: %s\n", __LINE__, #condition); return 1; } } while (0)
int main() {
  auto ntsc = displayViewport(640, 480, 4.0 / 3);
  CHECK(ntsc.x == 0 && ntsc.y == 0 && ntsc.width == 640 && ntsc.height == 480);
  auto nesPixels = displayViewport(640, 480, 256.0 / 240);
  CHECK(nesPixels.x == 64 && nesPixels.width == 512 && nesPixels.height == 480);
  auto snes = displayViewport(1920, 1080, 4.0 / 3);
  CHECK(snes.x == 240 && snes.y == 0 && snes.width == 1440 && snes.height == 1080);
  auto gba = displayViewport(1920, 1080, 3.0 / 2);
  CHECK(gba.x == 150 && gba.width == 1620 && gba.height == 1080);
  auto gb = displayViewport(1920, 1080, 10.0 / 9);
  CHECK(gb.x == 360 && gb.width == 1200 && gb.height == 1080);
  auto wide = displayViewport(640, 480, 16.0 / 9);
  CHECK(wide.y == 60 && wide.width == 640 && wide.height == 360);
  auto portrait = displayViewport(1080, 2400, 4.0 / 3);
  CHECK(portrait.y == 795 && portrait.width == 1080 && portrait.height == 810);
  CHECK(displayViewport(0, 480, 4.0 / 3).width == 0);
  CHECK(displayViewport(640, -1, 4.0 / 3).height == 0);
  CHECK(displayViewport(640, 480, std::numeric_limits<double>::quiet_NaN()).width == 0);
  CHECK(displayViewport(640, 480, std::numeric_limits<double>::infinity()).width == 0);
  CHECK(displayViewport(1, 1, std::numeric_limits<double>::min()).width == 1);
  CHECK(displayViewport(1, 1, std::numeric_limits<double>::max()).height == 1);
  std::puts("PASS display geometry: console proportions, overrides, portrait, invalid input");
}
