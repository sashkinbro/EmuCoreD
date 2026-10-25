package com.sbro.emucored.ui.textures

import com.sbro.emucored.data.RemoteTexturePack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextureOnlineSelectionTest {
    private fun pack(
        id: String,
        title: String,
        serials: List<String>
    ) = RemoteTexturePack(
        id = id,
        name = "$title HD",
        gameTitle = title,
        serials = serials,
        version = "1.0",
        authors = listOf("Author"),
        credits = "",
        description = "",
        downloadUrl = "https://example.com/$id.zip",
        sourceUrl = "https://example.com/$id",
        license = "CC0",
        sizeBytes = 1,
        sha256 = "0".repeat(64),
        fileCount = 1,
        previewUrls = emptyList()
    )

    @Test fun matchingSerialAndOtherVersionsAreSeparated() {
        val matching = pack("match", "Animal Crossing: Wild World", listOf("ADME"))
        val otherVersion = pack("other", "Animal Crossing: Wild World", listOf("ADMP"))
        val unrelated = pack("unrelated", "Mario Kart DS", listOf("AMCE"))

        val selection = selectTextureOnlinePacks(
            packs = listOf(unrelated, otherVersion, matching),
            serial = "adme",
            gameTitle = "Animal Crossing: Wild World"
        )

        assertEquals(listOf(matching), selection.matchingPacks)
        assertEquals(listOf(otherVersion), selection.otherVersionPacks)
        assertTrue(unrelated !in selection.matchingPacks)
        assertTrue(unrelated !in selection.otherVersionPacks)
    }

    @Test fun sameTitleStaysVisibleWhenTheSerialIsUnknown() {
        val otherVersion = pack("other", "Animal Crossing: Wild World", listOf("ADMP"))

        val selection = selectTextureOnlinePacks(
            packs = listOf(otherVersion),
            serial = null,
            gameTitle = "Animal Crossing: Wild World"
        )

        assertTrue(selection.matchingPacks.isEmpty())
        assertEquals(listOf(otherVersion), selection.otherVersionPacks)
    }
}
