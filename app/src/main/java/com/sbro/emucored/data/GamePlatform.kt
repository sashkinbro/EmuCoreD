// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.data

import com.sbro.emucored.core.N3dsGameFormats
import java.util.Locale

enum class GamePlatform {
    N3DS,
    NDS;

    companion object {
        private val ndsExtensions = setOf("nds", "srl", "dsi")

        fun fromFileName(name: String): GamePlatform? {
            val extension = name.substringAfterLast('/', name)
                .substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
            return when {
                N3dsGameFormats.isSupportedName(name) -> N3DS
                extension in ndsExtensions -> NDS
                else -> null
            }
        }

        fun isSupportedName(name: String): Boolean = fromFileName(name) != null

        fun fromPath(path: String): GamePlatform =
            fromFileName(path) ?: N3DS
    }
}
