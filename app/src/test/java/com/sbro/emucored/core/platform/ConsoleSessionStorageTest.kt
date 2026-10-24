package com.sbro.emucored.core.platform

import com.sbro.emucored.core.platform.session.ConsoleSessionStorage
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ConsoleSessionStorageTest {
    @Test fun statesUseOriginalIdentityAndCannotCollideAcrossConsolesOrSameNamedGames() {
        val root = File("build/storage-contract")
        val first = ConsoleSessionStorage.stateFile(root, ConsolePlatform.GBA, "content://roms/a/shared.gba", 0)
        assertEquals(first, ConsoleSessionStorage.stateFile(root, ConsolePlatform.GBA, "content://roms/a/shared.gba", 0))
        assertNotEquals(first, ConsoleSessionStorage.stateFile(root, ConsolePlatform.GBA, "content://roms/b/shared.gba", 0))
        assertNotEquals(first.name, ConsoleSessionStorage.stateFile(root, ConsolePlatform.NES, "content://roms/a/shared.gba", 0).name)
        assertNotEquals(first.name, ConsoleSessionStorage.stateFile(root, ConsolePlatform.GBA, "content://roms/a/shared.gba", 1).name)
        assertTrue(first.canonicalPath.startsWith(root.canonicalPath + File.separator))
        assertFalse(first.name.contains("content:"))
    }

    @Test fun slotNumbersAreValidatedInsteadOfSilentlyOverwritingAnotherSlot() {
        for (slot in listOf(-1, 11, Int.MAX_VALUE)) {
            try {
                ConsoleSessionStorage.stateFile(File("build/storage-contract"), ConsolePlatform.GBA, "rom", slot)
                fail("Invalid slot $slot must not map to a user save")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
