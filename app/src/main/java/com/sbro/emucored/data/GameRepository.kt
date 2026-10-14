package com.sbro.emucored.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.sbro.emucored.core.BiosValidator
import com.sbro.emucored.core.DocumentPathResolver
import com.sbro.emucored.core.EmulatorBridge
import com.sbro.emucored.core.SetupValidator
import com.sbro.emucored.core.N3dsGameMetadataReader
import java.io.File

data class GameItem(
    val title: String,
    val path: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Long,
    val coverArtPath: String? = null,
    val serial: String? = null
)

class GameRepository {

    companion object {
        private const val TAG = "GameRepository"
        // Keep the library honest with what the bundled Azahar core mounts.
        private val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val COVER_DIRECTORY_NAMES = setOf("covers", "cover", "art", "artwork", "boxart", "box art")
        private const val MAX_DOCUMENT_SCAN_DEPTH = 32
        private const val MAX_DOCUMENT_SCAN_ENTRIES = 20_000
        internal fun libraryIdentity(path: String): String {
            if (!path.startsWith("content://")) {
                return runCatching { File(path).canonicalPath }
                    .getOrDefault(File(path).absolutePath)
            }
            return runCatching {
                val uri = path.toUri()
                val documentId = DocumentsContract.getDocumentId(uri)
                "${uri.authority}:$documentId"
            }.getOrDefault(path)
        }

        internal fun parentDocumentId(documentId: String): String? {
            val separatorIndex = documentId.lastIndexOf('/')
            if (separatorIndex >= 0) return documentId.substring(0, separatorIndex)

            val volumeSeparatorIndex = documentId.indexOf(':')
            return if (volumeSeparatorIndex >= 0 && volumeSeparatorIndex < documentId.lastIndex) {
                documentId.substring(0, volumeSeparatorIndex + 1)
            } else {
                null
            }
        }

        internal fun relativeDocumentSegments(rootDocumentId: String, targetDocumentId: String): List<String>? {
            if (targetDocumentId == rootDocumentId) return emptyList()
            val prefix = if (rootDocumentId.endsWith(':')) rootDocumentId else "$rootDocumentId/"
            if (!targetDocumentId.startsWith(prefix)) return null
            return targetDocumentId.removePrefix(prefix).split('/').filter(String::isNotBlank)
        }
    }

    fun scanDirectory(path: String, context: Context): List<GameItem> {
        return scanDirectory(path, context, emptyMap())
    }

    fun scanDirectory(
        path: String,
        context: Context,
        cachedGamesByPath: Map<String, GameItem>,
        shouldAbort: () -> Boolean = { false },
        preferEnglishTitles: Boolean = false
    ): List<GameItem> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return scanLocalDirectory(
            dir,
            context,
            cachedGamesByPath,
            shouldAbort,
            preferEnglishTitles
        ).sortedBy { it.title.lowercase() }
    }

    fun scanDirectoryFromUri(uri: Uri, context: Context): List<GameItem> {
        return scanDirectoryFromUri(uri, context, emptyMap())
    }

    fun scanDirectoryFromUri(
        uri: Uri,
        context: Context,
        cachedGamesByPath: Map<String, GameItem>,
        shouldAbort: () -> Boolean = { false },
        preferEnglishTitles: Boolean = false
    ): List<GameItem> {
        if (!DocumentsContract.isTreeUri(uri)) {
            return emptyList()
        }
        return runCatching {
            val docFile = DocumentFile.fromTreeUri(context, uri) ?: return@runCatching emptyList()
            scanDocumentFile(
                docFile,
                context,
                cachedGamesByPath,
                shouldAbort,
                preferEnglishTitles = preferEnglishTitles,
                depth = 0,
                budget = DocumentScanBudget()
            )
                .sortedBy { it.title.lowercase() }
        }.onFailure { error ->
            Log.w(TAG, "Unable to scan document tree: $uri", error)
        }.getOrDefault(emptyList())
    }

    fun findCoverForGame(
        path: String,
        context: Context,
        @Suppress("UNUSED_PARAMETER") serial: String? = null,
        @Suppress("UNUSED_PARAMETER") title: String? = null
    ): String? {
        return CustomGameCoverRepository(context).findCustomCoverPath(path)
            ?: N3dsGameMetadataReader.extractIcon(context, path)
    }

    private fun scanLocalDirectory(
        dir: File,
        context: Context,
        cachedGamesByPath: Map<String, GameItem>,
        shouldAbort: () -> Boolean,
        preferEnglishTitles: Boolean = false
    ): List<GameItem> {
        val items = mutableListOf<GameItem>()
        val children = dir.listFiles().orEmpty()
        val customCoverRepository = CustomGameCoverRepository(context)

        children.forEach { file ->
            if (shouldAbort()) return items
            when {
                file.isDirectory && normalizeBaseName(file.name) !in COVER_DIRECTORY_NAMES -> {
                    items.addAll(
                        scanLocalDirectory(
                            file,
                            context,
                            cachedGamesByPath,
                            shouldAbort,
                            preferEnglishTitles
                        )
                    )
                }

                file.isFile && com.sbro.emucored.core.N3dsGameFormats.isSupportedName(file.name) -> {
                    if (file.extension.equals("zip", true) && N3dsGameMetadataReader.read(context, file.absolutePath) == null) return@forEach
                    val entrySize = file.length()
                    val cachedGame = cachedGamesByPath[file.absolutePath]
                    val canReuseCachedMetadata = cachedGame != null &&
                        cachedGame.fileSize == entrySize &&
                        cachedGame.lastModified == file.lastModified() &&
                        cachedGame.fileName == file.name
                    val metadata = if (canReuseCachedMetadata) {
                        com.sbro.emucored.core.GameMetadata(cachedGame.title, cachedGame.serial)
                    } else {
                        val sourcePath = file.absolutePath
                        val sourceMetadata = EmulatorBridge.getGameMetadata(
                            sourcePath,
                            readDiscMetadata = false
                        )
                        val pspMetadata = N3dsGameMetadataReader.read(context, sourcePath)
                        sourceMetadata.copy(
                            title = pspMetadata?.title ?: sourceMetadata.title,
                            serial = pspMetadata?.serial ?: sourceMetadata.serial
                        )
                    }
                    if (BiosValidator.isLikelyBiosLibraryEntry(file.name, metadata.title, metadata.serial, file.length())) {
                        return@forEach
                    }

                    val cleanTitle = EmulatorBridge.cleanGameDisplayTitle(metadata.title, file.name)
                    val serial = metadata.serial
                    val title = cleanTitle
                    items += GameItem(
                        title = title,
                        path = file.absolutePath,
                        fileName = file.name,
                        fileSize = entrySize,
                        lastModified = file.lastModified(),
                        coverArtPath = customCoverRepository.findCustomCoverPath(file.absolutePath)
                            ?: N3dsGameMetadataReader.extractIcon(context, file.absolutePath),
                        serial = serial
                    )
                }
            }
        }

        return items
    }

    private fun scanDocumentFile(
        docFile: DocumentFile,
        context: Context,
        cachedGamesByPath: Map<String, GameItem>,
        shouldAbort: () -> Boolean,
        preferEnglishTitles: Boolean = false,
        depth: Int,
        budget: DocumentScanBudget
    ): List<GameItem> {
        if (depth > MAX_DOCUMENT_SCAN_DEPTH || !budget.tryEnterDirectory(docFile.uri.toString())) {
            return emptyList()
        }
        val items = mutableListOf<GameItem>()
        val children = runCatching { docFile.listFiles() }.getOrDefault(emptyArray())
        val customCoverRepository = CustomGameCoverRepository(context)

        for (file in children) {
            if (shouldAbort() || !budget.tryVisitEntry()) return items
            val mimeType = runCatching { file.type }.getOrNull()
            val name = documentDisplayName(context, file)
            when (SetupValidator.classifyDocumentEntry(mimeType, name)) {
                SetupValidator.DocumentEntryKind.DIRECTORY,
                SetupValidator.DocumentEntryKind.UNKNOWN -> {
                    if (normalizeBaseName(name) !in COVER_DIRECTORY_NAMES) {
                        items.addAll(
                            scanDocumentFile(
                                file,
                                context,
                                cachedGamesByPath,
                                shouldAbort,
                                preferEnglishTitles,
                                depth + 1,
                                budget
                            )
                        )
                    }
                }
                SetupValidator.DocumentEntryKind.GAME_FILE -> {
                    val uriPath = file.uri.toString()
                    val fileSize = runCatching { file.length() }.getOrDefault(0L)
                    if (name.endsWith(".zip", true) && N3dsGameMetadataReader.read(context, uriPath) == null) continue
                    val entrySize = fileSize
                    val lastModified = runCatching { file.lastModified() }.getOrDefault(0L)

                    val cachedGame = cachedGamesByPath[uriPath]
                    val canReuseCachedMetadata = cachedGame != null &&
                        cachedGame.fileSize == entrySize &&
                        cachedGame.lastModified == lastModified &&
                        cachedGame.fileName == name
                    val metadata = if (canReuseCachedMetadata) {
                        com.sbro.emucored.core.GameMetadata(cachedGame.title, cachedGame.serial)
                    } else {
                        val sourcePath = uriPath
                        val sourceMetadata = EmulatorBridge.getGameMetadata(
                            sourcePath,
                            readDiscMetadata = false
                        )
                        val pspMetadata = N3dsGameMetadataReader.read(context, sourcePath)
                        sourceMetadata.copy(
                            title = pspMetadata?.title ?: sourceMetadata.title,
                            serial = pspMetadata?.serial ?: sourceMetadata.serial
                        )
                    }

                    if (BiosValidator.isLikelyBiosLibraryEntry(name, metadata.title, metadata.serial, fileSize)) {
                        continue
                    }

                    val cleanTitle = cleanScannedTitle(metadata.title, name)
                    val serial = metadata.serial
                    val title = cleanTitle
                    items += GameItem(
                        title = title,
                        path = uriPath,
                        fileName = name,
                        fileSize = entrySize,
                        lastModified = lastModified,
                        coverArtPath = customCoverRepository.findCustomCoverPath(uriPath)
                            ?: N3dsGameMetadataReader.extractIcon(context, uriPath),
                        serial = serial
                    )
                }
                SetupValidator.DocumentEntryKind.OTHER -> Unit
            }
        }

        return items
    }

    private fun normalizeBaseName(value: String): String {
        return value.lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("""\[[^]]*]|\([^)]*\)"""), " ")
            .replace(Regex("""\b(disc|disk|cd|dvd)\s*[0-9]+\b"""), " ")
            .replace(Regex("""\b\(?(usa|eur|europe|japan|jpn|beta|demo|proto|prototype|rev\s*[a-z0-9]+)\)?\b"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanScannedTitle(rawTitle: String, displayName: String): String {
        return EmulatorBridge.cleanGameDisplayTitle(rawTitle, displayName)
    }

    private fun documentDisplayName(context: Context?, document: DocumentFile): String {
        val rawName = runCatching { document.name }.getOrNull().orEmpty()
        if (rawName.isNotBlank()) return DocumentPathResolver.normalizeDisplayName(rawName)
        if (context == null) return ""
        return runCatching { DocumentPathResolver.getDisplayName(context, document.uri.toString()) }
            .getOrDefault("")
    }

    private class DocumentScanBudget {
        private var visitedEntries = 0
        private val visitedDirectories = HashSet<String>()

        fun tryEnterDirectory(uri: String): Boolean = visitedDirectories.add(uri)

        fun tryVisitEntry(): Boolean {
            if (visitedEntries >= MAX_DOCUMENT_SCAN_ENTRIES) return false
            visitedEntries++
            return true
        }
    }
}
