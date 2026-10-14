package com.sbro.emucored.data

import android.content.Context
import com.sbro.emucored.data.n3ds.N3dsCoverIndexRepository
import java.io.File

data class CoverCacheClearResult(
    val deletedFiles: Int,
    val freedBytes: Long,
    val failedFiles: Int
) {
    val fullyCleared: Boolean
        get() = failedFiles == 0
}

/**
 * Library artwork comes from the game itself: the SMDH icon embedded in the
 * image (see [com.sbro.emucored.core.N3dsGameMetadataReader.extractIcon]).
 * Nothing is downloaded, so an entry never shows artwork that belongs to a
 * different game. This class only keeps cache maintenance helpers and the
 * public cover URL used by external surfaces such as Discord presence.
 */
class CoverArtRepository(context: Context) {

    private val context = context.applicationContext

    private val coverCacheDirectory = File(this.context.cacheDir, "game-covers")
    private val remoteImageCacheDirectory = File(this.context.cacheDir, "remote-image-cache")

    /** Clears cached artwork downloads. User-selected covers live in filesDir and are preserved. */
    fun clearCache(): CoverCacheClearResult = clearDirectories(listOf(coverCacheDirectory))

    /** Clears all temporary image caches without touching library metadata or custom artwork. */
    fun clearAllTemporaryImageCaches(): CoverCacheClearResult =
        clearDirectories(listOf(coverCacheDirectory, remoteImageCacheDirectory))

    fun isManagedCoverCachePath(path: String?): Boolean =
        CoverCachePolicy.isPathInside(path, coverCacheDirectory)

    /** Public cover URL for external surfaces (Discord presence, cloud profiles). */
    fun buildPublicCoverUrl(
        serial: String?,
        @Suppress("UNUSED_PARAMETER") styleOverride: Int? = null,
        title: String? = null
    ): String? = N3dsCoverIndexRepository(context).find(serial, title, threeDimensional = false)?.url

    private fun clearDirectories(directories: List<File>): CoverCacheClearResult {
        var deletedFiles = 0
        var freedBytes = 0L
        var failedFiles = 0

        directories.distinctBy { it.absolutePath }.forEach { directory ->
            if (!directory.exists()) return@forEach
            val root = runCatching { directory.canonicalFile }.getOrNull() ?: run {
                failedFiles++
                return@forEach
            }
            directory.walkBottomUp().forEach { entry ->
                if (entry == directory) return@forEach
                val safeEntry = runCatching { entry.canonicalFile }.getOrNull()
                if (safeEntry == null || !CoverCachePolicy.isFileInside(safeEntry, root)) {
                    failedFiles++
                    return@forEach
                }
                if (entry.isFile) {
                    val size = entry.length()
                    if (runCatching { entry.delete() }.getOrDefault(false)) {
                        deletedFiles++
                        freedBytes += size
                    } else {
                        failedFiles++
                    }
                } else if (entry.isDirectory) {
                    // Remove only now-empty cache subdirectories; the two cache roots remain available.
                    runCatching { entry.delete() }
                }
            }
        }
        return CoverCacheClearResult(deletedFiles, freedBytes, failedFiles)
    }
}
