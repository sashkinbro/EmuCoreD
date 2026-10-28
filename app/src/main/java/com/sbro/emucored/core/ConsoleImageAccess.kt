// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core

import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Owns a seekable SAF image for the entire guest lifetime, avoiding multi-GB copies. */
object ConsoleImageAccess {
    private const val MAX_ARCHIVE_COPY_BYTES = 16L * 1024 * 1024 * 1024
    class Image internal constructor(
        val path: String,
        val displayName: String,
        private val descriptor: ParcelFileDescriptor? = null,
        private val temporary: File? = null,
        private val owners: List<Image> = emptyList()
    ) : Closeable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                owners.forEach { runCatching { it.close() } }
                descriptor?.close()
            } finally { deleteTemporary(temporary) }
        }
    }

    suspend fun prepare(
        context: Context,
        source: String,
        materializeContent: Boolean = false
    ): Image? = prepareOwned(context, source, null, materializeContent)

    /** Testable resolver boundary; every document is still opened through its real provider. */
    internal suspend fun prepareWithSiblingDocuments(
        context: Context, source: String, siblings: List<Pair<String, String>>
    ): Image? = prepareOwned(context, source, siblings, false)

    private suspend fun prepareOwned(
        context: Context,
        source: String,
        siblings: List<Pair<String, String>>?,
        materializeContent: Boolean
    ): Image? {
        var owned: Image? = null
        try {
            return withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                prepareBlocking(context, source, siblings, materializeContent) { job.ensureActive() }.also { owned = it }
            }
        } catch (error: Throwable) {
            owned?.close()
            throw error
        }
    }

    /** Scanning already runs off the UI thread; retain all parts for the native volume probe. */
    internal fun prepareForProbe(
        context: Context, source: String,
        siblingDocuments: List<Pair<String, String>>? = null,
        checkActive: () -> Unit = {}
    ): Image? = prepareBlocking(context, source, siblingDocuments, false, checkActive)

    /** ZipFile cannot reliably reopen a DocumentsProvider descriptor via /proc/self/fd. */
    internal fun prepareArchiveForProbe(context: Context, source: String, checkActive: () -> Unit = {}): Image? =
        prepareSingle(context, source, true, checkActive, MAX_ARCHIVE_COPY_BYTES)
    private fun prepareBlocking(
        context: Context,
        source: String,
        siblings: List<Pair<String, String>>?,
        materializeContent: Boolean,
        checkActive: () -> Unit
    ): Image? {
        checkActive()
        val archivedGame = ArchiveGameIdentity.parse(source)
        if (archivedGame != null) {
            val archive = prepareSingle(context, archivedGame.archive, true, checkActive, MAX_ARCHIVE_COPY_BYTES) ?: return null
            var retained = false
            try {
                val directory = File(context.cacheDir, "console-images").apply { mkdirs() }
                val staged = GameZipArchive.extract(
                    File(archive.path), archivedGame.entry, directory, 16L * 1024 * 1024 * 1024, checkActive
                )
                val result = Image(staged.path, archivedGame.entry.substringAfterLast('/'),
                    temporary = staged, owners = listOf(archive))
                retained = true
                return result
            } finally {
                if (!retained) archive.close()
            }
        }
        val main = prepareSingle(context, source, materializeContent, checkActive) ?: return null
        val wbfsHeader = runCatching { File(main.path).inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic.contentEquals("WBFS".toByteArray())
        } }.getOrDefault(false)
        val multipartName = if (wbfsHeader && !main.displayName.endsWith(".wbfs", true))
            main.displayName.substringBeforeLast('.', main.displayName) + ".wbfs" else main.displayName
        if (!ConsoleImageParts.isMultipart(multipartName)) {
            if (wbfsHeader) { main.close(); return null }
            return main
        }
        val images = mutableListOf(main)
        var directory: File? = null
        var retained = false
        try {
            checkActive()
            val available = siblings ?: siblingSources(context, source, main.displayName, checkActive)
            val names = ConsoleImageParts.parts(multipartName, available.map { it.first }) ?: return null
            for (name in names.drop(1)) {
                checkActive()
                val child = available.singleOrNull { it.first == name } ?: return null
                images += prepareSingle(context, child.second, false, checkActive) ?: return null
            }
            if (multipartName.endsWith(".wbfs", true)) {
                checkActive()
                // Dolphin validates the total header size. Reject a missing part before
                // boot instead of accidentally treating a descriptor number as a sibling.
                val header = File(main.path).inputStream().use { input ->
                    val bytes = ByteArray(10)
                    var count = 0
                    while (count < bytes.size) {
                        val read = input.read(bytes, count, bytes.size - count)
                        if (read <= 0) break
                        count += read
                    }
                    bytes.copyOf(count)
                }
                if (header.size < 10 || !header.copyOfRange(0, 4).contentEquals("WBFS".toByteArray())) return null
                val shift = header[8].toInt() and 255
                if (shift !in 9..31) return null
                var sectors = 0L
                for (index in 4..7) sectors = (sectors shl 8) or (header[index].toLong() and 255)
                if (sectors == 0L || images.sumOf { File(it.path).length() } != sectors * (1L shl shift)) return null
            }
            val parent = File(context.cacheDir, "console-images").apply { mkdirs() }
            directory = java.nio.file.Files.createTempDirectory(parent.toPath(), "parts-").toFile()
            images.forEachIndexed { index, image ->
                checkActive()
                val target = File(directory, ConsoleImageParts.nativeName(multipartName, index))
                if (runCatching { Os.symlink(image.path, target.path) }.isFailure) {
                    // Pipes and providers that disallow named links still work through
                    // an owned copy. Never modify the source or reuse stale staged data.
                    File(image.path).inputStream().use { input ->
                        target.outputStream().use { output -> copy(input, output, checkActive) }
                    }
                }
            }
            val result = Image(File(directory, ConsoleImageParts.nativeName(multipartName, 0)).path,
                main.displayName, temporary = directory, owners = images.toList())
            retained = true
            return result
        } finally {
            if (!retained) {
                images.forEach { runCatching { it.close() } }
                deleteTemporary(directory)
            }
        }
    }

    private fun prepareSingle(
        context: Context,
        source: String,
        materializeContent: Boolean,
        checkActive: () -> Unit,
        copyLimit: Long = Long.MAX_VALUE
    ): Image? {
        checkActive()
        val path = source.removePrefix("!").let { if (it.startsWith("file://")) it.toUri().path.orEmpty() else it }
        if (path.isBlank()) return null
        if (!path.startsWith("content://")) {
            val direct = File(path)
            if (direct.isFile && direct.canRead()) return Image(direct.absolutePath, direct.name)
        }
        val uri = if (path.startsWith("content://")) path.toUri()
            else DocumentPathResolver.findAccessibleTreeUriForRawPath(context, path) ?: return null
        val displayName = DocumentPathResolver.getDisplayName(context, uri.toString())
        checkActive()
        if (!materializeContent) {
            val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            if (descriptor != null) {
                val seekable = runCatching {
                    val stat = android.system.Os.fstat(descriptor.fileDescriptor)
                    val regular = (stat.st_mode and android.system.OsConstants.S_IFMT) ==
                        android.system.OsConstants.S_IFREG
                    if (!regular) false else {
                        android.system.Os.lseek(descriptor.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
                        true
                    }
                }.getOrDefault(false)
                if (seekable) return Image("/proc/self/fd/${descriptor.fd}", displayName, descriptor)
                descriptor.close()
            }
        }
        // A cloud provider may expose a pipe. Materialize just that image in an
        // owned temporary file; never reuse an unverified copy after a new launch.
        val directory = File(context.cacheDir, "console-images").apply { mkdirs() }
        val extension = displayName.substringAfterLast('.', "rom").filter(Char::isLetterOrDigit).take(12)
        val temporary = File.createTempFile("image-", ".$extension", directory)
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null.also { temporary.delete() }
            input.use { stream ->
                temporary.outputStream().use { output ->
                    copy(stream, output, checkActive, copyLimit)
                }
            }
            if (temporary.length() == 0L) { temporary.delete(); null }
            else Image(temporary.path, displayName, temporary = temporary)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun copy(input: java.io.InputStream, output: java.io.OutputStream, checkActive: () -> Unit,
                     maxBytes: Long = Long.MAX_VALUE) {
        val buffer = ByteArray(256 * 1024)
        var copied = 0L
        while (true) {
            checkActive()
            val read = input.read(buffer)
            if (read < 0) return
            require(read.toLong() <= maxBytes - copied) { "Image is too large" }
            output.write(buffer, 0, read)
            copied += read
        }
    }

    private fun deleteTemporary(path: File?) {
        if (path == null) return
        // Staging directories are flat. Unlink children directly: recursive
        // traversal could follow a source link if its target becomes a directory.
        if (path.isDirectory) path.listFiles().orEmpty().forEach { it.delete() }
        path.delete()
    }

    private fun siblingSources(context: Context, source: String, name: String, checkActive: () -> Unit): List<Pair<String, String>> {
        checkActive()
        val raw = source.removePrefix("!").let { if (it.startsWith("file://")) it.toUri().path.orEmpty() else it }
        if (!raw.startsWith("content://")) {
            val local = File(raw)
            if (local.isFile && local.canRead()) return local.parentFile?.listFiles().orEmpty()
                .filter { checkActive(); it.isFile }.map { it.name to it.path }
        }
        val uri = if (raw.startsWith("content://")) raw.toUri()
            else DocumentPathResolver.findAccessibleTreeUriForRawPath(context, raw) ?: return listOf(name to source)
        val targetId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val preferences = context.getSharedPreferences("console-image-parents", Context.MODE_PRIVATE)
        val parent = preferences.getString(parentKey(uri.toString()), null)?.toUri()
        if (parent != null && parent.authority == uri.authority && DocumentsContract.isTreeUri(parent)) {
            checkActive()
            val known = documentChildren(context, parent, checkActive)
            if (known.any { sameDocument(it.second.toUri(), uri, targetId) }) return known
        }
        val roots = buildList {
            if (DocumentsContract.isTreeUri(uri)) add(uri)
            addAll(context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri })
        }.filter { it.authority == uri.authority }.distinct()
        val directories = java.util.ArrayDeque<Pair<DocumentFile, Int>>()
        for (root in roots) runCatching { DocumentFile.fromTreeUri(context, root) }.getOrNull()
            ?.let { directories.add(it to 0) }
        var checked = 0
        while (directories.isNotEmpty() && checked++ < 96) {
            checkActive()
            val (parent, depth) = directories.removeFirst()
            val children = runCatching { parent.listFiles().toList() }.getOrDefault(emptyList())
            if (children.any { child -> checkActive(); child.uri == uri || (targetId != null &&
                    runCatching { DocumentsContract.getDocumentId(child.uri) }.getOrNull() == targetId) }) {
                return children.filter { checkActive(); it.isFile }.mapNotNull { child -> child.name?.let { it to child.uri.toString() } }
            }
            if (depth < 16) children.filter { checkActive(); it.isDirectory }.forEach { directories.add(it to depth + 1) }
        }
        return listOf(name to source)
    }

    /** Scanner evidence avoids searching every granted tree again on a later boot. */
    internal fun rememberDocumentParent(context: Context, source: String, parent: String, displayName: String) {
        if (!ConsoleImageParts.isMultipart(displayName)) return
        val uri = source.toUri()
        val folder = parent.toUri()
        if (uri.authority != folder.authority || !DocumentsContract.isTreeUri(folder)) return
        val preferences = context.getSharedPreferences("console-image-parents", Context.MODE_PRIVATE)
        val key = parentKey(source)
        if (preferences.getString(key, null) == parent) return
        val edit = preferences.edit()
        if (preferences.all.size >= 512) preferences.all.keys.firstOrNull { it != key }?.let(edit::remove)
        edit.putString(key, parent).apply()
    }

    private fun parentKey(source: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun sameDocument(candidate: android.net.Uri, source: android.net.Uri, targetId: String?): Boolean =
        candidate.authority == source.authority && (candidate == source || (targetId != null &&
            runCatching { DocumentsContract.getDocumentId(candidate) }.getOrNull() == targetId))

    private fun documentChildren(context: Context, parent: android.net.Uri, checkActive: () -> Unit): List<Pair<String, String>> {
        val childUri = runCatching { DocumentsContract.buildChildDocumentsUriUsingTree(parent,
            DocumentsContract.getDocumentId(parent)) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        try {
            context.contentResolver.query(childUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    checkActive()
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    result += name to DocumentsContract.buildDocumentUriUsingTree(parent, id).toString()
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { return emptyList() }
        return result
    }
}
