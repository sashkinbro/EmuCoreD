// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import androidx.annotation.Keep

/**
 * The core's Gateway cheat engine. Cheat files live next to the emulated
 * title in the core user directory and are keyed by title ID.
 */
@Keep
object CheatEngine {
    @JvmStatic
    external fun loadCheatFile(titleId: Long)

    @JvmStatic
    external fun saveCheatFile(titleId: Long)

    @JvmStatic
    external fun getCheats(): Array<Cheat>?

    @JvmStatic
    external fun addCheat(cheat: Cheat)

    @JvmStatic
    external fun removeCheat(index: Int)

    @JvmStatic
    external fun updateCheat(index: Int, newCheat: Cheat)
}
