package com.sbro.emucored.ui.cheats

import androidx.compose.runtime.saveable.SaverScope
import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.SelectedGameIdentity
import com.sbro.emucored.data.cheats.TypedCheatCatalog
import org.junit.Assert.*
import org.junit.Test

class PendingCheatImportTest {
    private val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
    private fun restore(import: PendingCheatImport) = PendingCheatImport.Saver.restore(
        requireNotNull(with(PendingCheatImport.Saver) { scope.save(import) }))!!
    private fun restore(guard: CheatSelectionGuard) = CheatSelectionGuard.Saver.restore(
        requireNotNull(with(CheatSelectionGuard.Saver) { scope.save(guard) }))!!

    @Test fun pickerRecreationRetainsOriginalTypedTargetAndEpoch() {
        val guard = CheatSelectionGuard()
        val game = GameItem("Original", "content://games/alpha", "alpha.gba", 12, 0, platform = GamePlatform.GBA)
        val pending = PendingCheatImport.capture(game, SelectedGameIdentity(null, null), guard.select(game.path))
        val returned = restore(pending)
        val restoredGuard = restore(guard)
        assertEquals(TypedCheatCatalog.gameKey(game.platform, game.path),
            TypedCheatCatalog.gameKey(returned.game.platform, returned.game.path))
        assertTrue(restoredGuard.isCurrent(returned.request))
        restoredGuard.select("content://games/beta")
        restoredGuard.select(game.path)
        assertFalse(restoredGuard.publish(returned.request) { fail("Restored stale import published") })
        assertEquals(game.path, returned.game.path)
    }

    @Test fun pickerRecreationRetainsResolved3dsKeyInsteadOfUsingAnotherGamesIdentity() {
        val guard = CheatSelectionGuard()
        val game = GameItem("Original", "/roms/a.3ds", "a.3ds", 1, 0, serial = "fallback")
        val returned = restore(PendingCheatImport.capture(game,
            SelectedGameIdentity("CTR-ORIGINAL", "1234ABCD"), guard.select(game.path)))
        assertEquals("CTR-ORIGINAL", returned.identity.serial)
        assertEquals("1234ABCD", returned.identity.crc)
        assertEquals("fallback", returned.game.serial)
        assertEquals(GamePlatform.N3DS, returned.game.platform)
    }
}
