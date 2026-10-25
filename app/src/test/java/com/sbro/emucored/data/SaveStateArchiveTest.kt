package com.sbro.emucored.data

import com.sbro.emucored.core.platform.session.ConsoleSessionStorage
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SaveStateArchiveTest {
    @Test fun exportRejectsAStateReplacedBetweenManifestHashingAndPayloadCopy() {
        val directory = Files.createTempDirectory("state-export-race").toFile()
        try {
            val archive = SaveStateArchive(directory, File(directory, "legacy"))
            val entry = state(directory, GamePlatform.GBA, "racing-game", 0)
            val file = File(entry.absolutePath)
            val replacement = file.readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
            val bytes = ByteArrayOutputStream()
            var replaced = false
            val output = object : OutputStream() {
                override fun write(value: Int) {
                    if (!replaced) {
                        replaced = true
                        val pending = File(directory, "external-state.pending").apply { writeBytes(replacement) }
                        Files.move(pending.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                    bytes.write(value)
                }
            }
            assertThrows(IllegalArgumentException::class.java) { archive.write(listOf(entry), output) }
            assertTrue(replaced)
            assertArrayEquals(replacement, file.readBytes())
        } finally { directory.deleteRecursively() }
    }

    private fun state(root: File, platform: GamePlatform, identity: String, slot: Int): SaveStateEntryInfo {
        val file = ConsoleSessionStorage.stateFile(root, platform.console, identity, slot)
        file.parentFile!!.mkdirs()
        val payload = ByteArray(128) { it.toByte() }
        var checksum = 1469598103934665603L
        payload.forEach { checksum = (checksum xor (it.toLong() and 255)) * 1099511628211L }
        file.writeBytes(ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0x3145544154534345).putLong(1).putLong(checksum).putLong(payload.size.toLong()).array() + payload)
        File("${file.path}.json").writeText(JSONObject().put("platform", platform.name).put("gamePath", identity)
            .put("slot", slot).put("title", "Original fixture").toString())
        File("${file.path}.png").writeBytes(Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=="))
        return SaveStateEntryInfo(file.path, file.name, platform.name, null, slot, file.length(), file.lastModified(),
            identity, "Original fixture", null, platform = platform)
    }

    @Test fun preservesPerConsoleIdentityPreviewAndMetadataWithoutReplacingExistingSlots() {
        val directory = Files.createTempDirectory("state-archive").toFile()
        try {
            val archive = SaveStateArchive(directory, File(directory, "legacy"))
            val gba = state(directory, GamePlatform.GBA, "content://game-one", 0)
            val snes = state(directory, GamePlatform.SNES, "content://game-two", 0)
            val original = File(gba.absolutePath).readBytes()
            val zip = File(directory, "states.zip")
            archive.write(listOf(gba, snes), zip.outputStream())
            val preview = archive.preview(zip, zip.name, null, null, null)
            assertEquals(2, preview.candidates.size)
            assertTrue(preview.candidates.all { it.targetSlot == 1 })
            val result = archive.importStates(zip, preview.candidates)
            assertEquals(2, result.importedCount)
            assertEquals(0, result.failedCount)
            assertArrayEquals(original, File(gba.absolutePath).readBytes())
            val restored = ConsoleSessionStorage.stateFile(directory, GamePlatform.GBA.console, "content://game-one", 1)
            assertArrayEquals(original, restored.readBytes())
            assertTrue(SaveStateArchive.validPng(File("${restored.path}.png")))
            assertEquals(1, JSONObject(File("${restored.path}.json").readText()).getInt("slot"))
            assertEquals(GamePlatform.GBA, ConsoleSaveStateIndex(directory).read(restored)?.platform)
            assertEquals(1, archive.preview(zip, zip.name, "content://game-one", GamePlatform.GBA, null).skippedCount)
            assertTrue(archive.preview(zip, zip.name, "content://foreign", GamePlatform.GBA, null).candidates.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun payloadCorruptionAndTraversalNeverReplaceAValidState() {
        val directory = Files.createTempDirectory("bad-state-archive").toFile()
        try {
            val archive = SaveStateArchive(directory, File(directory, "legacy"))
            val entry = state(directory, GamePlatform.GBA, "stable-game", 0)
            val original = File(entry.absolutePath).readBytes()
            val zip = File(directory, "states.zip")
            archive.write(listOf(entry), zip.outputStream())
            val corrupt = File(directory, "corrupt.zip")
            ZipFile(zip).use { input -> ZipOutputStream(corrupt.outputStream()).use { output ->
                input.entries().asSequence().forEach { source ->
                    val bytes = input.getInputStream(source).readBytes()
                    if (source.name.startsWith("slots/")) bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                    output.putNextEntry(ZipEntry(source.name)); output.write(bytes); output.closeEntry()
                }
            } }
            val preview = archive.preview(corrupt, corrupt.name, null, null, null)
            assertEquals(1, archive.importStates(corrupt, preview.candidates).failedCount)
            assertArrayEquals(original, File(entry.absolutePath).readBytes())
            assertFalse(ConsoleSessionStorage.stateFile(directory, GamePlatform.GBA.console, "stable-game", 1).exists())
            val traversal = File(directory, "traversal.zip")
            ZipOutputStream(traversal.outputStream()).use {
                it.putNextEntry(ZipEntry("slots/../outside.ecs")); it.write(original); it.closeEntry()
                it.putNextEntry(ZipEntry("manifest.json")); it.write("{}".toByteArray()); it.closeEntry()
            }
            assertThrows(IllegalArgumentException::class.java) { archive.preview(traversal, traversal.name, null, null, null) }
            assertFalse(File(directory.parentFile, "outside.ecs").exists())
        } finally { directory.deleteRecursively() }
    }
}
