// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import androidx.annotation.Keep

/**
 * Progress sink for the core's disk shader cache compilation. The core builds
 * its shader cache on first boot; EmuCoreD uses this to drive a progress
 * indicator while the game is starting.
 */
@Keep
object DiskShaderCacheProgress {
    @Keep
    enum class LoadCallbackStage {
        Prepare,
        Decompile,
        Build,
        Complete
    }

    var onProgress: ((LoadCallbackStage, Int, Int, String) -> Unit)? = null

    @JvmStatic
    fun loadProgress(stage: LoadCallbackStage, progress: Int, max: Int, objectName: String) {
        onProgress?.invoke(stage, progress, max, objectName)
    }
}
