package com.sbro.emucored.data.textures

import com.sbro.emucored.core.platform.ConsolePlatform
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class TexturePackStoreTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==")
    private fun zip(root: File, vararg entries: Pair<String, ByteArray>): File = File.createTempFile("texture-", ".zip", root).also { file ->
        ZipOutputStream(file.outputStream()).use { output -> entries.forEach { (name, bytes) ->
            output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
        } }
    }

    @Test fun interruptedReplacementRestoresThePreviousPackAndCompletedReplacementKeepsTheNewOne() {
        val root = Files.createTempDirectory("texture-recovery").toFile()
        try {
            val target = TexturePackTarget(ConsolePlatform.GAMECUBE, "GM8E01")
            val store = TexturePackStore(root)
            val directory = store.directory(target)
            directory.mkdirs()
            File(directory, "new.png").writeBytes(png)
            val old = File(directory.parentFile, ".${target.id}.texture-old").apply { mkdirs() }
            File(old, "old.png").writeBytes(png)
            val journal = File(directory.parentFile, ".${target.id}.texture-journal")
            journal.writeText(JSONObject().put("phase", "prepared").put("hadOld", true).toString())
            assertEquals(1, store.inspect(target).textureCount)
            assertTrue(File(directory, "old.png").isFile)
            assertFalse(File(directory, "new.png").exists())
            assertFalse(old.exists())
            assertFalse(journal.exists())
            old.mkdirs()
            File(old, "older.png").writeBytes(png)
            journal.writeText(JSONObject().put("phase", "committed").put("hadOld", true).toString())
            store.inspect(target)
            assertTrue(File(directory, "old.png").isFile)
            assertFalse(old.exists())
            assertFalse(journal.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun actualTotalAndEntryLimitsRejectBeforeReplacingAnInstalledPack() {
        val root = Files.createTempDirectory("texture-bounds").toFile()
        try {
            val target = TexturePackTarget(ConsolePlatform.GAMECUBE, "GM8E01")
            val store = TexturePackStore(root)
            store.install(target, zip(root, "old.png" to png))
            val archive = zip(root, "new.png" to png, "other.png" to png)
            for (limits in listOf(TexturePackLimits(maxEntries = 1), TexturePackLimits(maxTotalBytes = png.size.toLong()))) {
                assertThrows(IllegalArgumentException::class.java) { TexturePackStore(root, limits).install(target, archive) }
                assertArrayEquals(png, File(store.directory(target), "old.png").readBytes())
                assertFalse(File(store.directory(target), "new.png").exists())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun routesOnlySupportedCoresAndExactNativeIdentities() {
        val root = Files.createTempDirectory("texture-routing").toFile()
        try {
            val store = TexturePackStore(root)
            assertEquals(File(root, "azahar/load/textures/0004000001234500"),
                store.directory(TexturePackTarget(ConsolePlatform.N3DS, "0004000001234500")))
            for (platform in listOf(ConsolePlatform.GAMECUBE, ConsolePlatform.WII)) {
                assertEquals(File(root, "consoles/dolphin/Load/Textures/GM8E01"),
                    store.directory(TexturePackTarget(platform, "GM8E01")))
            }
            for (platform in ConsolePlatform.entries.filterNot { it in setOf(ConsolePlatform.N3DS, ConsolePlatform.GAMECUBE, ConsolePlatform.WII) })
                assertThrows(IllegalArgumentException::class.java) { TexturePackTarget(platform, "GM8E01") }
            assertThrows(IllegalArgumentException::class.java) { TexturePackTarget(ConsolePlatform.N3DS, "CTR-P-ABCD") }
            assertThrows(IllegalArgumentException::class.java) { TexturePackTarget(ConsolePlatform.WII, "../../outside") }
        } finally { root.deleteRecursively() }
    }

    @Test fun installsNativeWrapperAndExportsWithoutLosingAzaharConfiguration() {
        val root = Files.createTempDirectory("texture-roundtrip").toFile()
        try {
            val target = TexturePackTarget(ConsolePlatform.N3DS, "0004000001234500")
            val store = TexturePackStore(root)
            val config = "{\"options\":{\"use_new_hash\":true}}".toByteArray()
            val archive = zip(root, "load/textures/${target.id}/tex1_1x1_ABCDEF0123456789_0.png" to png,
                "load/textures/${target.id}/pack.json" to config)
            val installed = store.install(target, archive)
            assertEquals(1, installed.textureCount)
            assertArrayEquals(config, File(store.directory(target), "pack.json").readBytes())
            val exported = File(root, "export.zip")
            store.export(target, exported.outputStream())
            ZipFile(exported).use { result ->
                assertArrayEquals(png, result.getInputStream(result.getEntry("${target.id}/tex1_1x1_ABCDEF0123456789_0.png")).readBytes())
            }
            store.delete(target)
            assertEquals(0, store.inspect(target).textureCount)
            store.install(target, exported)
            assertEquals(1, store.inspect(target).textureCount)
        } finally { root.deleteRecursively() }
    }

    @Test fun unsafeForeignCollidingAndOversizedArchivesPreserveExistingPack() {
        val root = Files.createTempDirectory("texture-rejections").toFile()
        try {
            val target = TexturePackTarget(ConsolePlatform.GAMECUBE, "GM8E01")
            val store = TexturePackStore(root)
            val name = "tex1_1x1_ABCDEF0123456789_0.png"
            store.install(target, zip(root, "GM8E01/$name" to png))
            val native = File(store.directory(target), name)
            val bad = listOf(
                zip(root, "../outside.png" to png),
                zip(root, "GZLE01/$name" to png),
                zip(root, "GM8E01/$name" to png, "GM8E01/${name.uppercase()}" to png),
                zip(root, "GM8E01/broken.png" to byteArrayOf(1, 2)),
                zip(root, "GM8E01/evil.exe" to png))
            bad.forEach { archive ->
                assertThrows(IllegalArgumentException::class.java) { store.install(target, archive) }
                assertArrayEquals(png, native.readBytes())
            }
            val limited = TexturePackStore(root, TexturePackLimits(maxFileBytes = 32))
            assertThrows(IllegalArgumentException::class.java) { limited.install(target, zip(root, "GM8E01/$name" to png)) }
            assertArrayEquals(png, native.readBytes())
            assertFalse(File(root.parentFile, "outside.png").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun dolphinRegionFreeWrapperMapsToTheSelectedActualGameId() {
        val root = Files.createTempDirectory("texture-region-free").toFile()
        try {
            val target = TexturePackTarget(ConsolePlatform.WII, "RMGE01")
            val store = TexturePackStore(root)
            store.install(target, zip(root, "RMG/tex1_1x1_ABCDEF0123456789_0.png" to png))
            assertTrue(File(store.directory(target), "tex1_1x1_ABCDEF0123456789_0.png").isFile)
            assertFalse(File(root, "consoles/dolphin/Load/Textures/RMG").exists())
        } finally { root.deleteRecursively() }
    }
}
