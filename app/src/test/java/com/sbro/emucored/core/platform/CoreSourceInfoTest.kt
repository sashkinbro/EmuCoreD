package com.sbro.emucored.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreSourceInfoTest {
    @Test fun everyEngineHasOneOfficialSourceAndItsOwnConsoles() {
        val sources = CoreId.entries.map(CoreSourceInfo::forCore)
        assertEquals(CoreId.entries.size, sources.map { it.repositoryUrl }.toSet().size)
        assertTrue(sources.all { it.repositoryUrl.startsWith("https://github.com/") })
        assertEquals(listOf(ConsolePlatform.GAMECUBE, ConsolePlatform.WII), CoreSourceInfo.forCore(CoreId.DOLPHIN).platforms)
        assertEquals(listOf(ConsolePlatform.GB, ConsolePlatform.GBC), CoreSourceInfo.forCore(CoreId.SAMEBOY).platforms)
        assertTrue(CoreSourceInfo.forCore(CoreId.AZAHAR).isIntegrated)
        assertTrue(CoreSourceInfo.forCore(CoreId.MELONDS).isIntegrated)
        assertFalse(CoreSourceInfo.forCore(CoreId.DOLPHIN).isIntegrated)
        assertFalse(CoreSourceInfo.forCore(CoreId.MGBA).isIntegrated)
        assertFalse(CoreSourceInfo.forCore(CoreId.SAMEBOY).isIntegrated)
    }
}
