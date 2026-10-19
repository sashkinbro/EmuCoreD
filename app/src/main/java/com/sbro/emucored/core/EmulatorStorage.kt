package com.sbro.emucored.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

enum class EmulatorDataLocation {
    INTERNAL,
    SD_CARD
}

object EmulatorStorage {
    private val verifiedCustomRoots = ConcurrentHashMap.newKeySet<String>()


    data class StandardDataRoot(
        val directory: File,
        val preferencePath: String?
    )

    private val runtimeSubdirectories = listOf(
        "sstates",
        "azahar",
        "logs"
    )

    /** The user-selected emulator data root (external app storage by default). */
    fun dataRoot(context: Context, customRootPath: String? = null): File =
        root(context, customRootPath)

    /**
     * Azahar's active user directory. The EmuCoreD native bridge receives this
     * path directly, so NAND, the virtual SD card (sdmc), states, cheats and
     * textures are all rooted here.
     */
    fun azaharUserDir(
        context: Context,
        @Suppress("UNUSED_PARAMETER") customRootPath: String? = null
    ): File = coreUserDir(context)

    /** The user directory handed to the core before a session starts. */
    fun azaharSaveRoot(
        context: Context,
        @Suppress("UNUSED_PARAMETER") customRootPath: String? = null
    ): File = coreUserDir(context)

    /** The virtual SD card the core exposes as `sdmc:/` to games and homebrew. */
    fun sdmcDir(context: Context, customRootPath: String? = null): File =
        File(azaharUserDir(context, customRootPath), "sdmc").apply { mkdirs() }

    /**
     * Directory the native Azahar bridge actually uses as its user directory.
     *
     * The libretro build appended "/Azahar" to the save root; the EmuCoreD
     * native bridge receives [CoreRuntime.userDirectory] directly, so its
     * cheats, states and config live in `files/azahar`.
     */
    fun coreUserDir(context: Context): File =
        File(context.filesDir, "azahar").apply { mkdirs() }

    /** Directory the core writes its own save states into (`<TITLEID>.<slot>.cst`). */
    fun coreStatesDir(context: Context): File =
        File(coreUserDir(context), "states").apply { mkdirs() }

    /** The emulated NAND (installed titles, settings and system saves). */
    fun nandDir(context: Context, customRootPath: String? = null): File =
        File(azaharUserDir(context, customRootPath), "nand").apply { mkdirs() }

    /** System archives shared fonts, Mii data and other optional system files. */
    fun sysDataDir(context: Context, customRootPath: String? = null): File =
        File(azaharUserDir(context, customRootPath), "sysdata").apply { mkdirs() }

    private fun defaultRoot(context: Context): File {
        return context.getExternalFilesDir(null) ?: context.filesDir
    }

    /**
     * Returns the app-specific directory on removable/secondary storage, if Android exposes one.
     * Index zero is the primary external storage used by [defaultRoot]; secondary entries are SD
     * cards or other removable volumes. No broad storage permission is required for these paths.
     */
    fun sdCardRoot(context: Context): File? = runCatching {
        findSecondaryExternalFilesDir(context.getExternalFilesDirs(null))
    }.getOrNull()

    fun prepareStandardDataRoot(
        context: Context,
        location: EmulatorDataLocation
    ): StandardDataRoot? {
        val directory = when (location) {
            EmulatorDataLocation.INTERNAL -> defaultRoot(context)
            EmulatorDataLocation.SD_CARD -> sdCardRoot(context) ?: return null
        }
        if (!prepareRoot(directory)) return null

        return StandardDataRoot(
            directory = directory,
            preferencePath = if (location == EmulatorDataLocation.INTERNAL) null else directory.absolutePath
        )
    }

    fun selectedStandardLocation(
        preferencePath: String?,
        sdCardPath: String?
    ): EmulatorDataLocation? = when {
        preferencePath.isNullOrBlank() -> EmulatorDataLocation.INTERNAL
        !sdCardPath.isNullOrBlank() && File(preferencePath).absolutePath == File(sdCardPath).absolutePath -> {
            EmulatorDataLocation.SD_CARD
        }
        else -> null
    }

    internal fun findSecondaryExternalFilesDir(directories: Array<File?>): File? =
        directories.asSequence().drop(1).filterNotNull().firstOrNull()

    private fun root(context: Context, customRootPath: String? = null): File {
        val customRoot = customRootPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        // Selection performs the destructive write probe on Dispatchers.IO. Normal directory
        // access must not repeat create/delete probes because repositories can resolve paths from
        // the main thread while rendering UI.
        if (customRoot != null && isPreparedDirectoryTree(customRoot)) {
            return customRoot
        }

        return defaultRoot(context).apply { ensureRootDirectories(this) }
    }

    fun prepareCustomDataRoot(customRootPath: String?): Boolean {
        val customRoot = customRootPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: return false
        return prepareRoot(customRoot)
    }

    fun saveStatesDir(
        context: Context,
        @Suppress("UNUSED_PARAMETER") customRootPath: String? = null
    ): File = coreStatesDir(context)

    fun memoryCardsDir(context: Context, customRootPath: String? = null): File =
        File(root(context, customRootPath), "memcards").apply { mkdirs() }

    /** Azahar's custom texture packs: `<user dir>/load/textures/<title id>`. */
    fun texturesDir(context: Context, customRootPath: String? = null): File =
        File(azaharUserDir(context, customRootPath), "load/textures").apply { mkdirs() }

    fun cheatsDir(context: Context, @Suppress("UNUSED_PARAMETER") customRootPath: String? = null): File =
        File(coreUserDir(context), "cheats").apply { mkdirs() }

    fun patchesDir(context: Context, customRootPath: String? = null): File =
        File(azaharUserDir(context, customRootPath), "patches").apply { mkdirs() }

    fun logDir(context: Context, customRootPath: String? = null): File =
        File(root(context, customRootPath), "logs").apply { mkdirs() }

    fun appStateDir(context: Context): File = File(root(context), "app-state").apply { mkdirs() }

    fun importedCheatsDir(context: Context): File = File(appStateDir(context), "imported-cheats").apply { mkdirs() }

    private fun prepareRoot(root: File): Boolean {
        val rootPath = root.absolutePath
        if (!ensureRootDirectories(root)) {
            verifiedCustomRoots.remove(rootPath)
            return false
        }
        if (rootPath in verifiedCustomRoots) return true

        val writable = canWriteProbe(root) && runtimeSubdirectories.all { subdirectory ->
            canWriteProbe(File(root, subdirectory))
        }
        if (writable) verifiedCustomRoots.add(rootPath)
        return writable
    }

    private fun ensureRootDirectories(root: File): Boolean {
        return runCatching {
            if (!root.exists() && !root.mkdirs()) return false
            if (!root.isDirectory) return false

            for (subdirectory in runtimeSubdirectories) {
                val dir = File(root, subdirectory)
                if (!dir.exists() && !dir.mkdirs()) return false
                if (!dir.isDirectory) return false
            }
            true
        }.getOrDefault(false)
    }

    private fun isPreparedDirectoryTree(root: File): Boolean {
        if (!ensureRootDirectories(root) || !root.canWrite()) return false
        return runtimeSubdirectories.all { File(root, it).canWrite() }
    }

    private fun canWriteProbe(directory: File): Boolean {
        val probe = File(directory, ".emucored-write-test")
        return runCatching {
            FileOutputStream(probe, false).use { stream ->
                stream.write(1)
            }
            if (probe.exists() && !probe.delete()) {
                probe.deleteOnExit()
            }
            true
        }.getOrDefault(false)
    }
}
