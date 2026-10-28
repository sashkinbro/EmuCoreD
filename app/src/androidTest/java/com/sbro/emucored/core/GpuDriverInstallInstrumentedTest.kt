package com.sbro.emucored.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpuDriverInstallInstrumentedTest {
    @Test fun installsRealOwnedCatalogPackageIntoIsolatedStorage() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("gpuDriverArchive").orEmpty()
        assumeTrue("Provide a driver archive with gpuDriverArchive", path.isNotBlank())
        testStorageContext().use { context ->
            val manager = GpuDriverManager(context)
            val name = manager.installFromArchive(File(path), GpuDriverVendor.ADRENO)
            val installed = manager.listInstalledDrivers().single { it.name == name }
            assertTrue(installed.isUsable)
            assertTrue(isValidDriverLibrary(File(installed.mainLibraryPath)))
            assertEquals(installed.mainLibraryPath, manager.resolveUsableDriverPath(installed.mainLibraryPath))
            assertNull(manager.resolveUsableDriverPath(null))

            val incompatible = File(context.cacheDir, "incompatible.zip")
            ZipFile(path).use { original ->
                ZipOutputStream(incompatible.outputStream()).use { output ->
                    original.entries().asSequence().filterNot { it.name == "meta.json" }.forEach { entry ->
                        output.putNextEntry(ZipEntry(entry.name))
                        if (!entry.isDirectory) original.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                    output.putNextEntry(ZipEntry("meta.json"))
                    output.write("{\"minApi\":999,\"libraryName\":\"libvulkan_freedreno.so\"}".toByteArray())
                    output.closeEntry()
                }
            }
            assertTrue("Unsupported Android version accepted", runCatching {
                manager.installFromArchive(incompatible, GpuDriverVendor.ADRENO)
            }.isFailure)
            assertEquals("Existing driver was damaged", installed.mainLibraryPath,
                manager.resolveUsableDriverPath(installed.mainLibraryPath))
        }
    }
}
