package com.sbro.emucored.core

import android.content.Context
import android.system.Os
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.dolphin.DolphinSessionEngine
import com.sbro.emucored.core.platform.ConsolePlatform
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConsoleMultipartImageInstrumentedTest {
    @Test fun splitWbfsKeepsNamedSeekableDescriptorsAliveForTheRealNativeReader() = runBlocking {
        Fixture().use { fixture ->
            // The scanner already knows this folder's children even when the
            // granted library has more directories than a fallback BFS budget.
            repeat(200) { File(fixture.folder, "folder_$it").mkdirs() }
            val files = fixture.wbfs(File(fixture.folder, "folder_199"))
            assertEquals(com.sbro.emucored.data.GamePlatform.WII,
                ConsoleGameDiscovery.identify(fixture.context, fixture.uri(files.first()), files.first().name,
                    siblingDocuments = files.map { it.name to fixture.uri(it) }))
            val original = files.map { it.readBytes() }
            val prepared = ConsoleImageAccess.prepareWithSiblingDocuments(fixture.context,
                fixture.uri(files.first()), files.map { it.name to fixture.uri(it) })!!
            val directory = File(prepared.path).parentFile!!
            val descriptorPaths = files.indices.map { index ->
                val named = File(directory, ConsoleImageParts.nativeName(files.first().name, index))
                assertArrayEquals(original[index], named.readBytes())
                Os.readlink(named.path).also { assertTrue("Expected named held FD, not a multi-GB copy", it.startsWith("/proc/self/fd/")) }
            }
            try {
                assertEquals(ConsolePlatform.WII, DolphinSessionEngine.probePlatform(prepared.path))
                assertEquals("Original.WBFS", prepared.displayName)
            } finally { prepared.close(); prepared.close() }
            assertFalse(directory.exists())
            for (fd in descriptorPaths) assertFalse("Descriptor leaked: $fd", File(fd).exists())
            files.forEachIndexed { index, file -> assertArrayEquals(original[index], file.readBytes()) }
            val game = com.sbro.emucored.data.GameItem("Original", files.first().path, files.first().name,
                files.first().length(), 0, platform = com.sbro.emucored.data.GamePlatform.WII)
            val repository = com.sbro.emucored.data.ContentLibraryRepository(fixture.context)
            assertEquals("ORIG01", repository.resolveIdentity(game).typed?.gameId)
            assertNull("Cheat identity trusted the selected console instead of the actual disc",
                repository.resolveIdentity(game.copy(platform = com.sbro.emucored.data.GamePlatform.GAMECUBE)).typed)
        }
    }

    @Test fun uppercaseSplitIsoPreservesCompanionsAndNativeDiscoveryExcludesLaterParts() = runBlocking {
        Fixture().use { fixture ->
            val bytes = ByteArray(65536)
            "ORIG01".toByteArray().copyInto(bytes)
            putInt(bytes, 0x1c, 0xc2339f3d.toInt())
            val first = File(fixture.folder, "Original.PART0.ISO").apply { writeBytes(bytes.copyOfRange(0, 32768)) }
            val second = File(fixture.folder, "original.part1.iso").apply { writeBytes(bytes.copyOfRange(32768, bytes.size)) }
            var marker: File? = null
            ConsoleImageAccess.prepare(fixture.context, first.path)!!.use { image ->
                assertTrue(image.path.endsWith("image.part0.iso"))
                assertEquals(ConsolePlatform.GAMECUBE, DolphinSessionEngine.probePlatform(image.path))
                assertArrayEquals(second.readBytes(), File(File(image.path).parentFile, "image.part1.iso").readBytes())
                // A source can be replaced while a session owns its staged links.
                // Cleanup must unlink the stage, never recurse into the new directory.
                assertTrue(first.delete())
                assertTrue(first.mkdir())
                marker = File(first, "keep.txt").apply { writeText("source data") }
            }
            assertFalse(ConsoleGameFormats.isSupportedName(second.name))
            assertTrue(first.exists() && second.exists())
            assertEquals("source data", marker!!.readText())
        }
    }

    @Test fun aSingleDocumentGrantCannotSilentlyReachAdjacentSplitFiles() = runBlocking {
        Fixture().use { fixture ->
            val files = fixture.wbfs()
            // FileProvider grants access to this document, not a DocumentsProvider
            // tree. Native preparation must not guess the neighboring source path.
            assertNull(ConsoleImageAccess.prepare(fixture.context, fixture.uri(files.first())))
            assertTrue(files.all { it.exists() && it.length() == 2L * 1024 * 1024 })
        }
    }

    @Test fun cancellationAfterOpeningTheMainDocumentReleasesItsDescriptor() = runBlocking {
        Fixture().use { fixture ->
            val files = fixture.wbfs()
            val job = Job()
            // Android aliases /data/data and /data/user/0 refer to the same inode.
            // A descriptor lease must be checked by kernel identity, not link spelling.
            val sourceIdentity = Os.stat(files.first().path)
            fun ownsSource(fd: File): Boolean = runCatching {
                val current = Os.stat(fd.path)
                current.st_dev == sourceIdentity.st_dev && current.st_ino == sourceIdentity.st_ino
            }.getOrDefault(false)
            val held = mutableListOf<String>()
            val siblings = object : AbstractList<Pair<String, String>>() {
                override val size = files.size
                override fun get(index: Int): Pair<String, String> {
                    if (index == 0) {
                        held += File("/proc/self/fd").listFiles().orEmpty()
                            .filter(::ownsSource).map { it.path }
                        job.cancel()
                    }
                    return files[index].name to fixture.uri(files[index])
                }
            }
            try {
                withContext(job) {
                    val active = currentCoroutineContext()
                    ConsoleImageAccess.prepareForProbe(fixture.context, fixture.uri(files.first()), siblings,
                        checkActive = { active.ensureActive() })
                }
                fail("Cancellation was ignored")
            } catch (_: CancellationException) { /* Expected; the parent test remains active. */ }
            assertTrue("Fixture did not observe the opened source descriptor", held.isNotEmpty())
            for (fd in held) assertFalse("Source descriptor still held after cancellation: $fd", ownsSource(File(fd)))
            assertTrue(files.all(File::exists))
        }
    }

    private class Fixture : AutoCloseable {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "shared/multipart-${UUID.randomUUID()}").apply { mkdirs() }
        fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
        fun wbfs(parent: File = folder): List<File> {
            val first = ByteArray(2 * 1024 * 1024)
            "WBFS".toByteArray().copyInto(first)
            putInt(first, 4, 8192) // 4 MiB total in 512-byte host sectors.
            first[8] = 9; first[9] = 21; first[12] = 1
            first[769] = 1 // Logical disc block zero maps to physical 2 MiB block one.
            val second = ByteArray(first.size)
            "ORIG01".toByteArray().copyInto(second)
            putInt(second, 0x18, 0x5d1c9ea3)
            return listOf(
                File(parent, "Original.WBFS").apply { writeBytes(first) },
                File(parent, "original.wbf1").apply { writeBytes(second) }
            )
        }
        override fun close() { folder.deleteRecursively() }
    }

    companion object {
        private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
            for (index in 0..3) bytes[offset + index] = (value ushr (24 - index * 8)).toByte()
        }
    }
}
