package com.sbro.emucored.data

import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.session.ConsoleSessionStorage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConsoleSaveStateIndexTest {
    @Test fun indexesOnlyCompletedStatesBoundToTheirConsoleGameAndSlot() {
        val directory = Files.createTempDirectory("console-state-index").toFile()
        try {
            val identity = "content://games/document/opaque-123"
            val file = ConsoleSessionStorage.stateFile(directory, ConsolePlatform.GBA, identity, 0)
            file.parentFile!!.mkdirs()
            val payload = ByteArray(128) { it.toByte() }
            var checksum = 1469598103934665603L
            payload.forEach { checksum = (checksum xor (it.toLong() and 255)) * 1099511628211L }
            file.writeBytes(ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(0x3145544154534345).putLong(1).putLong(checksum).putLong(payload.size.toLong()).array() + payload)
            val json = JSONObject().put("platform", "GBA").put("gamePath", identity).put("title", "Original game").put("slot", 0)
            val sidecar = File("${file.path}.json").apply { writeText(json.toString()) }
            val index = ConsoleSaveStateIndex(directory)
            assertEquals(1, index.entries().size)
            assertEquals(0, index.entries("content://another-game").size)
            assertEquals(0, index.entries(identity).single().slot)
            sidecar.writeText(json.put("platform", "NES").toString())
            assertTrue(index.entries().isEmpty())
            sidecar.writeText(json.put("platform", "GBA").put("gamePath", "foreign-game").toString())
            assertTrue(index.entries().isEmpty())
            sidecar.writeText(json.put("gamePath", identity).toString())
            file.appendBytes(byteArrayOf(0))
            assertTrue(index.entries().isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
