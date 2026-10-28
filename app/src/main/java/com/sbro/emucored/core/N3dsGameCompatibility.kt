// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import java.util.Locale

/**
 * Timing defaults for 3DS titles whose dynamic frame rate needs a stable cap.
 *
 * Luigi's Mansion 2 is uncapped on real hardware and normally settles around
 * 30 fps. Without a render-thread delay the emulated GPU looks infinitely fast,
 * so the game keeps pushing frames and the host alternates between full and
 * sub-full speed. The compatibility profile below asks the core to simulate GPU
 * completion timing and caps the game's render thread at its native rate.
 *
 * Values are applied before the core boots and are reset for every other 3DS
 * title, so they never leak between games.
 */
internal object N3dsGameCompatibility {
    data class Defaults(
        val simulateGpuTimings: Boolean = false,
        val delayGameRenderThreadUs: Int = 0
    )

    private data class Profile(
        val serialPrefixes: List<String>,
        val titleMarkers: List<String>,
        val defaults: Defaults
    )

    private val profiles = listOf(
        Profile(
            serialPrefixes = listOf("CTR-P-AGG", "CTR-N-AGG"),
            titleMarkers = listOf(
                "luigi's mansion 2",
                "luigis mansion 2",
                "luigi's mansion: dark moon",
                "dark moon"
            ),
            defaults = Defaults(
                simulateGpuTimings = true,
                delayGameRenderThreadUs = 6_500
            )
        )
    )

    fun defaultsFor(serial: String?, title: String?): Defaults {
        val normalizedSerial = serial?.trim()?.uppercase(Locale.ROOT).orEmpty()
        val normalizedTitle = title
            ?.lowercase(Locale.ROOT)
            ?.replace('\u2018', '\'')
            ?.replace('\u2019', '\'')
            .orEmpty()
        val profile = profiles.firstOrNull { candidate ->
            candidate.serialPrefixes.any(normalizedSerial::startsWith) ||
                candidate.titleMarkers.any(normalizedTitle::contains)
        }
        return profile?.defaults ?: Defaults()
    }
}
