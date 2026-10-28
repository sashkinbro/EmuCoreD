package com.sbro.emucored.core

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.data.GamePlatform
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchivedConsoleImageInstrumentedTest {
    @Test fun selectedGameIsOwnedUntilCloseForLocalAndProviderArchives() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "shared/archive-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val archive = File(folder, "pack.zip")
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                listOf("first.sfc" to byteArrayOf(1, 2), "nested/second.sfc" to byteArrayOf(3, 4, 5)).forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
            }
            val original = archive.readBytes()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive).toString()
            for (source in listOf(archive.path, uri)) {
                val identity = ArchiveGameIdentity(source, "nested/second.sfc").encode()
                assertEquals(GamePlatform.SNES, ConsoleGameDiscovery.identify(context, identity))
                assertEquals("second.sfc", DocumentPathResolver.getDisplayName(context, identity))
                assertEquals("second.sfc", DocumentPathResolver.getFallbackDisplayName(identity))
                assertEquals("second", EmulatorBridge.getGameMetadata(identity, readDiscMetadata = false).title)
                val image = requireNotNull(ConsoleImageAccess.prepare(context, identity))
                val staged = File(image.path)
                assertEquals("second.sfc", image.displayName)
                assertArrayEquals(byteArrayOf(3, 4, 5), staged.readBytes())
                image.close()
                image.close()
                assertFalse("Staged game was retained after close", staged.exists())
                assertArrayEquals(original, archive.readBytes())
            }
        } finally { folder.deleteRecursively() }
    }
}
