package com.sbro.emucored.core

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.ds.DsSession
import com.sbro.emucored.core.ds.EmuCoreDsNative
import com.sbro.emucored.data.SaveStateRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DsAtomicSaveDeviceInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 90_000) fun failedPublicationPreservesSlotAndArchivesRoundTripWithoutDuplicateEntries() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dsAtomicState") == "true")
        compose.setContent { Text("DS save publication and archive integrity") }
        testStorageContext().use { context ->
            val rom = File(context.filesDir, "original-state-test.nds").apply { writeBytes(createDsScreenSentinelRom()) }
            try {
                assertTrue(DsSession.start(context, rom.path, renderer = 13))
                withTimeout(20_000) { while (EmuCoreDsNative.nativeGetFrameCount() < 10) delay(10) }
                DsSession.pause()
                assertTrue(DsSession.saveState(1))
                val state = File(requireNotNull(DsSession.statePath(1)))
                val original = state.readBytes()
                val pending = File("${state.path}.pending")
                assertTrue(pending.mkdir())
                try {
                    assertFalse(DsSession.saveState(1))
                    assertArrayEquals(original, state.readBytes())
                } finally { assertTrue(pending.delete()) }
                assertTrue(DsSession.saveState(1))
                assertFalse(pending.exists())
                assertTrue(DsSession.loadState(1))
                val repository = SaveStateRepository(context)
                val entries = repository.listEntries(rom.path)
                assertEquals("Filtered DS states must not appear twice", 1, entries.size)
                val archive = File(context.cacheDir, "states.zip")
                assertTrue(repository.backupEntries(entries, Uri.fromFile(archive)))
                val preview = repository.analyzeImport(Uri.fromFile(archive), rom.path)
                assertEquals(2, preview.candidates.single().targetSlot)
                assertEquals(1, repository.importStates(preview).importedCount)
                assertTrue(DsSession.loadState(2))
                val corrupt = File(context.cacheDir, "corrupt.zip")
                ZipFile(archive).use { source -> ZipOutputStream(corrupt.outputStream()).use { output ->
                    source.entries().asSequence().forEach { entry ->
                        val bytes = source.getInputStream(entry).readBytes()
                        if (entry.name.startsWith("slots/")) bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                        output.putNextEntry(ZipEntry(entry.name)); output.write(bytes); output.closeEntry()
                    }
                } }
                val before = state.readBytes()
                val badPreview = repository.analyzeImport(Uri.fromFile(corrupt), rom.path)
                assertEquals(1, repository.importStates(badPreview).failedCount)
                assertArrayEquals(before, state.readBytes())
                assertFalse(File(requireNotNull(DsSession.statePath(3))).exists())
            } finally { DsSession.stop(); DsSession.setSurface(null) }
        }
    }
}
