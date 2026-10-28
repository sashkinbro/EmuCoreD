package com.sbro.emucored.data

import org.junit.Assert.*
import org.junit.Test

class ProfilePlatformsTest {
    @Test fun everyConsoleHasItsOwnProfileFilter() {
        assertEquals(GamePlatform.entries.map { it.name }.toSet() + setOf("ALL", "UNKNOWN"),
            ProfilePlatformFilter.entries.map { it.name }.toSet())
    }

    @Test fun allConsoleIdentitiesRoundTripAndFilterWithoutChangingLegacyFields() {
        val names = GamePlatform.entries.map { it.name }
        val games = names.mapIndexed { index, name ->
            val platform = GamePlatform.valueOf(name)
            val record = mutableMapOf<String, Any>("s" to "IPKE", "ms" to index.toLong())
            writeProfilePlatform(record, platform)
            assertEquals(platform, readProfilePlatform(record))
            assertEquals("IPKE", record["s"])
            assertEquals(index.toLong(), record["ms"])
            game(name, "IPKE", (index + 1) * 100L).copy(platform = platform)
        }.reversed()
        for (name in names) {
            val platform = GamePlatform.valueOf(name)
            assertEquals(games.filter { it.platform == platform },
                games.filterProfilePlatform(ProfilePlatformFilter.valueOf(name)))
        }
        assertTrue(games.filterProfilePlatform(ProfilePlatformFilter.UNKNOWN).isEmpty())
    }

    @Test fun breakdownSubtractsEveryKnownConsoleBeforeAssigningUnclassifiedRemainder() {
        val games = listOf("N3DS", "NDS", "GAMECUBE", "WII", "GBA", "GB", "GBC", "NES", "SNES")
            .mapIndexed { index, name -> game(name, null, (index + 1) * 100L)
                .copy(platform = GamePlatform.valueOf(name)) }
        val result = profilePlatformBreakdown(games + games.first(), 11, 5_000L, complete = true)
        assertEquals(ProfilePlatformTotal(2, 500L), result.unknown)
        for (entry in games) assertEquals(ProfilePlatformTotal(1, entry.totalPlayTimeMs), result[entry.platform])
        assertEquals(11, result.platforms.values.sumOf { it.games } + result.unknown.games)
        assertEquals(5_000L, result.platforms.values.sumOf { it.playTimeMs } + result.unknown.playTimeMs)
        val partial = profilePlatformBreakdown(games.takeLast(2), 11, 5_000L, complete = false)
        assertEquals(ProfilePlatformTotal(), partial.unknown)
        assertEquals(2, partial.platforms.values.sumOf { it.games })
    }

    @Test fun explicitUnrecognizedPlatformIsNeverReclassifiedAsLegacyDsFromSerial() {
        assertNull(readProfilePlatform(mapOf("p" to "FUTURE_CONSOLE", "s" to "IPKE")))
        assertEquals(GamePlatform.NDS, readProfilePlatform(mapOf("s" to "IPKE")))
    }

    @Test fun newConsoleProfileKeysDoNotCollideAndLegacyKeysStayStable() {
        assertEquals("IPKE", profileGameKey("ipke", "/games/shared", GamePlatform.NDS))
        assertEquals("CTR-P-ABCE", profileGameKey("CTR-P-ABCE", "/games/shared", GamePlatform.N3DS))
        assertEquals(profileGameKey(null, "/games/shared", null),
            profileGameKey(null, "/games/shared", GamePlatform.NDS))
        val keys = listOf("GAMECUBE", "WII", "GBA", "GB", "GBC", "NES", "SNES")
            .map { profileGameKey("SAME", "/games/shared", GamePlatform.valueOf(it)) }
        assertEquals(7, keys.toSet().size)
        assertFalse("SAME" in keys)
        val entries = listOf("GAMECUBE", "WII").map {
            PlayerPlayTimeDelta("/games/shared.rvz", "Shared", "SAME", null, 10L,
                platform = GamePlatform.valueOf(it))
        }
        assertNotEquals(profilePlayTimeCacheKey(entries[0]), profilePlayTimeCacheKey(entries[1]))
    }

    private fun game(key: String, serial: String?, time: Long) = PlayerGamePlayStat(
        gameKey = key, title = "A shared title", serial = serial, coverArtPath = null,
        totalPlayTimeMs = time, sessions = 1, lastPlayedAtMs = null
    )

    @Test fun legacyPlatformsUseStrongSerialFormsAndNeverTitlesOrHashedPaths() {
        for (serial in listOf("IPKE", "NTR-P-IPKE", "TWL-KAUE"))
            assertEquals(GamePlatform.NDS, resolveProfilePlatform(serial))
        for (serial in listOf("CTR-P-ABCE", "KTR-N-CBRE", "000400000F700100"))
            assertEquals(GamePlatform.N3DS, resolveProfilePlatform(serial))
        for (serial in listOf(null, "", "P_49a0dcfe17771e27", "Mario.nds", "unrecognized"))
            assertNull(resolveProfilePlatform(serial))
    }

    @Test fun realPathsProvidePlatformIncludingEncodedSafWithoutDefaultingOpaquePathsTo3ds() {
        assertEquals(GamePlatform.NDS, resolveProfilePlatform(null, "content://provider/document/Game%2Ends"))
        assertEquals(GamePlatform.NDS, resolveProfilePlatform(null, "/games/homebrew.srl"))
        assertEquals(GamePlatform.N3DS, resolveProfilePlatform(null, "/games/homebrew.3dsx"))
        assertNull(resolveProfilePlatform(null, "content://provider/document/123"))
        assertNull(resolveProfilePlatform(null, "/games/unknown"))
    }

    @Test fun nullablePlatformRoundTripsWithoutChangingLegacyRecordFields() {
        val record = mutableMapOf<String, Any>("t" to "Shared title", "ms" to 123L, "s" to "IPKE")
        assertEquals(GamePlatform.NDS, readProfilePlatform(record))
        writeProfilePlatform(record, GamePlatform.N3DS)
        assertEquals("N3DS", record[PROFILE_GAME_PLATFORM_KEY])
        assertEquals(GamePlatform.N3DS, readProfilePlatform(record))
        assertEquals(123L, record["ms"])
        writeProfilePlatform(record, null)
        assertFalse(record.containsKey(PROFILE_GAME_PLATFORM_KEY))
        assertEquals(GamePlatform.NDS, readProfilePlatform(record))
        assertNull(readProfilePlatform(mapOf("p" to "unknown", "t" to "Game.nds")))
    }

    @Test fun explicitPlatformKeepsSeriallessHomebrewClassifiedAfterLoading() {
        val homebrew = game("P_49a0dcfe17771e27", null, 4_000).copy(platform = GamePlatform.NDS)
        assertEquals(GamePlatform.NDS, homebrew.profilePlatform())
        assertEquals(listOf(homebrew), listOf(homebrew).filterProfilePlatform(ProfilePlatformFilter.NDS))
        assertTrue(listOf(homebrew).filterProfilePlatform(ProfilePlatformFilter.UNKNOWN).isEmpty())
    }

    @Test fun completeBreakdownPreservesTotalsAndKeepsUnknownDataExplicit() {
        val games = listOf(game("ds", "IPKE", 5_000), game("3ds", "CTR-P-ABCE", 7_000), game("P_hash", null, 3_000))
        val result = profilePlatformBreakdown(games, 5, 20_000, complete = true)
        assertEquals(ProfilePlatformTotal(1, 5_000), result.ds)
        assertEquals(ProfilePlatformTotal(1, 7_000), result.threeDs)
        assertEquals(ProfilePlatformTotal(3, 8_000), result.unknown)
        assertEquals(5, result.ds.games + result.threeDs.games + result.unknown.games)
        assertEquals(20_000L, result.ds.playTimeMs + result.threeDs.playTimeMs + result.unknown.playTimeMs)
    }

    @Test fun partialPublicPageDoesNotAssignUnloadedGamesOrTimeToUnknown() {
        val result = profilePlatformBreakdown(listOf(game("ds", "IPKE", 5_000)), 12, 999_000, complete = false)
        assertEquals(ProfilePlatformTotal(1, 5_000), result.ds)
        assertEquals(ProfilePlatformTotal(), result.threeDs)
        assertEquals(ProfilePlatformTotal(), result.unknown)
    }

    @Test fun platformFiltersKeepOrderAndDoNotClassifyBySharedGameTitle() {
        val ds = game("ds", "IPKE", 5_000)
        val threeDs = game("3ds", "CTR-P-ABCE", 7_000)
        val unknown = game("P_hash", null, 3_000)
        val games = listOf(threeDs, unknown, ds)
        assertEquals(games, games.filterProfilePlatform(ProfilePlatformFilter.ALL))
        assertEquals(listOf(ds), games.filterProfilePlatform(ProfilePlatformFilter.NDS))
        assertEquals(listOf(threeDs), games.filterProfilePlatform(ProfilePlatformFilter.N3DS))
        assertEquals(listOf(unknown), games.filterProfilePlatform(ProfilePlatformFilter.UNKNOWN))
    }
}
