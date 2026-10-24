package com.sbro.emucored.core.platform

import com.sbro.emucored.data.GamePlatform
import org.junit.Assert.*
import org.junit.Test

class GamePlatformDiscoveryTest {
    @Test fun everyUnambiguousCartridgeFormatKeepsItsConsoleIdentity() {
        val cases = mapOf("test.3DS" to "N3DS", "test.nds" to "NDS", "test.GBA" to "GBA",
            "test.gb" to "GB", "test.cgb" to "GBC", "test.unif" to "NES",
            "test.sfc" to "SNES", "test.gcm" to "GAMECUBE", "test.wbfs" to "WII")
        cases.forEach { (name, expected) -> assertEquals(name, expected, GamePlatform.fromFileName(name)?.name) }
    }

    @Test fun ambiguousDiscContainersAreCandidatesWithoutGuessingTheConsole() {
        listOf("game.rvz", "game.iso", "game.wia", "game.gcz", "game.ciso").forEach {
            assertTrue("Scanner omitted $it", GamePlatform.isSupportedName(it))
            assertNull("A filename cannot distinguish GC from Wii", GamePlatform.fromFileName(it))
        }
        assertFalse(GamePlatform.isSupportedName("game.txt"))
    }
}
