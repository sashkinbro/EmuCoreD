package com.sbro.emucored.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucored.core.testStorageContext
import com.sbro.emucored.data.cheats.TypedCheatCatalog
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual imported files and enabled-state persistence in isolated storage. */
@RunWith(AndroidJUnit4::class)
class TypedCheatImportStateInstrumentedTest {
    private val original = CheatBlock(
        id = "first-pack_1", title = "Lives", lines = listOf("010315DA"),
        enabled = false, format = "gb-gameshark", sourcePackId = "first-pack"
    )

    @Test fun mergingAnotherPackWithIdenticalCodeNeverEnablesItsNewId() {
        exercise(
            original.copy(id = "second-pack_1", sourcePackId = "second-pack"),
            merge = true, expectedEnabled = setOf(original.id)
        )
    }

    @Test fun replacementWithIdenticalCodeUnderANewIdStartsDisabled() {
        exercise(original.copy(id = "second-pack_1", sourcePackId = "second-pack"),
            merge = false, expectedEnabled = emptySet())
    }

    @Test fun reimportingTheSameUnchangedBlockPreservesItsEnabledState() {
        exercise(original, merge = true, expectedEnabled = setOf(original.id))
    }

    @Test fun changingCodeOrFormatUnderTheSameIdDisablesTheBlock() {
        exercise(original.copy(lines = listOf("010415DA")), merge = false, expectedEnabled = emptySet())
        exercise(original.copy(format = "gb-game-genie"), merge = false, expectedEnabled = emptySet())
    }

    @Test fun mergingAFormatChangeReplacesTheOldBlockAndStartsDisabled() {
        exercise(original.copy(format = "gb-game-genie"), merge = true, expectedEnabled = emptySet())
    }

    private fun exercise(imported: CheatBlock, merge: Boolean, expectedEnabled: Set<String>) {
        testStorageContext().use { context ->
            val repository = CheatRepository(context)
            val key = TypedCheatCatalog.gameKey(GamePlatform.GB, "/original-test.gb")
            assertEquals(1, repository.importCheatFile(key, TypedCheatCatalog.serialize(listOf(original))))
            repository.setBlockEnabled(key, original.id, true)
            assertEquals(setOf(original.id), enabledIds(CheatRepository(context), key))

            // Downloaded enabled flags must not grant permission to enable a code.
            val expectedCount = if (merge && imported.id != original.id) 2 else 1
            assertEquals(expectedCount, repository.importCheatFile(
                key, TypedCheatCatalog.serialize(listOf(imported.copy(enabled = true))), mergeWithExisting = merge))

            // Read through a fresh repository to verify durable state, not local objects.
            assertEquals(expectedEnabled, enabledIds(CheatRepository(context), key))
        }
    }

    private fun enabledIds(repository: CheatRepository, key: String): Set<String> =
        requireNotNull(repository.getGameConfig(key, "", null)).blocks
            .filter { it.enabled }.map { it.id }.toSet()
}
