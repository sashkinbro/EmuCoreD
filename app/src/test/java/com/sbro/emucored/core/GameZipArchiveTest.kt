package com.sbro.emucored.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameZipArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun listsEachSupportedGameWithoutLosingItsArchivePath() {
        val archive = folder.newFile("games.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            listOf("games/First.sfc", "Second.nes", "readme.txt").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        assertEquals(listOf("games/First.sfc", "Second.nes"),
            GameZipArchive.entries(archive) { it.endsWith(".sfc") || it.endsWith(".nes") })
    }

    @Test fun rejectsTraversalInsteadOfOfferingAnUnsafeEntry() {
        val archive = zip("unsafe.zip", "../game.sfc" to byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) {
            GameZipArchive.entries(archive) { it.endsWith(".sfc") }
        }
    }

    @Test fun extractsOnlyTheSelectedGameAndPreservesTheArchive() {
        val archive = zip("collection.zip", "one/game.sfc" to byteArrayOf(1, 2, 3),
            "two/game.sfc" to byteArrayOf(4, 5))
        val before = archive.readBytes()
        val output = GameZipArchive.extract(archive, "two/game.sfc", folder.root, 1024)
        assertArrayEquals(byteArrayOf(4, 5), output.readBytes())
        assertArrayEquals(before, archive.readBytes())
        assertEquals("sfc", output.extension)
    }

    @Test fun rejectsOversizedExtractionWithoutLeavingPartialFiles() {
        val archive = zip("large.zip", "game.sfc" to ByteArray(9) { 1 })
        val outputDirectory = folder.newFolder("output")
        assertThrows(IllegalArgumentException::class.java) {
            GameZipArchive.extract(archive, "game.sfc", outputDirectory, 8)
        }
        assertEquals(0, outputDirectory.listFiles()!!.size)
        assertFalse(archive.length() == 0L)
    }


    @Test fun cancelledExtractionRemovesPartialOutput() {
        val archive = zip("cancel.zip", "game.sfc" to ByteArray(160_000) { (it % 251).toByte() })
        val outputDirectory = folder.newFolder("cancel-output")
        var checks = 0
        assertThrows(CancellationException::class.java) {
            GameZipArchive.extract(archive, "game.sfc", outputDirectory, 200_000) {
                if (++checks == 3) throw CancellationException("cancel")
            }
        }
        assertEquals(0, outputDirectory.listFiles()!!.size)
    }

    @Test fun detectsCorruptEntryAndRemovesPartialOutput() {
        val archive = folder.newFile("corrupt.zip")
        val payload = byteArrayOf(13, 77, 45, 99, 17)
        ZipOutputStream(archive.outputStream()).use { zip ->
            val entry = ZipEntry("game.sfc").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(payload) }.value
            }
            zip.putNextEntry(entry)
            zip.write(payload)
            zip.closeEntry()
        }
        val bytes = archive.readBytes()
        val offset = bytes.indices.first { index ->
            index + payload.size <= bytes.size && payload.indices.all { bytes[index + it] == payload[it] }
        }
        bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
        archive.writeBytes(bytes)
        val outputDirectory = folder.newFolder("corrupt-output")
        assertThrows(IllegalArgumentException::class.java) {
            GameZipArchive.extract(archive, "game.sfc", outputDirectory, 1024)
        }
        assertEquals(0, outputDirectory.listFiles()!!.size)
    }
    private fun zip(name: String, vararg files: Pair<String, ByteArray>): File =
        folder.newFile(name).also { archive ->
            ZipOutputStream(archive.outputStream()).use { zip ->
                files.forEach { (entry, bytes) ->
                    zip.putNextEntry(ZipEntry(entry))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
}
