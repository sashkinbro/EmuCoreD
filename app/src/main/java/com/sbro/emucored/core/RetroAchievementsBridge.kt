// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

/**
 * RetroAchievements bridge.
 *
 * The rcheevos client lives in the EmuCoreD frontend (there is no libretro
 * memory map any more), so EmuCoreD owns the whole client lifecycle: login,
 * hashing and the achievement runtime. It is driven from the Kotlin side and
 * reports JSON state the way the settings and in-game screens expect.
 *
 * TODO(native): wire the rcheevos client to the core's own guest memory.
 * Until then every call degrades to "signed out", so the UI stays honest
 * instead of showing stale data.
 */
object RetroAchievementsBridge {
    @Volatile
    private var enabledFlag: Boolean = false

    @Volatile
    private var hardcoreFlag: Boolean = false

    @Volatile
    private var unofficialFlag: Boolean = false

    @Volatile
    private var encoreFlag: Boolean = false

    val enabled: Boolean get() = enabledFlag
    val hardcore: Boolean get() = hardcoreFlag
    val unofficial: Boolean get() = unofficialFlag
    val encore: Boolean get() = encoreFlag

    fun updateEnabled(value: Boolean) {
        enabledFlag = value
    }

    fun updateHardcore(value: Boolean) {
        hardcoreFlag = value
    }

    fun updateUnofficial(value: Boolean) {
        unofficialFlag = value
    }

    fun updateEncore(value: Boolean) {
        encoreFlag = value
    }

    fun loginWithPassword(username: String, password: String): String? = null

    fun loginWithToken(username: String, token: String): String? = null

    fun logout() = Unit

    fun loadGame(path: String) = Unit

    fun unloadGame() = Unit

    fun pump() = Unit

    fun stateJson(): String = "{}"

    fun achievementsJson(): String = "{}"

    fun pollEventsJson(): String = "[]"
}
