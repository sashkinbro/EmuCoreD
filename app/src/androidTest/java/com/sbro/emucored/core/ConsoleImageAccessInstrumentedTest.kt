package com.sbro.emucored.core

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConsoleImageAccessInstrumentedTest {
    @Test fun seekableProviderImageStaysReadableUntilItsOwnerClosesWithoutCopying() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(app.cacheDir, "shared/console-image-${UUID.randomUUID()}").apply { mkdirs() }
        val bytes = ByteArray(32768) { (it * 17).toByte() }
        val image = File(folder, "original.gba").apply { writeBytes(bytes) }
        try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", image)
            val prepared = ConsoleImageAccess.prepare(app, uri.toString())!!
            assertEquals("original.gba", prepared.displayName)
            assertTrue("Seekable images should not require a full cache copy", prepared.path.startsWith("/proc/self/fd/"))
            assertArrayEquals(bytes, File(prepared.path).readBytes())
            prepared.close()
            prepared.close()
            assertArrayEquals("Source image was modified", bytes, image.readBytes())
        } finally { folder.deleteRecursively() }
    }

    @Test fun readableFilesUseTheirActualPathAndMissingSourcesDoNotBecomeEmptyRoms() = runBlocking {
        testStorageContext().use { context ->
            val image = File(context.cacheDir, "test.nes").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            ConsoleImageAccess.prepare(context, "!${image.path}")!!.use { assertEquals(image.path, it.path) }
            assertNull(ConsoleImageAccess.prepare(context, File(context.cacheDir, "missing.nes").path))
            assertEquals(4L, image.length())
        }
    }
}
