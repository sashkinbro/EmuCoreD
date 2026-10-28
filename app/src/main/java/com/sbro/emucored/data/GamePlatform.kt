// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.data

import com.sbro.emucored.core.ArchiveGameIdentity
import com.sbro.emucored.core.N3dsGameFormats
import com.sbro.emucored.core.platform.ConsolePlatform
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class GamePlatform {
    N3DS,
    NDS,
    GAMECUBE,
    WII,
    GBA,
    GB,
    GBC,
    NES,
    SNES;

    val console: ConsolePlatform get() = ConsolePlatform.valueOf(name)

    companion object {
        private val discovered = ConcurrentHashMap<String, GamePlatform>()

        fun fromFileName(name: String): GamePlatform? {
            // ELF remains the established Azahar homebrew route; compressed
            // disc containers require an actual volume probe during discovery.
            if (name.substringAfterLast('.').equals("elf", true)) return N3DS
            val candidate = ConsolePlatform.candidatesForFileName(name).singleOrNull() ?: return null
            return valueOf(candidate.name)
        }

        fun isSupportedName(name: String): Boolean = ConsolePlatform.candidatesForFileName(name).isNotEmpty()

        internal fun isSupportedArchiveEntry(name: String): Boolean =
            fromFileName(name) in setOf(GBA, GB, GBC, NES, SNES)

        fun remember(path: String, platform: GamePlatform) { discovered[path] = platform }

        /** Scanner/cache evidence takes precedence over a potentially ambiguous extension. */
        fun forPath(path: String): GamePlatform? = discovered[path] ?: ArchiveGameIdentity.parse(path)?.let {
            fromFileName(it.entry)?.takeIf { _ -> isSupportedArchiveEntry(it.entry) }
        } ?: fromFileName(path)

        fun fromPath(path: String): GamePlatform =
            requireNotNull(forPath(path)) { "Game console has not been identified" }
    }
}
