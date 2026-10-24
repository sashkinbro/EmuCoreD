package com.sbro.emucored.data.drive

import org.junit.Assert.*
import org.junit.Test

class ConsoleBackupPathsTest {
    private val hash = "a".repeat(64)
    @Test fun includesRealUserStatesBatteriesAndDolphinGameSaves() {
        listOf("console-states/wii/$hash/wii_${hash}_slot0.ecs",
            "console-states/gba/$hash/gba_${hash}_slot10.ecs.png",
            "console-data/gb/$hash/battery.sav", "console-data/nes/$hash/battery.sav.fds.ups",
            "console-data/snes/$hash/game.srm", "ds-states/Contra_4/slot1.dss.json",
            "ds-saves/Contra_4.sav", "console-dolphin/GC/MemoryCardA.USA.raw",
            "console-dolphin/GC/USA/Card A/01-GAME.gci", "console-dolphin/Wii/title/00010000/524d4745/data/save.bin")
            .forEach { assertTrue(it, ConsoleBackupPaths.allowed(it)) }
        assertEquals("save-states", ConsoleBackupPaths.category("console-states"))
        assertEquals("memory-cards", ConsoleBackupPaths.category("console-dolphin"))
    }

    @Test fun excludesFirmwareKeysRomsCachesAndInvalidStateNamespaces() {
        listOf("console-data/firmware/disksys.rom", "console-data/gba/$hash/game.gba",
            "console-dolphin/GC/IPL.bin", "console-dolphin/Wii/keys.bin",
            "console-dolphin/Wii/title/00000001/00000002/data/firmware.bin",
            "console-dolphin/Wii/title/00010000/524d4745/content/title.app",
            "console-dolphin/Cache/state.raw", "ds-states/../slot1.dss",
            "console-states/gba/$hash/nes_${hash}_slot1.ecs", "console-states/gba/$hash/gba_${hash}_slot11.ecs",
            "console-states/gba/$hash/../../escape.ecs", "console-data/gb/$hash/.battery.sav")
            .forEach { assertFalse(it, ConsoleBackupPaths.allowed(it)) }
    }
}
