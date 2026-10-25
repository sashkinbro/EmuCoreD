package com.sbro.emucored.ui.saves

import android.content.Context
import android.graphics.BitmapFactory
import com.sbro.emucored.data.SaveStateEntryInfo
import com.sbro.emucored.data.SaveStateRepository
import com.sbro.emucored.ui.common.preloadGameCoverArt
import com.sbro.emucored.ui.common.invalidateCoverImage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal data class SaveStatePreview(val path: String, val aspectRatio: Float)
internal data class SaveManagerSnapshot(
    val entries: List<SaveStateEntryInfo>, val previews: Map<String, SaveStatePreview>
)

private val prepared = SaveManagerPreparedSnapshots<SaveManagerSnapshot>()
private val previewVersions = linkedMapOf<String, Pair<Long, Long>>()
private fun reconcilePreviewVersion(path: String) {
    val file = File(path)
    val version = file.length() to file.lastModified()
    synchronized(previewVersions) {
        if (previewVersions.remove(path) != version) invalidateCoverImage(path)
        previewVersions[path] = version
        while (previewVersions.size > 128) previewVersions.remove(previewVersions.keys.first())
    }
}
private fun preparationKey(context: Context, path: String?, title: String?, serial: String?) =
    listOf(context.applicationContext.filesDir.absolutePath, path, title, serial).joinToString("\u0000") { it.orEmpty() }

/** Await immediately before navigation so the destination's first composition has real slot data. */
suspend fun prepareSaveManager(
    context: Context, gamePath: String? = null, gameTitle: String? = null, gameSerial: String? = null
) {
    val key = preparationKey(context, gamePath, gameTitle, gameSerial)
    val request = prepared.begin(key)
    val snapshot = loadSaveManagerSnapshot(context.applicationContext, gamePath, gameTitle, gameSerial)
    currentCoroutineContext().ensureActive()
    prepared.publish(key, request, snapshot)
}

internal fun takePreparedSaveManager(context: Context, path: String?, title: String?, serial: String?) =
    prepared.take(preparationKey(context, path, title, serial))

internal fun invalidatePreparedSaveManagers() = prepared.clear()

internal suspend fun loadSaveManagerSnapshot(
    context: Context, path: String?, title: String?, serial: String?
): SaveManagerSnapshot = withContext(Dispatchers.IO) {
    val repository = SaveStateRepository(context)
    val job = currentCoroutineContext()
    val listed = repository.listEntries(path, title, serial)
    job.ensureActive()
    val entries = if (path.isNullOrBlank() && listed.isNotEmpty()) repository.enrichGlobalEntries(listed) else listed
    val previews = entries.mapNotNull { entry ->
        job.ensureActive()
        val image = repository.getPreviewImagePath(entry) ?: return@mapNotNull null
        reconcilePreviewVersion(image)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@mapNotNull null
        entry.absolutePath to SaveStatePreview(image, bounds.outWidth.toFloat() / bounds.outHeight)
    }.toMap()
    // Read all geometry cheaply, but decode only the first visible rows. Offscreen
    // previews retain their correct dimensions and use the normal shared loader.
    coroutineScope {
        val permits = Semaphore(2)
        entries.take(4).mapNotNull { previews[it.absolutePath] }.map { preview ->
            async { permits.withPermit { preloadGameCoverArt(context, preview.path) } }
        }.awaitAll()
    }
    job.ensureActive()
    // Do not publish a mix of metadata and previews from different save versions.
    if (entries.any { entry ->
            val file = File(entry.absolutePath)
            !file.isFile || file.length() != entry.sizeBytes || file.lastModified() != entry.lastModified
        }) throw IOException("Save states changed while preparing the screen")
    SaveManagerSnapshot(entries, previews)
}
