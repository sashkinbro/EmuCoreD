package com.sbro.emucored.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PerGameScreenSwapTest {
    @Test fun swapRoundTripsForBothPlatformsWithoutAddingGraphicsOverrides() {
        for (path in listOf("game.nds", "game.3ds")) {
            val original = PerGameSettings(gameKey = path, gameTitle = "Game", providedKeys = setOf("showFps"))
            val swapped = original.withScreenSwap(true)
            val loaded = JSONObject(swapped.toJson().toString()).toPerGameSettings()
            assertTrue(loaded.resolveScreenSwap(false))
            assertEquals(setOf("showFps", "swapScreens"), swapped.providedKeys)
            assertFalse(loaded.withScreenSwap(false).resolveScreenSwap(true))
            assertNull(original.swapScreens)
        }
    }

    @Test fun oldProfilesRetainTheirLegacySwapAndExplicitChoiceWins() {
        val old = PerGameSettings(gameKey = "game.3ds", gameTitle = "Game", coreOptions = mapOf("swap_screen" to "true"))
        assertTrue(old.resolveScreenSwap(false))
        assertFalse(old.withScreenSwap(false).resolveScreenSwap(true))
        assertNull(old.withScreenSwap(false).providedKeys)
        assertTrue(PerGameSettings(gameKey = "other.nds", gameTitle = "Other").resolveScreenSwap(true))
    }
}
