package com.sbro.emucored.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.*
import org.junit.Test

class GpuDriverArchiveTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun failedReplacementPreservesExistingDriver() {
        val root = createTempDirectory("driver-test-").toFile()
        try {
            val old = File(root, "driver/libvulkan.so").apply { parentFile!!.mkdirs(); writeText("original") }
            val archive = zip("libvulkan.so" to "invalid".toByteArray())
            assertThrows(IllegalStateException::class.java) {
                GpuDriverArchive.install(ByteArrayInputStream(archive), "driver.zip", root) { _, _ -> error("invalid driver") }
            }
            assertEquals("original", old.readText())
            assertEquals(listOf("driver"), root.list()!!.toList())
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsTraversalAndOversizedArchivesWithoutLeavingFiles() {
        val root = createTempDirectory("driver-test-").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                GpuDriverArchive.install(ByteArrayInputStream(zip("../outside" to byteArrayOf(1))), "test.zip", root) { _, _ -> }
            }
            assertThrows(IllegalArgumentException::class.java) {
                GpuDriverArchive.install(ByteArrayInputStream(zip("libvulkan.so" to ByteArray(33))), "test.zip", root, maxBytes = 32) { _, _ -> }
            }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun successfulReplacementPublishesValidatedFiles() {
        val root = createTempDirectory("driver-test-").toFile()
        try {
            File(root, "driver/old.so").apply { parentFile!!.mkdirs(); writeText("old") }
            val archive = zip("libvulkan.so" to "new".toByteArray())
            val name = GpuDriverArchive.install(ByteArrayInputStream(archive), "driver.zip", root) { directory, paths ->
                assertEquals(listOf("libvulkan.so"), paths)
                assertEquals("new", File(directory, "libvulkan.so").readText())
            }
            assertEquals("driver", name)
            assertEquals("new", File(root, "driver/libvulkan.so").readText())
            assertFalse(File(root, "driver/old.so").exists())
            assertEquals(listOf("driver"), root.list()!!.toList())
        } finally { root.deleteRecursively() }
    }
}
