// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <algorithm>
#include <cmath>

struct DisplayViewport { int x, y, width, height; };

// Pixel dimensions describe a texture; CRT console display proportions are independent.
inline DisplayViewport displayViewport(int surfaceWidth, int surfaceHeight, double aspect) {
  if (surfaceWidth <= 0 || surfaceHeight <= 0 || !std::isfinite(aspect) || aspect <= 0) return {0, 0, 0, 0};
  int width = surfaceWidth;
  int height = surfaceHeight;
  if (double(surfaceWidth) / surfaceHeight > aspect) {
    width = std::max(1, static_cast<int>(std::lround(height * aspect)));
  } else height = std::max(1, static_cast<int>(std::lround(width / aspect)));
  width = std::min(width, surfaceWidth);
  height = std::min(height, surfaceHeight);
  return {(surfaceWidth - width) / 2, (surfaceHeight - height) / 2, width, height};
}
