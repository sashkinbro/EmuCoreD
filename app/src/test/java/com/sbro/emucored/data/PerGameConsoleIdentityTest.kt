package com.sbro.emucored.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PerGameConsoleIdentityTest {
    @Test fun opaqueProfilesPreserveConsoleAndNormalizeItsOwnRenderer() {
        val profile = PerGameSettings(gameKey = "content://provider/document/opaque-one", gameTitle = "Game",
            gamePlatform = GamePlatform.SNES, renderer = 13)
        val restored = JSONObject(profile.toJson().toString()).toPerGameSettings()
        assertEquals(GamePlatform.SNES, restored.gamePlatform)
        assertEquals(13, restored.renderer)
        val default = PerGameSettings(gameKey = profile.gameKey, gameTitle = "Game", gamePlatform = GamePlatform.N3DS)
        assertEquals(GamePlatform.SNES, restored.resolveAgainst(default).gamePlatform)
    }

    @Test fun legacyAndUnknownFutureMetadataRemainReadable() {
        val legacy = JSONObject().put("gameKey", "/games/old.nds").toPerGameSettings()
        assertEquals(GamePlatform.NDS, legacy.gamePlatform)
        val future = JSONObject().put("gameKey", "content://opaque").put("gamePlatform", "FUTURE").toPerGameSettings()
        assertNull(future.gamePlatform)
    }
}
