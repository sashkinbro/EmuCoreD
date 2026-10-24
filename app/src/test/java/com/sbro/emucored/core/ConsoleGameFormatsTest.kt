package com.sbro.emucored.core

import com.sbro.emucored.core.platform.ConsolePlatform
import org.junit.Assert.*
import org.junit.Test

class ConsoleGameFormatsTest {
    @Test fun registryDrivesDiscoveryAndSetupForEveryConsole() {
        for (platform in ConsolePlatform.entries) for (extension in ConsoleGameFormats.profile(platform).extensions) {
            val name = "Original GAME.${extension.uppercase(java.util.Locale.ROOT)}"
            assertTrue("$platform/$name", platform in ConsolePlatform.candidatesForFileName(name))
            assertEquals(SetupValidator.DocumentEntryKind.GAME_FILE, SetupValidator.classifyDocumentEntry(null, name))
            assertEquals(SetupValidator.DocumentEntryKind.GAME_FILE,
                SetupValidator.classifyDocumentEntry("application/octet-stream", name))
        }
        assertEquals(9, ConsolePlatform.entries.size)
    }

    @Test fun compressedCiaUsesInstallerAndCompressedCartridgeRemainsThreeDs() {
        assertTrue(N3dsGameFormats.isCiaFile("content/game.ZCIA"))
        assertTrue(N3dsGameFormats.isCiaFile("game.CIA"))
        assertFalse(N3dsGameFormats.isCiaFile("game.zcci"))
        assertEquals(setOf(ConsolePlatform.N3DS), ConsoleGameFormats.candidatesForFileName("game.z3ds"))
    }

    @Test fun sharedFormatsPreserveAmbiguityAndArchivesAreNotMislabelledAsGames() {
        assertEquals(setOf(ConsolePlatform.GAMECUBE, ConsolePlatform.WII), ConsoleGameFormats.candidatesForFileName("game.RVZ"))
        assertEquals(setOf(ConsolePlatform.N3DS, ConsolePlatform.GAMECUBE, ConsolePlatform.WII),
            ConsoleGameFormats.candidatesForFileName("original.ELF"))
        for (name in listOf("game.zip", "game.7z", "game.rar", "game.nes.png", "disksys.rom", "")) {
            assertFalse(name, ConsoleGameFormats.isSupportedName(name))
            assertEquals(SetupValidator.DocumentEntryKind.OTHER,
                SetupValidator.classifyDocumentEntry("application/octet-stream", name))
        }
        assertEquals(SetupValidator.DocumentEntryKind.UNKNOWN, SetupValidator.classifyDocumentEntry(null, "folder"))
        assertEquals(SetupValidator.DocumentEntryKind.DIRECTORY,
            SetupValidator.classifyDocumentEntry("vnd.android.document/directory", "folder.gba"))
    }
}
