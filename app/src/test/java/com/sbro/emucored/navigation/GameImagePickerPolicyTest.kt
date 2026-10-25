package com.sbro.emucored.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameImagePickerPolicyTest {
    @Test fun documentPickerAcceptsImagesFromEveryConsole() {
        listOf("game.3ds", "game.NDS", "game.gcm", "game.rvz", "game.wad",
            "game.gba", "game.gb", "game.gbc", "game.nes", "game.sfc",
            "homebrew.dol", "homebrew.elf").forEach {
            assertTrue("The document picker rejected a supported game: $it", isSupportedGameImage(it))
        }
    }

    @Test fun documentPickerRejectsUnrelatedFilesAndDiscSidecars() {
        listOf("", "cover.png", "archive.zip", "game.wbf1", "game.part1.iso",
            "playlist.m3u", "track.cue", "track.bin").forEach {
            assertFalse("The document picker admitted an unsupported entry: $it", isSupportedGameImage(it))
        }
    }
}
