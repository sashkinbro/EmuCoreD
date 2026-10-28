package com.sbro.emucored.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import com.sbro.emucored.data.GamePlatform

class ArchiveGameIdentityTest {
    @Test fun contentUriAndUnicodeEntryRoundTripAndRemainDistinct() {
        val first = ArchiveGameIdentity("content://com.example/tree/primary%3ADS/document/primary%3ADS%2Fpack.zip", "日本/гра.sfc")
        val second = first.copy(entry = "日本/інша.sfc")
        assertEquals(first, ArchiveGameIdentity.parse(first.encode()))
        assertEquals(second, ArchiveGameIdentity.parse(second.encode()))
        assertNotEquals(first.encode(), second.encode())
    }

    @Test fun invalidUnicodeCannotSilentlyChangeTheIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveGameIdentity("/games/pack.zip", "broken\uD800.sfc").encode()
        }
    }
    @Test fun encodedIdentityNeverExceedsWhatTheParserAccepts() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveGameIdentity("/games/pack.zip", "x".repeat(30_000) + ".sfc").encode()
        }
    }
    @Test fun cartridgePlatformCanBeRecoveredFromPersistedArchiveIdentity() {
        val identity = ArchiveGameIdentity("content://documents/pack.zip", "SNES/Donkey Kong.sfc").encode()
        assertEquals(GamePlatform.SNES, GamePlatform.forPath(identity))
    }
    @Test fun unrelatedPathsAndMalformedIdentitiesAreNotAccepted() {
        assertNull(ArchiveGameIdentity.parse("/games/example.sfc"))
        assertNull(ArchiveGameIdentity.parse("content://documents/example.zip"))
        assertNull(ArchiveGameIdentity.parse("gamezip:v1:!!!:!!!"))
        assertNull(ArchiveGameIdentity.parse("gamezip:v1::"))
    }
}
