package com.sbro.emucored.data.textures

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.BackupSessionGate
import com.sbro.emucored.core.EmuCoreNative
import com.sbro.emucored.core.testStorageContext
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreOptionPreferences
import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.PerGameSettingsRepository
import com.sbro.emucored.data.drive.DriveBackupException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Original metadata and texture fixtures only; never touches retail games or user texture roots. */
@RunWith(AndroidJUnit4::class)
class TexturePackRepositoryInstrumentedTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==")

    @Test fun nativeHeaderIdentityWinsOverLibrarySerialAndWrongSelectedConsoleIsRejected() = runBlocking {
        testStorageContext().use { context ->
            val repository = AndroidTexturePackRepository(context)
            val game = cube(context.cacheDir)
            assertEquals(TexturePackTarget(ConsolePlatform.GAMECUBE, "GTSE01"), repository.resolve(game))
            assertNull(repository.resolve(game.copy(platform = GamePlatform.WII)))
            assertNull(repository.resolve(game.copy(platform = GamePlatform.NDS)))

            val file = File(context.cacheDir, "0004000009999900.cxi")
            val image = ByteArray(0x200)
            "NCCH".toByteArray().copyInto(image, 0x100)
            ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).putLong(0x118, 0x0004000001234500L)
            file.writeBytes(image)
            val threeDs = GameItem("Header fixture", file.path, file.name, file.length(), 0,
                serial = "0004000009999900", platform = GamePlatform.N3DS)
            assertEquals("Native fixture must be readable through Azahar's absolute-path protocol",
                0x0004000001234500L, EmuCoreNative.getTitleId("!${file.path}"))
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                assertEquals("Held descriptors require the same native absolute-path protocol",
                    0x0004000001234500L, EmuCoreNative.getTitleId("!/proc/self/fd/${descriptor.fd}"))
            }
            assertEquals(TexturePackTarget(ConsolePlatform.N3DS, "0004000001234500"), repository.resolve(threeDs))
            file.writeBytes(ByteArray(0x200))
            assertNull("A filename/library serial must never substitute for a native title ID", repository.resolve(threeDs))
        }
    }

    @Test fun safZipImportExportAndDeleteUseTheActualNativeGameDirectoryAndPreserveOldPackOnFailure() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val shared = File(app.cacheDir, "shared/texture-${UUID.randomUUID()}").apply { check(mkdirs()) }
        fun uri(file: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        try {
            testStorageContext().use { context ->
                val repository = AndroidTexturePackRepository(context)
                val game = cube(context.cacheDir)
                val target = requireNotNull(repository.resolve(game))
                val archive = zip(shared, "GTSE01/test.png", png)
                repository.importZip(game, target, uri(archive))
                val installed = repository.inspect(game, target)
                assertEquals(1, installed.stats.textureCount)
                assertEquals(listOf("test.png"), installed.files)
                assertTrue(File(context.filesDir, "consoles/dolphin/Load/Textures/GTSE01/test.png").isFile)
                repository.setEnabled(game, target, true)
                assertEquals("true", CoreOptionPreferences(context).effectiveOptions(ConsolePlatform.GAMECUBE, game.path)["hires_textures"])
                assertEquals("true", PerGameSettingsRepository(context).get(game.path)?.coreOptions?.get("hires_textures"))

                val damaged = zip(shared, "GTSE01/../escape.png", png)
                try { repository.importZip(game, target, uri(damaged)); fail("Traversal import succeeded") }
                catch (_: IllegalArgumentException) { }
                catch (_: IOException) { /* Android's ZIP validator may reject traversal before the store. */ }
                assertEquals(listOf("test.png"), repository.inspect(game, target).files)
                val exported = File(shared, "export.zip").apply { writeBytes(byteArrayOf()) }
                repository.export(game, target, uri(exported))
                assertTrue(exported.length() > 0)
                repository.delete(game, target)
                assertEquals(0, repository.inspect(game, target).stats.textureCount)
                repository.importZip(game, target, uri(exported))
                assertEquals(1, repository.inspect(game, target).stats.textureCount)
            }
        } finally { shared.deleteRecursively() }
    }

    @Test fun runningSessionGateRejectsFilesystemAndPreferenceMutationsBeforeAnyNativeWrite() = runBlocking {
        testStorageContext().use { context ->
            val repository = AndroidTexturePackRepository(context)
            val game = cube(context.cacheDir)
            val target = requireNotNull(repository.resolve(game))
            val archive = zip(context.cacheDir, "test.png", png)
            try {
                BackupSessionGate.start(active = { true }) { true }
                val operations: List<suspend () -> Unit> = listOf(
                    { repository.installArchive(game, target, archive); Unit },
                    { repository.delete(game, target) },
                    { repository.setEnabled(game, target, true) },
                    { repository.export(game, target, Uri.fromFile(File(context.cacheDir, "must-not-exist.zip"))) }
                )
                operations.forEach { operation ->
                    try { operation(); fail("Mutation was allowed during gameplay") }
                    catch (error: DriveBackupException) { assertEquals("busy", error.reason) }
                }
                assertFalse(File(context.filesDir, "consoles/dolphin/Load/Textures/GTSE01").exists())
                assertFalse(File(context.cacheDir, "must-not-exist.zip").exists())
                assertTrue(CoreOptionPreferences(context).gameOverrides(ConsolePlatform.GAMECUBE, game.path).isEmpty())
            } finally { BackupSessionGate.stopped() }
        }
    }

    private fun cube(directory: File): GameItem {
        val image = ByteArray(64 * 1024)
        "GTSE01".toByteArray().copyInto(image)
        ByteBuffer.wrap(image).order(ByteOrder.BIG_ENDIAN).putInt(0x1c, 0xc2339f3d.toInt())
        val file = File(directory, "wrong-RMGE01.iso").apply { writeBytes(image) }
        return GameItem("Original disc header", file.path, file.name, file.length(), 0,
            serial = "RMGE01", platform = GamePlatform.GAMECUBE)
    }

    private fun zip(directory: File, name: String, bytes: ByteArray): File = File.createTempFile("pack-", ".zip", directory).also {
        ZipOutputStream(it.outputStream()).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
    }
}
