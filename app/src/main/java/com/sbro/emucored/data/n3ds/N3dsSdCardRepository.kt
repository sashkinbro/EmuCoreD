package com.sbro.emucored.data

import android.content.Context
import android.net.Uri
import com.sbro.emucored.core.EmulatorStorage
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class N3dsSavedGame(val name: String, val bytes: Long)

/**
 * Directory-backed manager for the virtual SD card (sdmc) that Azahar exposes
 * to games. Installed saves live under
 * `sdmc/Nintendo 3DS/<id0>/<id1>/title/<title id>`, so the manager lists those
 * title folders and backs the whole card up as a ZIP.
 */
class N3dsSdCardRepository(private val context: Context) {
    val saveDirectory: File
        get() = EmulatorStorage.sdmcDir(
            context, AppPreferences(context).getEmulatorDataPathSync())

    fun ensureSdCard(): Boolean = runCatching {
        val root = saveDirectory
        (root.isDirectory || root.mkdirs()) && root.isDirectory
    }.getOrDefault(false)

    /** Every installed title folder on the virtual SD card. */
    private fun titleDirectories(): List<File> {
        val nintendo = File(saveDirectory, "Nintendo 3DS")
        val titles = ArrayList<File>()
        nintendo.listFiles()?.filter(File::isDirectory)?.forEach { id0 ->
            id0.listFiles()?.filter(File::isDirectory)?.forEach { id1 ->
                val titleRoot = File(id1, "title")
                titleRoot.listFiles()?.filter(File::isDirectory)?.forEach { titles.add(it) }
            }
        }
        return titles
    }

    fun saves(): List<N3dsSavedGame> = titleDirectories()
        .map { directory ->
            N3dsSavedGame(directory.name, directory.walkTopDown()
                .filter(File::isFile).sumOf(File::length))
        }
        .sortedBy { it.name.lowercase() }

    fun backup(uri: Uri): Boolean = runCatching {
        val root = saveDirectory.canonicalFile
        context.contentResolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(output).use { zip ->
                root.walkTopDown().filter(File::isFile).forEach { file ->
                    val relative = file.canonicalFile.relativeTo(root).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry("Nintendo 3DS/$relative"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } != null
    }.getOrDefault(false)

    /** Import a card backup. Existing files are preserved. */
    fun restore(uri: Uri): Boolean = runCatching {
        val root = saveDirectory.canonicalFile
        val buffer = ByteArray(64 * 1024)
        var entries = 0
        var totalBytes = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries <= 20_000)
                    val raw = entry.name.replace('\\', '/')
                    val relative = when {
                        raw.startsWith("Nintendo 3DS/") -> raw.removePrefix("Nintendo 3DS/")
                        raw.startsWith("sdmc/") -> raw.removePrefix("sdmc/")
                        raw.startsWith("3ds/") -> raw.removePrefix("3ds/")
                        else -> raw
                    }
                    require(relative.isNotBlank())
                    val destination = File(root, relative).canonicalFile
                    require(destination.toPath().startsWith(root.toPath()))
                    if (entry.isDirectory) {
                        destination.mkdirs()
                    } else {
                        destination.parentFile?.mkdirs()
                        // Never clobber a newer save during a restore.
                        if (destination.exists()) {
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                totalBytes += count
                                require(totalBytes <= 1024L * 1024 * 1024)
                            }
                        } else {
                            destination.outputStream().use { output ->
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    totalBytes += count
                                    require(totalBytes <= 1024L * 1024 * 1024)
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } != null && entries > 0
    }.getOrDefault(false)
}
