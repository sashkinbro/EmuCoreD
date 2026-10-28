package com.sbro.emucored.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

data class InstalledGpuDriver(
    val name: String,
    val mainLibrary: String,
    val mainLibraryPath: String,
    val isUsable: Boolean,
    val vendor: GpuDriverVendor = GpuDriverVendor.UNKNOWN
)

class GpuDriverManager(private val context: Context) {

    fun listInstalledDrivers(): List<InstalledGpuDriver> {
        val root = driversRoot()
        if (!root.exists()) return emptyList()
        return root.listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .mapNotNull { driverDir ->
                val mainLibrary = readMainLibraryName(driverDir) ?: return@mapNotNull null
                val mainLibraryFile = File(driverDir, mainLibrary)
                if (mainLibraryFile.isFile) {
                    ensureDriverLibraryPermissions(mainLibraryFile)
                }
                val vendor = GpuDriverCompatibility.installedDriverVendor(mainLibraryFile)
                InstalledGpuDriver(
                    name = driverDir.name,
                    mainLibrary = mainLibrary,
                    mainLibraryPath = mainLibraryFile.absolutePath,
                    isUsable = isSupportedPackage(driverDir) && isValidDriverLibrary(mainLibraryFile) &&
                        isCompatibleForCurrentDevice(mainLibraryFile, vendor),
                    vendor = vendor
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun installFromArchive(uri: Uri, expectedVendor: GpuDriverVendor? = null): String {
        val archiveName = queryDisplayName(uri)
            ?: uri.lastPathSegment
            ?: "custom-driver.zip"
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Could not open archive")
        return input.use { installFromArchive(it, archiveName, expectedVendor) }
    }

    fun installFromArchive(file: File, expectedVendor: GpuDriverVendor? = null): String {
        file.inputStream().use { input ->
            return installFromArchive(input, file.name, expectedVendor)
        }
    }

    fun remove(driverName: String) {
        val directory = File(driversRoot(), driverName)
        require(directory.canonicalFile.parentFile == driversRoot().canonicalFile && !driverName.startsWith('.'))
        directory.deleteRecursively()
    }

    fun readMainLibraryPath(driverName: String): String? {
        val driverDir = File(driversRoot(), driverName)
        if (driverDir.canonicalFile.parentFile != driversRoot().canonicalFile || driverName.startsWith('.')) return null
        val mainLibrary = readMainLibraryName(driverDir) ?: return null
        return File(driverDir, mainLibrary).absolutePath
    }

    fun resolveUsableDriverPath(preferredPath: String?): String? {
        if (!GpuDriverSupport.deviceSupported()) {
            return null
        }

        preferredPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.canonicalFile.toPath().startsWith(driversRoot().canonicalFile.toPath()) }
            ?.takeIf { file ->
                val relative = driversRoot().canonicalFile.toPath().relativize(file.canonicalFile.toPath())
                relative.nameCount >= 2 && isSupportedPackage(File(driversRoot(), relative.getName(0).toString())) &&
                    isValidDriverLibrary(file) && isVendorCompatible(file)
            }
            ?.let { file ->
                ensureDriverLibraryPermissions(file)
                return file.absolutePath
            }

        return null
    }

    fun isVendorCompatible(libraryFile: File): Boolean =
        isCompatibleForCurrentDevice(libraryFile, GpuDriverCompatibility.installedDriverVendor(libraryFile))

    fun isVendorCompatible(libraryPath: String?): Boolean {
        val file = libraryPath?.takeIf { it.isNotBlank() }?.let(::File) ?: return false
        return isVendorCompatible(file)
    }

    private fun installFromArchive(
        input: InputStream,
        archiveName: String,
        expectedVendor: GpuDriverVendor? = null
    ): String {
        return GpuDriverArchive.install(input, archiveName, driversRoot()) { targetDir, extractedFiles ->
            val metadataFile = extractedFiles.firstOrNull { it == "meta.json" }?.let { File(targetDir, it) }
            val metadata = metadataFile?.let {
                require(it.length() <= 65536) { "Driver metadata is too large" }
                JSONObject(it.readText())
            }
            val minimumApi = metadata?.optInt("minApi", 0) ?: 0
            require(minimumApi <= Build.VERSION.SDK_INT) { "Driver requires a newer Android version" }
            val declaredLibrary = metadata?.optString("libraryName")?.takeIf { it.isNotBlank() }
            val selectedDriver = if (declaredLibrary != null) {
                extractedFiles.singleOrNull { it == declaredLibrary }
            } else selectMainDriverFile(extractedFiles)
                ?: error("Archive does not contain a Vulkan driver file")
            requireNotNull(selectedDriver) { "Declared Vulkan driver is missing" }
            // Validate the ELF header and architecture before a native loader sees it.
            val driverLibrary = File(targetDir, selectedDriver)
            if (!isValidDriverLibrary(driverLibrary)) error("Archive contains an invalid Vulkan driver file")

            val detectedVendor = expectedVendor
                ?.takeIf { it != GpuDriverVendor.UNKNOWN }
                ?: GpuDriverCompatibility.vendorOfDriverLibrary(driverLibrary)
            val deviceVendor = detectedDeviceVendor()
            if (!GpuDriverCompatibility.isInstalledDriverCompatible(
                    selectedDriver, detectedVendor, deviceVendor, DeviceGpuInfoProvider.get(context)
                )
            ) {
                error("This driver is for ${detectedVendor.displayName} GPUs and cannot be used on this " +
                    "${deviceVendor.displayName} device")
            }

            File(targetDir, "driver_name.txt").writeText("$selectedDriver\n")
            GpuDriverCompatibility.writeInstalledDriverVendor(driverLibrary, detectedVendor)
            extractedFiles.filter { it.endsWith(".so", ignoreCase = true) }
                .forEach { ensureDriverLibraryPermissions(File(targetDir, it)) }
        }
    }

    private fun driversRoot(): File = File(context.filesDir, "driver")

    private fun isSupportedPackage(driverDir: File): Boolean = runCatching {
        val metadata = File(driverDir, "meta.json")
        !metadata.exists() || (metadata.length() <= 65536 &&
            JSONObject(metadata.readText()).optInt("minApi", 0) <= Build.VERSION.SDK_INT)
    }.getOrDefault(false)

    private fun isCompatibleForCurrentDevice(libraryFile: File, vendor: GpuDriverVendor): Boolean =
        GpuDriverCompatibility.isInstalledDriverCompatible(
            mainLibraryName = libraryFile.name,
            driverVendor = vendor,
            deviceVendor = detectedDeviceVendor(),
            deviceGpu = DeviceGpuInfoProvider.get(context)
        )

    private fun detectedDeviceVendor(): GpuDriverVendor =
        GpuDriverCompatibility.vendorOfGpu(DeviceGpuInfoProvider.get(context).model)
            .takeIf { it != GpuDriverVendor.UNKNOWN }
            ?: GpuDriverCompatibility.deviceVendor().takeIf { it != GpuDriverVendor.UNKNOWN }
            ?: if (GpuDriverSupport.deviceSupported()) GpuDriverVendor.ADRENO else GpuDriverVendor.UNKNOWN

    private fun readMainLibraryName(driverDir: File): String? {
        val metadataFile = File(driverDir, "driver_name.txt")
        if (!metadataFile.isFile) return null
        return metadataFile.readText()
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() && File(driverDir, it).canonicalFile.toPath().startsWith(driverDir.canonicalFile.toPath()) }
    }

    private fun ensureDriverLibraryPermissions(file: File) {
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.setExecutable(true, true)
    }

    private fun selectMainDriverFile(extractedFiles: List<String>): String? {
        val sharedLibraries = extractedFiles
            .filter { it.endsWith(".so", ignoreCase = true) }
        if (sharedLibraries.isEmpty()) return null

        return sharedLibraries.firstOrNull { file ->
            val name = file.substringAfterLast('/')
            name.equals("libvulkan.so", ignoreCase = true)
        } ?: sharedLibraries.firstOrNull { file ->
            val name = file.substringAfterLast('/')
            name.startsWith("vulkan.", ignoreCase = true) || name.startsWith("libvulkan.", ignoreCase = true)
        } ?: sharedLibraries.firstOrNull { file ->
            file.contains("vulkan", ignoreCase = true)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }
}

internal fun isValidDriverLibrary(file: File): Boolean {
    if (!file.isFile || file.length() < 64L) return false
    return try {
        file.inputStream().use { input ->
            val header = ByteArray(64)
            if (input.read(header) != header.size) return false
            val bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val programOffset = bytes.getLong(32)
            val programSize = bytes.getShort(54).toInt() and 0xffff
            val programCount = bytes.getShort(56).toInt() and 0xffff
            header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[4] == 2.toByte() && header[5] == 1.toByte() && header[6] == 1.toByte() &&
                bytes.getShort(16).toInt() == 3 && (bytes.getShort(18).toInt() and 0xffff) == 183 &&
                programOffset >= 64 && programSize >= 56 && programCount > 0 &&
                programOffset <= file.length() && programSize.toLong() * programCount <= file.length() - programOffset
        }
    } catch (_: Exception) {
        false
    }
}
