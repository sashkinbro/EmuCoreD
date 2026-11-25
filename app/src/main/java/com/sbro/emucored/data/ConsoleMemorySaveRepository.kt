// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ConsoleMemorySaveItem(
    val id: String,
    val filePath: String,
    val title: String,
    val consoleId: String,
    val consoleShort: String,
    val sizeBytes: Long,
    val lastModified: Long
) {
    fun formattedSize(): String = when {
        sizeBytes < 1024L -> "$sizeBytes B"
        sizeBytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", sizeBytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", sizeBytes / (1024.0 * 1024.0))
    }

    fun formattedTime(): String {
        if (lastModified <= 0L) return ""
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastModified))
    }
}

object ConsoleMemorySaveRepository {

    private val cartridgeExtensions = setOf("sav", "srm", "eep", "fla", "sra", "mpk")
    private val dsExtensions = setOf("sav", "dsv")
    private val gcExtensions = setOf("raw", "gci", "gcp")
    private val wiiSaveNames = setOf("data.bin", "banner.bin")
    private val n3dsSaveNames = setOf("00000001.sav", "main", "system.dat")

    fun listAll(context: Context): List<ConsoleMemorySaveItem> {
        val filesDir = context.filesDir
        val items = ArrayList<ConsoleMemorySaveItem>()

        // 1. Unified console cartridge battery saves (NES, SNES, GB, GBC, GBA, N64)
        val consolesDir = File(filesDir, "consoles")
        val excludedPrefixes = listOf(
            File(consolesDir, "states").canonicalPath,
            File(consolesDir, "dolphin").canonicalPath
        )
        if (consolesDir.isDirectory) {
            consolesDir.walkTopDown()
                .onEnter { dir ->
                    val cp = runCatching { dir.canonicalPath }.getOrNull() ?: return@onEnter true
                    excludedPrefixes.none { cp == it || cp.startsWith(it + File.separator) }
                }
                .filter {
                    it.isFile &&
                        it.length() > 0L &&
                        it.extension.lowercase(Locale.ROOT) in cartridgeExtensions
                }
                .forEach { file ->
                    val (cid, cshort) = inferConsoleFromPath(file.absolutePath)
                    items += ConsoleMemorySaveItem(
                        id = file.absolutePath,
                        filePath = file.absolutePath,
                        title = "$cshort ${formatTitle(file.nameWithoutExtension)}",
                        consoleId = cid,
                        consoleShort = cshort,
                        sizeBytes = file.length(),
                        lastModified = file.lastModified()
                    )
                }
        }

        // 2. Nintendo DS battery saves under files/ds/
        val dsDir = File(filesDir, "ds")
        if (dsDir.isDirectory) {
            dsDir.walkTopDown()
                .filter { it.isFile && it.length() > 0L && it.extension.lowercase(Locale.ROOT) in dsExtensions }
                .forEach { file ->
                    items += ConsoleMemorySaveItem(
                        id = file.absolutePath,
                        filePath = file.absolutePath,
                        title = formatTitle(file.nameWithoutExtension),
                        consoleId = "nds",
                        consoleShort = "DS",
                        sizeBytes = file.length(),
                        lastModified = file.lastModified()
                    )
                }
        }

        // 3. Dolphin GameCube memory cards & GCI folders (files/consoles/dolphin/GC or files/dolphin/GC)
        for (rel in listOf("consoles/dolphin/GC", "dolphin/GC")) {
            val gcDir = File(filesDir, rel)
            if (!gcDir.isDirectory) continue
            gcDir.walkTopDown()
                .filter { it.isFile && it.length() > 0L && it.extension.lowercase(Locale.ROOT) in gcExtensions }
                .forEach { file ->
                    items += ConsoleMemorySaveItem(
                        id = file.absolutePath,
                        filePath = file.absolutePath,
                        title = "GameCube ${formatTitle(file.nameWithoutExtension)}",
                        consoleId = "gamecube",
                        consoleShort = "GC",
                        sizeBytes = file.length(),
                        lastModified = file.lastModified()
                    )
                }
        }

        // 4. Dolphin Wii title save data ONLY under title/<tid>/<tid>/data/
        for (rel in listOf("consoles/dolphin/Wii/title", "dolphin/Wii/title")) {
            val wiiDir = File(filesDir, rel)
            if (!wiiDir.isDirectory) continue
            wiiDir.walkTopDown()
                .filter { file ->
                    if (!file.isFile || file.length() <= 0L) return@filter false
                    val lower = file.name.lowercase(Locale.ROOT)
                    val parentName = file.parentFile?.name?.lowercase(Locale.ROOT)
                    parentName == "data" &&
                        (lower in wiiSaveNames || file.extension.lowercase(Locale.ROOT) == "sav")
                }
                .forEach { file ->
                    val titleId = file.parentFile?.parentFile?.name ?: file.name
                    items += ConsoleMemorySaveItem(
                        id = file.absolutePath,
                        filePath = file.absolutePath,
                        title = "Wii $titleId (${file.name})",
                        consoleId = "wii",
                        consoleShort = "WII",
                        sizeBytes = file.length(),
                        lastModified = file.lastModified()
                    )
                }
        }

        // 5. Azahar 3DS virtual SD card save data under files/azahar/sdmc/Nintendo 3DS/
        val n3dsDir = File(filesDir, "azahar/sdmc/Nintendo 3DS")
        if (n3dsDir.isDirectory) {
            n3dsDir.walkTopDown()
                .filter { file ->
                    if (!file.isFile || file.length() <= 0L) return@filter false
                    val lower = file.name.lowercase(Locale.ROOT)
                    val parentName = file.parentFile?.name?.lowercase(Locale.ROOT)
                    (parentName == "data" || parentName == "00000001") &&
                        (lower in n3dsSaveNames || file.extension.lowercase(Locale.ROOT) == "sav")
                }
                .forEach { file ->
                    val folderLabel = file.parentFile?.parentFile?.name ?: "3DS Save"
                    items += ConsoleMemorySaveItem(
                        id = file.absolutePath,
                        filePath = file.absolutePath,
                        title = "3DS $folderLabel (${file.name})",
                        consoleId = "3ds",
                        consoleShort = "3DS",
                        sizeBytes = file.length(),
                        lastModified = file.lastModified()
                    )
                }
        }

        return items
            .distinctBy { it.filePath }
            .sortedByDescending { it.lastModified }
    }

    fun backup(context: Context, filePath: String): File? = runCatching {
        val source = File(filePath)
        val rootCanonical = context.filesDir.canonicalFile
        val sourceCanonical = source.canonicalFile
        if (!sourceCanonical.isFile || !sourceCanonical.path.startsWith(rootCanonical.path + File.separator)) {
            return@runCatching null
        }
        val backupDir = File(context.filesDir, "backups/nintendo-saves").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = sourceCanonical.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        val dest = File(backupDir, "${stamp}_$safeName")
        sourceCanonical.inputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        dest
    }.getOrNull()

    fun delete(context: Context, filePath: String): Boolean = runCatching {
        val target = File(filePath).canonicalFile
        val rootCanonical = context.filesDir.canonicalFile
        if (!target.path.startsWith(rootCanonical.path + File.separator)) return@runCatching false
        target.isFile && target.delete()
    }.getOrDefault(false)

    private fun inferConsoleFromPath(path: String): Pair<String, String> {
        val norm = path.replace('\\', '/').lowercase(Locale.ROOT)
        return when {
            "/n64/" in norm || norm.endsWith(".eep") || norm.endsWith(".fla") || norm.endsWith(".sra") || norm.endsWith(".mpk") ->
                "n64" to "N64"
            "/snes/" in norm -> "snes" to "SNES"
            "/nes/" in norm -> "nes" to "NES"
            "/gba/" in norm -> "gba" to "GBA"
            "/gbc/" in norm -> "gbc" to "GBC"
            "/gb/" in norm -> "gb" to "GB"
            "/nds/" in norm || "/ds/" in norm -> "nds" to "DS"
            "/gamecube/" in norm || "/gc/" in norm -> "gamecube" to "GC"
            "/wii/" in norm -> "wii" to "WII"
            "/3ds/" in norm -> "3ds" to "3DS"
            else -> "nintendo" to "SAV"
        }
    }

    private fun formatTitle(raw: String): String =
        raw.replace('_', ' ').trim().ifBlank { "Save Data" }
}
