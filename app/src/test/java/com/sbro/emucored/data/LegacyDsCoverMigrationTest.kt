package com.sbro.emucored.data

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class LegacyDsCoverMigrationTest {
    @Test fun existingCoverIsReusedOnlyWithExactIndexedSerialArtwork() {
        val legacy = Files.createTempFile("legacy-cover", ".webp").toFile()
        try {
            legacy.writeBytes(byteArrayOf(1, 2, 3, 4))
            val hash = MessageDigest.getInstance("SHA-256").digest(legacy.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertTrue(isVerifiedLegacyDsCover(legacy, "ANWE", hash))
            assertFalse(isVerifiedLegacyDsCover(legacy, null, hash))
            assertFalse(isVerifiedLegacyDsCover(legacy, "ANWE", ""))
            assertFalse(isVerifiedLegacyDsCover(legacy, "ANWE", "0".repeat(64)))
            legacy.writeBytes(byteArrayOf(9, 8, 7))
            assertFalse(isVerifiedLegacyDsCover(legacy, "ANWE", hash))
        } finally {
            legacy.delete()
        }
    }
}
