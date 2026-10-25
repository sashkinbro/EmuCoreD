package com.sbro.emucored.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreSourceInfoTest {
    @Test fun everyEngineHasOneOfficialSourceAndItsOwnConsoles() {
        val sources = CoreId.entries.map(CoreSourceInfo::forCore)
        assertEquals(CoreId.entries.size, sources.map { it.repositoryUrl }.toSet().size)
        assertTrue(sources.all { it.repositoryUrl.startsWith("https://github.com/") || it.repositoryUrl.startsWith("https://gitlab.com/") })
        assertEquals("https://gitlab.com/jgemu/nestopia", CoreSourceInfo.forCore(CoreId.NESTOPIA).repositoryUrl)
        assertEquals("GPL-2.0-or-later", CoreSourceInfo.forCore(CoreId.NESTOPIA).licenseFamily)
        assertEquals(listOf(ConsolePlatform.NES), CoreSourceInfo.forCore(CoreId.NESTOPIA).platforms)
        assertEquals(listOf(ConsolePlatform.SNES), CoreSourceInfo.forCore(CoreId.BSNES).platforms)
        assertEquals(listOf(ConsolePlatform.GAMECUBE, ConsolePlatform.WII), CoreSourceInfo.forCore(CoreId.DOLPHIN).platforms)
        assertEquals(listOf(ConsolePlatform.GB, ConsolePlatform.GBC), CoreSourceInfo.forCore(CoreId.SAMEBOY).platforms)
        assertTrue(CoreSourceInfo.forCore(CoreId.AZAHAR).isIntegrated)
        assertTrue(CoreSourceInfo.forCore(CoreId.MELONDS).isIntegrated)
        assertTrue("Every packaged engine passed the nine-console frontend matrix", sources.all { it.isIntegrated })
    }
}
