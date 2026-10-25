package com.sbro.emucored.ui.textures

import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.textures.TexturePackTarget
import org.junit.Assert.*
import org.junit.Test

class TexturePickerSelectionTest {
    @Test fun restoredDocumentCallbackRetainsTheRequestedGameAndVerifiedTarget() {
        val selected = TextureGameSelection(GameItem("Original title", "content://provider/game/42", "game.iso", 1, 0,
            platform = GamePlatform.GAMECUBE), TexturePackTarget(ConsolePlatform.GAMECUBE, "GTSE01"))
        val restored = requireNotNull(restoreTextureSelection(saveTextureSelection(selected)))
        assertEquals(selected.game.path, restored.game.path)
        assertEquals(selected.game.platform, restored.game.platform)
        assertEquals(selected.target, restored.target)
    }

    @Test fun malformedOrUnsupportedSavedRequestsNeverSelectAnotherGame() {
        for (request in listOf(emptyList(), listOf("path", "NDS", "Game", "ABCD"),
            listOf("path", "WII", "Game", "../x"), listOf("path", "unknown", "Game", "RMGE01")))
            assertNull(restoreTextureSelection(request))
    }
}
