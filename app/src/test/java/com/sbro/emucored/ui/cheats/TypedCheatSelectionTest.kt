package com.sbro.emucored.ui.cheats

import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.cheats.CheatGameIdentity
import com.sbro.emucored.data.cheats.TypedCheatIdentity
import com.sbro.emucored.data.cheats.TypedCheatPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedCheatSelectionTest {
    private val identity = CheatGameIdentity(
        platform = GamePlatform.NES,
        sha1 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        sizeBytes = 1024L
    )

    private fun pack(
        id: String,
        title: String,
        sha1: String
    ) = TypedCheatPack(
        id = id,
        platform = GamePlatform.NES,
        title = title,
        path = "platforms/nes/packs/$id.json",
        sha256 = "0".repeat(64),
        sizeBytes = 1,
        blockCount = 1,
        formats = setOf("b"),
        identities = listOf(TypedCheatIdentity(sha1 = sha1, sizeBytes = 1024L))
    )

    @Test fun nonExactRevisionsStayAvailableForManualConfirmation() {
        val matching = pack("match", "Super Mario Bros", identity.sha1!!)
        val otherRevision = pack("other", "Super Mario Bros", "B".repeat(40))
        val unrelated = pack("unrelated", "Metroid", "C".repeat(40))

        val selection = selectTypedCheatPacks(
            packs = listOf(unrelated, otherRevision, matching),
            identity = identity,
            gameTitle = "Super Mario Bros"
        )

        assertEquals(listOf(matching), selection.matchingPacks)
        assertEquals(listOf(otherRevision), selection.otherVersionPacks)
        assertTrue(unrelated !in selection.matchingPacks)
        assertTrue(unrelated !in selection.otherVersionPacks)
    }

    @Test fun identicalTitleIsStillOfferedWhenTheIdentityIsNotResolved() {
        val pack = pack("other", "Super Mario Bros", "B".repeat(40))

        val selection = selectTypedCheatPacks(
            packs = listOf(pack),
            identity = null,
            gameTitle = "Super Mario Bros"
        )

        assertTrue(selection.matchingPacks.isEmpty())
        assertEquals(listOf(pack), selection.otherVersionPacks)
    }
}
