package com.sbro.emucored.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PerGameRendererPersistenceTest {
    @Test fun dsSoftwareSurvivesProfileSerializationAndReload() {
        for (path in listOf("/games/game.nds", "content://provider/document/primary%3Agames%2Fgame%2Ends")) {
            val profile = PerGameSettings(gameKey = path, gameTitle = "DS", renderer = 13)
            val json = profile.toJson()
            assertEquals(13, json.getInt("renderer"))
            assertEquals(13, JSONObject(json.toString()).toPerGameSettings().renderer)
        }
    }

    @Test fun unsupportedThreeDsSoftwareIsNormalizedOnSaveAndImport() {
        val profile = PerGameSettings(gameKey = "/games/game.3ds", gameTitle = "3DS", renderer = 13)
        assertEquals(14, profile.toJson().getInt("renderer"))
        val imported = JSONObject().put("gameKey", "/games/game.3ds").put("renderer", 13)
        assertEquals(14, imported.toPerGameSettings().renderer)
    }
}
