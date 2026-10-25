package com.sbro.emucored.ui.emulation

import com.sbro.emucored.core.normalizeUpscale
import kotlin.math.roundToInt

/**
 * Internal render size shown by the performance HUD.
 *
 * Cores report the guest/display size (for example Dolphin reports the XFB size),
 * which does not change with the internal-resolution setting. Scale that base by
 * the selected upscale so the "Res:" line follows in-game resolution changes.
 */
internal fun scaledPerformanceResolution(width: Int, height: Int, upscale: Float): String? {
    if (width <= 0 || height <= 0) return null
    val multiplier = normalizeUpscale(upscale)
    return "${(width * multiplier).roundToInt()}x${(height * multiplier).roundToInt()}"
}
