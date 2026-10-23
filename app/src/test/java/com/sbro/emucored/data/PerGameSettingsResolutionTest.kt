package com.sbro.emucored.data

import org.junit.Assert.*
import org.junit.Test

class PerGameSettingsResolutionTest {
    @Test fun managerEditRetainsExplicitSwapWithoutAddingUnrelatedOverrides() {
        for (path in listOf("game.nds", "game.3ds")) {
            for (swapped in listOf(false, true)) {
                val defaults = PerGameSettings(gameKey = path, gameTitle = "Game", renderer = 14)
                val saved = defaults.copy(providedKeys = emptySet()).withScreenSwap(swapped)
                val managerEdited = saved.resolveAgainst(defaults).copy(showFps = true, providedKeys = null)
                val reloaded = managerEdited.toJson().toPerGameSettings()
                assertEquals(swapped, reloaded.swapScreens)
                assertEquals(swapped, reloaded.resolveScreenSwap(!swapped))
                assertEquals(14, reloaded.renderer)
            }
        }
    }

    @Test fun managerEditRetainsLegacySwapAndKeepsMissingChoiceInherited() {
        val defaults = PerGameSettings(gameKey = "game.3ds", gameTitle = "Game")
        val legacy = defaults.copy(coreOptions = mapOf("swap_screen" to "true"), providedKeys = setOf("coreOptions"))
        assertTrue(legacy.resolveAgainst(defaults).copy(showFps = true).resolveScreenSwap(false))
        assertNull(defaults.copy(providedKeys = emptySet()).resolveAgainst(defaults).swapScreens)
    }
}
