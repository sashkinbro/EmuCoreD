package com.sbro.emucored.core

import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.data.GameRepository
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.GameLibraryCacheRepository
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.session.AdditionalConsoleSession
import com.sbro.emucored.core.platform.session.EngineLaunchRequest
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Optional owned ROM test; all emulator data is held in disposable test storage. */
@RunWith(AndroidJUnit4::class)
class ArchivedSnesSessionInstrumentedTest {
    @Test(timeout = 60_000) fun scansEachGameInAnArchiveAndKeepsThemDistinctAfterCacheReload() {
        val sourcePath = InstrumentationRegistry.getArguments().getString("ownedSnesRom").orEmpty()
        assumeTrue("Provide an owned SNES ROM via ownedSnesRom", sourcePath.isNotBlank())
        val source = File(sourcePath)
        assertTrue(source.isFile)
        testStorageContext().use { context ->
            val library = File(context.cacheDir, "library").apply { check(mkdirs()) }
            val archive = File(library, "collection.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                for (name in listOf("one/First.sfc", "two/Second.sfc")) {
                    zip.putNextEntry(ZipEntry(name)); source.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            val games = GameRepository().scanDirectory(library.path, context)
            assertEquals(2, games.size)
            assertEquals(setOf("First.sfc", "Second.sfc"), games.map { it.fileName }.toSet())
            assertTrue(games.all { it.platform == GamePlatform.SNES && ConsoleGameDiscovery.identify(context, it.path) == GamePlatform.SNES })
            val cache = GameLibraryCacheRepository(context)
            cache.save(library.path, games)
            val restored = cache.load(library.path)
            assertEquals(2, restored.size)
            assertEquals(games.map { it.path }.toSet(), restored.map { it.path }.toSet())
        }
    }
    @Test(timeout = 180_000) fun discoversAndStartsTwoGamesInAnAndroidDocumentTree() = runBlocking {
        val tree = InstrumentationRegistry.getArguments().getString("archiveTreeUri").orEmpty()
        assumeTrue("Provide a granted Android document tree containing the QA archive", tree.isNotBlank())
        testStorageContext().use { context ->
            val games = GameRepository().scanDirectoryFromUri(tree.toUri(), context)
                .filter { it.fileName in setOf("codex-zip-first.sfc", "codex-zip-second.sfc") }
            assertEquals(2, games.size)
            assertEquals(2, games.map { it.path }.distinct().size)
            assertTrue(games.all { it.platform == GamePlatform.SNES })
            val selected = games.first()
            try {
                assertTrue(AdditionalConsoleSession.start(EngineLaunchRequest(context, ConsolePlatform.SNES,
                    selected.path, selected.path, context.filesDir, mapOf("audio_enabled" to "false"), startPaused = true)))
                val guest = requireNotNull(AdditionalConsoleSession.session)
                guest.resume()
                withTimeout(30_000) { while (guest.frameCount() < 120) delay(25) }
            } finally { AdditionalConsoleSession.stop() }
        }
    }
    @Test(timeout = 180_000) fun twoArchiveEntriesBootThroughTheRealSnesSessionAndReleaseTheirFiles() = runBlocking {
        val sourcePath = InstrumentationRegistry.getArguments().getString("ownedSnesRom").orEmpty()
        assumeTrue("Provide an owned SNES ROM via ownedSnesRom", sourcePath.isNotBlank())
        val source = File(sourcePath)
        assertTrue(source.isFile && source.length() >= 32768)
        assertFalse(AdditionalConsoleSession.isActive)
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(app.cacheDir, "shared/snes-archive-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val archive = File(folder, "snes.zip")
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                for (name in listOf("one/game.sfc", "two/game.sfc")) {
                    zip.putNextEntry(ZipEntry(name))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val before = archive.readBytes()
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", archive).toString()
            testStorageContext().use { context ->
                val paths = mutableListOf<String>()
                try {
                    for (entry in listOf("one/game.sfc", "two/game.sfc")) {
                        val identity = ArchiveGameIdentity(uri, entry).encode()
                        val launch = EngineLaunchRequest(context, ConsolePlatform.SNES, identity, identity,
                            context.filesDir, mapOf("audio_enabled" to "false"), startPaused = true)
                        assertTrue("Archived SNES game did not start", AdditionalConsoleSession.start(launch))
                        paths += requireNotNull(AdditionalConsoleSession.stateFile(1)).path
                        val guest = requireNotNull(AdditionalConsoleSession.session)
                        guest.resume()
                        withTimeout(30_000) { while (guest.frameCount() < 120) delay(25) }
                        guest.pause()
                        assertTrue("SNES save state was not written", AdditionalConsoleSession.saveState(1))
                        AdditionalConsoleSession.stop()
                        assertFalse(AdditionalConsoleSession.isActive)
                        assertTrue(File(context.cacheDir, "console-images").listFiles().orEmpty().isEmpty())
                    }
                    assertNotEquals("Different archive entries share save identity", paths[0], paths[1])
                    assertArrayEquals(before, archive.readBytes())
                } finally { AdditionalConsoleSession.stop() }
            }
        } finally { folder.deleteRecursively() }
    }
}
