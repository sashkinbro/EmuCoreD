package com.sbro.emucored.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ConsoleSaveStateIntegrityTest {
    @Test fun hexadecimalThreeDsTitleIdsAreNotRewrittenAsDiscSerials() {
        assertEquals("0004000ABCD12345", parseExternalSaveStateName("0004000ABCD12345.01.cst")?.serial)
    }
    @Test fun frameEnvelopeRejectsTruncationCorruptionAndTrailingBytes() {
        val directory = Files.createTempDirectory("core-state-integrity").toFile()
        try {
            val file = File(directory, "state.ecs")
            val payload = ByteArray(128) { it.toByte() }
            var checksum = 1469598103934665603L
            payload.forEach { checksum = (checksum xor (it.toLong() and 255)) * 1099511628211L }
            val header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(0x3145544154534345).putLong(42).putLong(checksum).putLong(payload.size.toLong()).array()
            val state = header + payload
            file.writeBytes(state)
            assertTrue(SaveStateFileIntegrity.validate(file, GamePlatform.GBA))
            file.writeBytes(state.copyOf(state.size - 1))
            assertFalse(SaveStateFileIntegrity.validate(file, GamePlatform.GBA))
            file.writeBytes(state + byteArrayOf(0))
            assertFalse(SaveStateFileIntegrity.validate(file, GamePlatform.GBA))
            file.writeBytes(state.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertFalse(SaveStateFileIntegrity.validate(file, GamePlatform.GBA))
        } finally { directory.deleteRecursively() }
    }

    @Test fun dsStateRequiresCompletedDeclaredLengthAndCurrentMajor() {
        val directory = Files.createTempDirectory("ds-state-integrity").toFile()
        try {
            val file = File(directory, "slot1.dss")
            val state = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                .put("MELN".toByteArray()).putShort(13).putShort(1).putInt(32).putInt(0).array()
            file.writeBytes(state)
            assertTrue(SaveStateFileIntegrity.validate(file, GamePlatform.NDS))
            file.writeBytes(state.copyOf(20))
            assertFalse(SaveStateFileIntegrity.validate(file, GamePlatform.NDS))
            state[4] = 12
            file.writeBytes(state)
            assertFalse(SaveStateFileIntegrity.validate(file, GamePlatform.NDS))
        } finally { directory.deleteRecursively() }
    }
}
