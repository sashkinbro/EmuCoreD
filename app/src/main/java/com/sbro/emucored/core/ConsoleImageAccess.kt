// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.core.net.toUri
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Owns a seekable SAF image for the entire guest lifetime, avoiding multi-GB copies. */
object ConsoleImageAccess {
    class Image internal constructor(
        val path: String,
        val displayName: String,
        private val descriptor: ParcelFileDescriptor? = null,
        private val temporary: File? = null
    ) : Closeable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { descriptor?.close() } finally { temporary?.delete() }
        }
    }

    suspend fun prepare(context: Context, source: String): Image? {
        var owned: Image? = null
        fun retain(image: Image) = image.also { owned = it }
        try {
            return withContext(Dispatchers.IO) {
        val path = source.removePrefix("!").let { if (it.startsWith("file://")) it.toUri().path.orEmpty() else it }
        if (path.isBlank()) return@withContext null
        if (!path.startsWith("content://")) {
            val direct = File(path)
            if (direct.isFile && direct.canRead()) return@withContext retain(Image(direct.absolutePath, direct.name))
        }
        val uri = if (path.startsWith("content://")) path.toUri()
            else DocumentPathResolver.findAccessibleTreeUriForRawPath(context, path) ?: return@withContext null
        val displayName = DocumentPathResolver.getDisplayName(context, uri.toString())
        val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (descriptor != null) {
            val seekable = runCatching {
                Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
                File("/proc/self/fd/${descriptor.fd}").isFile
            }.getOrDefault(false)
            if (seekable) return@withContext retain(Image("/proc/self/fd/${descriptor.fd}", displayName, descriptor))
            descriptor.close()
        }
        // A cloud provider may expose a pipe. Materialize just that image in an
        // owned temporary file; never reuse an unverified copy after a new launch.
        val directory = File(context.cacheDir, "console-images").apply { mkdirs() }
        val extension = displayName.substringAfterLast('.', "rom").filter(Char::isLetterOrDigit).take(12)
        val temporary = File.createTempFile("image-", ".$extension", directory)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext null.also { temporary.delete() }
            input.use { stream ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = stream.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (temporary.length() == 0L) { temporary.delete(); null }
            else retain(Image(temporary.path, displayName, temporary = temporary))
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }
        } catch (error: Throwable) {
            owned?.close()
            throw error
        }
    }
}
