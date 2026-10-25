package com.sbro.emucored.data.textures

import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.data.GamePlatform
import org.junit.Assert.*
import org.junit.Test

class TexturePackIdentityTest {
    @Test fun onlyTheThreeCoresWithNativeReplacementSupportAreOffered() {
        assertEquals(setOf(GamePlatform.N3DS, GamePlatform.GAMECUBE, GamePlatform.WII),
            GamePlatform.entries.filter(TexturePackIdentity::supports).toSet())
    }

    @Test fun threeDsRequiresANonzeroNativeTitleIdNotAProductCodeOrFilename() {
        assertNull(TexturePackIdentity.fromNativeTitleId(0))
        assertEquals(TexturePackTarget(ConsolePlatform.N3DS, "0004000001234500"),
            TexturePackIdentity.fromNativeTitleId(0x0004000001234500L))
    }

    @Test fun dolphinRequiresTheActualProbedConsoleAndFullGameId() {
        assertEquals(TexturePackTarget(ConsolePlatform.GAMECUBE, "GM8E01"),
            TexturePackIdentity.fromDolphin(GamePlatform.GAMECUBE, ConsolePlatform.GAMECUBE, "GM8E01"))
        assertNull(TexturePackIdentity.fromDolphin(GamePlatform.WII, ConsolePlatform.GAMECUBE, "GM8E01"))
        assertNull(TexturePackIdentity.fromDolphin(GamePlatform.GAMECUBE, ConsolePlatform.WII, "RMGE01"))
        assertNull(TexturePackIdentity.fromDolphin(GamePlatform.NDS, ConsolePlatform.WII, "RMGE01"))
        for (id in listOf("RMG", "", "../../x", "000000", "0001000148414241"))
            assertNull(TexturePackIdentity.fromDolphin(GamePlatform.WII, ConsolePlatform.WII, id))
    }
}
