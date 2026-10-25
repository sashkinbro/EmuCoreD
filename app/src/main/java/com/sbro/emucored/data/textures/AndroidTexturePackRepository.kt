package com.sbro.emucored.data.textures

import android.content.Context
import android.net.Uri
import com.sbro.emucored.core.BackupSessionGate
import com.sbro.emucored.core.ConsoleImageAccess
import com.sbro.emucored.core.ConsoleImageParts
import com.sbro.emucored.core.DocumentPathResolver
import com.sbro.emucored.core.EmuCoreNative
import com.sbro.emucored.core.dolphin.DolphinSessionEngine
import com.sbro.emucored.core.platform.CoreOptionPreferences
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.ContentLibraryRepository
import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.RemoteContentCatalogRepository
import com.sbro.emucored.data.RemoteTexturePack
import com.sbro.emucored.data.PerGameSettings
import com.sbro.emucored.data.PerGameSettingsRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal data class InstalledTexturePack(
    val target: TexturePackTarget, val stats: TexturePackStats, val enabled: Boolean,
    val files: List<String>
)

internal data class InstalledTexturePackInfo(
    val target: TexturePackTarget,
    val platform: GamePlatform?,
    val gameTitle: String?,
    val stats: TexturePackStats,
    val enabled: Boolean
)

internal data class TexturePackSummary(
    val packs: List<InstalledTexturePackInfo>,
    val totalTextureCount: Int,
    val totalSizeBytes: Long
)

internal class AndroidTexturePackRepository(context: Context) {
    private val context = context.applicationContext
    private val library = ContentLibraryRepository(this.context)
    private val preferences = AppPreferences(this.context)
    private val options = CoreOptionPreferences(this.context)
    private val limits = TexturePackLimits()
    private val store = TexturePackStore(this.context.filesDir, limits)

    suspend fun games(): List<GameItem> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        library.loadGames().filter {
            job.ensureActive()
            TexturePackIdentity.supports(it.platform)
        }
    }

    suspend fun listPacks(): TexturePackSummary = withContext(Dispatchers.IO) {
        val games = library.loadGames()
        val n3dsGames = games.filter { it.platform == GamePlatform.N3DS }.associateBy {
            normalizePackSerial(it.serial)
        }
        val packs = store.listInstalled().mapNotNull { target ->
            val stats = runCatching { store.inspect(target) }.getOrNull() ?: return@mapNotNull null
            if (stats.textureCount == 0) return@mapNotNull null
            val game = if (target.platform == com.sbro.emucored.core.platform.ConsolePlatform.N3DS) {
                n3dsGames[target.id]
            } else {
                null
            }
            InstalledTexturePackInfo(
                target = target,
                platform = if (target.platform == com.sbro.emucored.core.platform.ConsolePlatform.N3DS) {
                    GamePlatform.N3DS
                } else {
                    null
                },
                gameTitle = game?.title,
                stats = stats,
                enabled = if (target.platform == com.sbro.emucored.core.platform.ConsolePlatform.N3DS) {
                    preferences.textureReplacementsEnabled.first()
                } else {
                    false
                }
            )
        }.sortedWith(
            compareBy<InstalledTexturePackInfo>(
                { (it.gameTitle ?: it.target.id).lowercase(java.util.Locale.ROOT) },
                { it.target.id }
            )
        )
        TexturePackSummary(
            packs = packs,
            totalTextureCount = packs.sumOf { it.stats.textureCount },
            totalSizeBytes = packs.sumOf { it.stats.totalBytes }
        )
    }

    suspend fun importAnyZip(uri: Uri): TexturePackStats = withContext(Dispatchers.IO) {
        val archive = File.createTempFile("texture-import-", ".zip", context.cacheDir)
        try {
            val input = requireNotNull(context.contentResolver.openInputStream(uri))
            input.use { source -> archive.outputStream().use { destination ->
                val buffer = ByteArray(128 * 1024)
                var bytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    bytes += read
                    require(bytes <= limits.maxArchiveBytes)
                    destination.write(buffer, 0, read)
                }
            } }
            val target = requireNotNull(store.detectTarget(archive)) {
                "Texture archive does not contain a supported game folder"
            }
            BackupSessionGate.whileStopped { store.install(target, archive) }.also {
                if (target.platform == com.sbro.emucored.core.platform.ConsolePlatform.N3DS) {
                    preferences.setTextureReplacementsEnabled(true)
                }
            }
        } finally {
            archive.delete()
        }
    }

    suspend fun deletePack(target: TexturePackTarget) = withContext(Dispatchers.IO) {
        BackupSessionGate.whileStopped { store.delete(target) }
    }

    fun storageRoots(): List<String> = store.storageRoots().map { it.absolutePath }

    suspend fun setReplacementsEnabled(enabled: Boolean) {
        preferences.setTextureReplacementsEnabled(enabled)
    }

    private fun normalizePackSerial(serial: String?): String =
        serial.orEmpty().trim().uppercase(java.util.Locale.ROOT)
            .replace(Regex("[^A-Z0-9]"), "")

    suspend fun resolve(game: GameItem): TexturePackTarget? = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        job.ensureActive()
        if (!TexturePackIdentity.supports(game.platform)) return@withContext null
        if (game.platform != GamePlatform.N3DS) return@withContext runCatching {
            // Single SAF documents stay URIs so Dolphin can use the document
            // grant. Only split ISO/WBFS images need an addressable sibling stage.
            if (!ConsoleImageParts.isMultipart(game.fileName)) {
                val identity = DolphinSessionEngine.probeGameIdentity(game.path)
                job.ensureActive()
                identity?.let {
                    TexturePackIdentity.fromDolphin(game.platform, it.platform, it.gameId)
                }
            } else ConsoleImageAccess.prepareForProbe(context, game.path, checkActive = { job.ensureActive() })?.use { image ->
                val identity = DolphinSessionEngine.probeGameIdentity(image.path)
                job.ensureActive()
                identity?.let {
                    TexturePackIdentity.fromDolphin(game.platform, it.platform, it.gameId)
                }
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        fun probe(path: String): TexturePackTarget? =
            if (!EmuCoreNative.isLoaded) null else {
                // Azahar's Android file backend otherwise resolves absolute paths
                // relative to its user directory, including held /proc descriptors.
                val nativePath = "!${path.removePrefix("!")}"
                val titleId = runCatching { EmuCoreNative.getTitleId(nativePath) }.getOrDefault(0)
                val actual = if (titleId != 0L) titleId
                    else runCatching { EmuCoreNative.getCiaTitleId(nativePath) }.getOrDefault(0)
                TexturePackIdentity.fromNativeTitleId(actual)
            }
        val source = game.path.removePrefix("!")
        val direct = if (source.startsWith("file://")) Uri.parse(source).path
            else DocumentPathResolver.resolveFilePath(context, source)
        if (direct != null && File(direct).isFile && File(direct).canRead())
            return@withContext runCatching {
                job.ensureActive()
                probe(direct).also { job.ensureActive() }
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        val uri = if (source.startsWith("content://")) Uri.parse(source)
            else DocumentPathResolver.findAccessibleTreeUriForRawPath(context, source)
        runCatching {
            job.ensureActive()
            uri?.let { context.contentResolver.openFileDescriptor(it, "r")?.use { descriptor ->
                // Keep the original descriptor alive during native metadata parsing. Never hash/copy a disc.
                probe("/proc/self/fd/${descriptor.fd}").also { job.ensureActive() }
            } }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
    }

    suspend fun inspect(game: GameItem, expected: TexturePackTarget): InstalledTexturePack =
        withContext(Dispatchers.IO) { BackupSessionGate.whileStopped {
            verify(game, expected)
            val stats = store.inspect(expected)
            val root = store.directory(expected)
            val files = root.walkTopDown().filter { it.isFile }.map {
                it.relativeTo(root).invariantSeparatorsPath
            }.sorted().toList()
            val enabled = if (game.platform == GamePlatform.N3DS) preferences.textureReplacementsEnabled.first()
                else options.effectiveOptions(game.platform.console, game.path)["hires_textures"] == "true"
            InstalledTexturePack(expected, stats, enabled, files)
        } }

    suspend fun importZip(game: GameItem, expected: TexturePackTarget, uri: Uri) = withContext(Dispatchers.IO) {
        // Download/copy first, then acquire the same mutation boundary as core startup.
        val archive = File.createTempFile("texture-import-", ".zip", context.cacheDir)
        try {
            val input = requireNotNull(context.contentResolver.openInputStream(uri))
            input.use { source -> archive.outputStream().use { destination ->
                val buffer = ByteArray(128 * 1024)
                var bytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    bytes += read
                    require(bytes <= limits.maxArchiveBytes)
                    destination.write(buffer, 0, read)
                }
            } }
            installArchive(game, expected, archive)
        } finally { archive.delete() }
    }

    suspend fun installArchive(game: GameItem, expected: TexturePackTarget, archive: File): TexturePackStats =
        withContext(Dispatchers.IO) { BackupSessionGate.whileStopped {
            verify(game, expected)
            store.install(expected, archive)
        } }

    suspend fun export(game: GameItem, expected: TexturePackTarget, uri: Uri) = withContext(Dispatchers.IO) {
        BackupSessionGate.whileStopped {
            verify(game, expected)
            val archive = File.createTempFile("texture-export-", ".zip", context.cacheDir)
            try {
                archive.outputStream().use { store.export(expected, it) }
                require(archive.length() <= limits.maxArchiveBytes)
                // Validate and finish the archive before opening/truncating the destination document.
                requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use { output ->
                    archive.inputStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            } finally { archive.delete() }
        }
    }

    suspend fun delete(game: GameItem, expected: TexturePackTarget) = withContext(Dispatchers.IO) {
        BackupSessionGate.whileStopped { verify(game, expected); store.delete(expected) }
    }

    suspend fun setEnabled(game: GameItem, expected: TexturePackTarget, enabled: Boolean) = withContext(Dispatchers.IO) {
        BackupSessionGate.whileStopped {
            verify(game, expected)
            if (game.platform == GamePlatform.N3DS) preferences.setTextureReplacementsEnabled(enabled)
            else {
                check(options.setGameOverride(game.platform.console, game.path, "hires_textures", enabled.toString()))
                val profiles = PerGameSettingsRepository(context)
                val profile = profiles.get(game.path)
                profiles.save(profile?.copy(
                    coreOptions = profile.coreOptions + ("hires_textures" to enabled.toString()),
                    providedKeys = profile.providedKeys?.plus("coreOptions")
                ) ?: PerGameSettings(gameKey = game.path, gameTitle = game.title, gamePlatform = game.platform,
                    coreOptions = options.gameOverrides(game.platform.console, game.path), providedKeys = setOf("coreOptions")))
            }
        }
    }

    suspend fun onlinePacks(target: TexturePackTarget): List<RemoteTexturePack> = withContext(Dispatchers.IO) {
        if (target.platform != com.sbro.emucored.core.platform.ConsolePlatform.N3DS) return@withContext emptyList()
        val catalog = RemoteContentCatalogRepository(context).loadTextureCatalog()
        if (catalog.entries.isEmpty()) catalog.error?.let { throw it }
        catalog.entries.filter { target.id in it.serials }
    }

    suspend fun installOnline(game: GameItem, expected: TexturePackTarget, pack: RemoteTexturePack) {
        require(expected.platform == com.sbro.emucored.core.platform.ConsolePlatform.N3DS && expected.id in pack.serials)
        val archive = withContext(Dispatchers.IO) { RemoteContentCatalogRepository(context).downloadTexturePack(pack) }
        try { installArchive(game, expected, archive) } finally { withContext(Dispatchers.IO) { archive.delete() } }
    }

    private suspend fun verify(game: GameItem, expected: TexturePackTarget) {
        require(resolve(game) == expected) { "Texture identity changed or could not be verified" }
    }
}
