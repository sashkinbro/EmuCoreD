package com.sbro.emucored.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GameItemCoverTitleTest {
    @Test fun dsTriesDescriptiveFileNameBeforeTruncatedHeaderTitle() {
        assertEquals(
            listOf("Super Mario 64 DS (USA).nds", "S.MARIO64DS"),
            GameItem.resolveCoverLookupTitles(
                GamePlatform.NDS,
                "S.MARIO64DS",
                "Super Mario 64 DS (USA).nds"
            )
        )
    }

    @Test fun threeDsTriesHeaderTitleThenFileNameForTruncatedMetadata() {
        assertEquals(
            listOf("Mario & Luigi", "Mario and Luigi Dream Team Bros.3ds"),
            GameItem.resolveCoverLookupTitles(
                GamePlatform.N3DS,
                "Mario & Luigi",
                "Mario and Luigi Dream Team Bros.3ds"
            )
        )
    }

    @Test fun blankCandidatesAreRemovedAndDuplicatesCollapse() {
        assertEquals(
            listOf("Shovel Knight"),
            GameItem.resolveCoverLookupTitles(GamePlatform.N3DS, "Shovel Knight", " ")
        )
    }

    @Test fun otherConsolesTryTheFileNameFirst() {
        assertEquals(
            listOf("Mario Party 7 (USA) (Rev 1).rvz", "Mario Party 7"),
            GameItem.resolveCoverLookupTitles(
                GamePlatform.GAMECUBE,
                "Mario Party 7",
                "Mario Party 7 (USA) (Rev 1).rvz"
            )
        )
    }
}
