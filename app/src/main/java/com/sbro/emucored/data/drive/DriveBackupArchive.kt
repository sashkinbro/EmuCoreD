package com.sbro.emucored.data.drive

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.sbro.emucored.BuildConfig
import com.sbro.emucored.core.BackupSessionGate
import com.sbro.emucored.core.EmulatorStorage
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.CheatRepository
import com.sbro.emucored.data.PerGameSettingsRepository
import com.sbro.emucored.data.ProfileDeviceInfoProvider
import com.sbro.emucored.data.EmulationSideArtwork
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import java.util.zip.ZipException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class DriveSnapshot(val file: File, val digest: String, val manifest: JSONObject)

/** An explicit data allowlist; never archives filesDir, databases, credentials, games or BIOS. */
class DriveBackupArchive(
    private val context: Context,
    private val deviceId: () -> String = { DriveBackupState.get(context).value.deviceId }
) {
    private val preferences = AppPreferences(context)
    private val perGame = PerGameSettingsRepository(context)
    private val cheats = CheatRepository(context)
    private val coreOptions = com.sbro.emucored.core.platform.CoreOptionPreferences(context)
    val workDir = File(context.noBackupFilesDir, "drive-backup").apply { mkdirs() }
    private val transaction = File(context.noBackupFilesDir, "drive-restore")
    private val journal get() = AtomicFile(File(transaction, "journal.json"))

    private fun roots(): Map<String, File> {
        val path = preferences.getEmulatorDataPathSync()
        if (!path.isNullOrBlank() && (!File(path).isDirectory || !File(path).canRead() || !File(path).canWrite())) {
            throw DriveBackupException("storage")
        }
        return linkedMapOf(
            "console-states" to com.sbro.emucored.core.platform.session.ConsoleSessionStorage.stateRoot(context.filesDir),
            "console-data" to File(context.filesDir, "consoles"),
            "console-dolphin" to File(context.filesDir, "consoles/dolphin"),
            "ds-states" to File(context.filesDir, "ds/states"),
            "ds-saves" to File(context.filesDir, "ds/saves"),
            "memory-stick" to EmulatorStorage.sdmcDir(context, path),
            "memory-cards" to EmulatorStorage.memoryCardsDir(context, path),
            "save-states" to EmulatorStorage.saveStatesDir(context, path),
            "cheat-files" to EmulatorStorage.importedCheatsDir(context),
            "patches" to EmulatorStorage.patchesDir(context, path),
            "customization" to File(context.filesDir, "customization"),
            "side-artwork" to File(context.filesDir, "emulation_side_artwork"),
            "covers" to File(context.filesDir, "library/custom-game-covers"),
            "textures" to EmulatorStorage.texturesDir(context, path)
        )
    }

    private fun allowed(name: String): Boolean {
        if (name in JSON_FILES || name == "core-options.json") return true
        if (ConsoleBackupPaths.allowed(name)) return true
        val parts = name.split('/')
        if (parts.size < 2 || parts.any { it.isBlank() || it == "." || it == ".." || '\\' in it || ':' in it }) return false
        return when (parts[0]) {
            "memory-stick" -> parts.drop(1).none { it.startsWith(".") } && !name.endsWith(".tmp")
            "memory-cards" -> parts.size == 2 && !parts[1].startsWith(".") && !name.endsWith(".tmp")
            "save-states" -> parts.size == 2 && (name.endsWith(".cst", true) || name.endsWith(".cst.png", true) || name.endsWith(".cst.json", true))
            "cheat-files" -> parts.size == 2 && name.endsWith(".txt", true)
            "patches" -> parts.size == 2 && (
                name.endsWith(".pnach", true) || name.endsWith(".cht", true) ||
                    name.endsWith(".ips", true) || name.endsWith(".bps", true)
                )
            "customization" -> parts.size == 2 && parts[1] in setOf("app_font", "home_background.jpg", "home_background.png", "home_background.gif", "home_background.mp4", "home_background.image", "home_background.video")
            "side-artwork" -> parts.size == 2 && parts[1] == "custom_side_artwork.image"
            "covers" -> parts.size == 2 && parts[1].matches(Regex("[0-9a-f]{40}\\.[a-z0-9]{2,5}"))
            "textures" -> parts.size >= 3 && parts.none { it.equals("dumps", true) || it.startsWith(".") } &&
                name.substringAfterLast('.').lowercase() in setOf("png", "dds", "ktx2")
            else -> false
        }
    }

    private fun portableSettings(json: JSONObject): JSONObject = JSONObject(json.toString()).apply {
        LOCAL_KEYS.forEach(::remove)
        listOf("memoryCardSlot1", "memoryCardSlot2").forEach { key ->
            optString(key).takeIf { it.isNotBlank() }?.let { put(key, File(it).name) }
        }
    }

    private fun portablePerGame(json: JSONObject): JSONObject = JSONObject(json.toString()).apply {
        optJSONArray("profiles")?.let { array ->
            for (i in 0 until array.length()) {
                array.getJSONObject(i).apply { remove("customDriverPath"); put("gpuDriverType", 0) }
            }
        }
    }

    private suspend fun exportSettings(): JSONObject = preferences.exportJson()
        .put("emulationSideArtwork", preferences.emulationSideArtwork.first().preferenceValue)

    private suspend fun importSettings(json: JSONObject) {
        preferences.importJson(json)
        if (json.has("emulationSideArtwork")) preferences.setEmulationSideArtwork(
            EmulationSideArtwork.fromPreference(json.getInt("emulationSideArtwork")))
    }

    suspend fun create(includeTextures: Boolean = true): DriveSnapshot = BackupSessionGate.whileStopped {
        recoverPending()
        val file = File(workDir, "snapshot-new.zip")
        val entries = JSONArray()
        val contentHash = MessageDigest.getInstance("SHA-256")
        val sources = sortedMapOf<String, File>()
        roots().forEach { (prefix, root) ->
            if (prefix == "textures" && !includeTextures) return@forEach
            if (root.isDirectory) root.walkTopDown().onEnter { directory ->
                !Files.isSymbolicLink(directory.toPath()) && (prefix != "console-data" || directory == root ||
                    directory.relativeTo(root).invariantSeparatorsPath.substringBefore('/') in setOf("gba", "gb", "gbc", "nes", "snes"))
            }.forEach { source ->
                if (source.isFile && !Files.isSymbolicLink(source.toPath())) {
                    val name = "$prefix/${source.relativeTo(root).invariantSeparatorsPath}"
                    val stateSidecar = prefix in setOf("console-states", "ds-states", "save-states") &&
                        source.extension in setOf("png", "json")
                    val stateExists = !stateSidecar || File(source.path.substringBeforeLast('.')).isFile
                    if (stateExists && allowed(name) && source.canonicalPath.startsWith(root.canonicalPath + File.separator)) sources[name] = source
                }
            }
        }
        val json = sortedMapOf(
            "settings.json" to portableSettings(exportSettings()),
            "per-game.json" to portablePerGame(perGame.exportJson()),
            "cheats.json" to cheats.exportJson(),
            "core-options.json" to coreOptions.exportJson()
        )
        val manifest = JSONObject().put("format", FORMAT).put("schema", 1)
            .put("appVersion", BuildConfig.VERSION_NAME).put("coreVersion", ProfileDeviceInfoProvider.CORE_VERSION)
            .put("createdAt", System.currentTimeMillis()).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("deviceId", deviceId())
        try {
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                suspend fun entry(name: String, input: InputStream) {
                    val hash = MessageDigest.getInstance("SHA-256")
                    zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                    val size = input.use { copy(it, zip) { hash.update(it) } }
                    zip.closeEntry()
                    val hex = hash.digest().hex()
                    entries.put(JSONObject().put("name", name).put("size", size).put("sha256", hex))
                    contentHash.update("$name\u0000$size\u0000$hex\n".toByteArray())
                }
                for ((name, value) in json) entry(name, value.toString().byteInputStream())
                for ((name, source) in sources) entry(name, source.inputStream())
                manifest.put("files", entries)
                zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0 })
                zip.write(manifest.toString().toByteArray())
                zip.closeEntry()
            }
            DriveSnapshot(file, contentHash.digest().hex(), manifest)
        } catch (error: Throwable) {
            file.delete()
            if (error is IOException) throw DriveBackupException(if (workDir.usableSpace < SPACE_RESERVE) "space" else "storage", error)
            throw error
        }
    }

    /** Validates every entry before any local state is changed. Extraction stays on private storage. */
    suspend fun extract(archive: File): JSONObject {
        val stage = File(workDir, "extracted")
        clearChild(workDir, stage); stage.mkdirs()
        try {
            ZipFile(archive).use { zip ->
                val metadata = zip.getEntry("manifest.json") ?: invalid()
                if (metadata.size !in 1..MAX_JSON_BYTES) invalid()
                val manifest = JSONObject(zip.getInputStream(metadata).use { it.readBytes().toString(Charsets.UTF_8) })
                if (manifest.optString("format") != FORMAT || manifest.optInt("schema") != 1) invalid()
                val files = manifest.getJSONArray("files")
                if (files.length() !in 3..100_000 || zip.size() != files.length() + 1) invalid()
                val seen = mutableSetOf<String>()
                var total = 0L
                for (i in 0 until files.length()) {
                    val spec = files.getJSONObject(i)
                    val name = spec.getString("name")
                    if (!allowed(name) || !seen.add(name)) invalid()
                    val size = spec.getLong("size")
                    if (size < 0 || size > Long.MAX_VALUE - total) invalid()
                    if ((name in JSON_FILES || name == "core-options.json") && size > MAX_JSON_BYTES) invalid()
                    total += size
                }
                if (!seen.containsAll(JSON_FILES)) invalid()
                if (total > stage.usableSpace - SPACE_RESERVE) throw DriveBackupException("space")
                for (i in 0 until files.length()) {
                    currentCoroutineContext().ensureActive()
                    val spec = files.getJSONObject(i)
                    val name = spec.getString("name")
                    val entry = zip.getEntry(name) ?: invalid()
                    val size = spec.getLong("size")
                    if (entry.isDirectory || entry.size != size) invalid()
                    val target = File(stage, name).canonicalFile
                    if (!target.path.startsWith(stage.canonicalPath + File.separator)) invalid()
                    target.parentFile!!.mkdirs()
                    val hash = MessageDigest.getInstance("SHA-256")
                    val actual = target.outputStream().use { output ->
                        zip.getInputStream(entry).use { input -> copy(input, output, size) { hash.update(it) } }
                    }
                    if (actual != size || hash.digest().hex() != spec.getString("sha256")) invalid()
                }
                JSON_FILES.forEach { JSONObject(File(stage, it).readText()) }
                File(stage, "core-options.json").takeIf(File::isFile)?.let { file ->
                    val options = JSONObject(file.readText())
                    if (options.optInt("version") != 1 || options.optJSONObject("values") == null) invalid()
                }
                validateConsoleStates(stage, files)
                return manifest
            }
        } catch (error: Throwable) {
            clearChild(workDir, stage)
            throw when (error) {
                is CancellationException, is DriveBackupException -> error
                is ZipException, is JSONException -> DriveBackupException("invalid", error)
                is IOException -> DriveBackupException("storage", error)
                else -> error
            }
        }
    }

    private fun validateConsoleStates(stage: File, files: JSONArray) {
        val specifications = (0 until files.length()).associate { i ->
            val spec = files.getJSONObject(i)
            spec.getString("name") to spec
        }
        specifications.forEach { (name, spec) ->
            val file = File(stage, name)
            when {
                name.startsWith("console-states/") && name.endsWith(".ecs") -> {
                    val parts = name.split('/')
                    val console = com.sbro.emucored.core.platform.ConsolePlatform.fromId(parts[1]) ?: invalid()
                    val platform = com.sbro.emucored.data.GamePlatform.valueOf(console.name)
                    if (!com.sbro.emucored.data.SaveStateFileIntegrity.validate(file, platform)) invalid()
                    if ("$name.json" !in specifications) invalid()
                    val metadataFile = File(stage, "$name.json")
                    if (metadataFile.length() !in 1..65_536) invalid()
                    val metadata = JSONObject(metadataFile.readText())
                    val identity = metadata.optString("gamePath")
                    val slot = metadata.optInt("slot", -1)
                    if (identity.isBlank() || identity.length > 8192 || slot !in 0..10 ||
                        metadata.optString("platform") != platform.name ||
                        com.sbro.emucored.core.platform.session.ConsoleSessionStorage.identityKey(identity) != parts[2] ||
                        parts[3] != "${console.id}_${parts[2]}_slot$slot.ecs") invalid()
                    val digest = metadata.optString("sha256")
                    if ((console.core == com.sbro.emucored.core.platform.CoreId.DOLPHIN || digest.isNotBlank()) && digest != spec.getString("sha256")) invalid()
                }
                name.startsWith("ds-states/") && name.endsWith(".dss") ->
                    if (!com.sbro.emucored.data.SaveStateFileIntegrity.validate(file, com.sbro.emucored.data.GamePlatform.NDS)) invalid()
            }
            if (name.substringBefore('/') in setOf("console-states", "ds-states", "save-states") && name.endsWith(".png")) {
                if (name.removeSuffix(".png") !in specifications || !com.sbro.emucored.data.SaveStateArchive.validPng(file)) invalid()
            }
        }
    }

    suspend fun restore(manifest: JSONObject, categories: Set<String>) = BackupSessionGate.whileStopped {
        recoverPending()
        val stage = File(workDir, "extracted")
        val currentRoots = roots()
        clearChild(context.noBackupFilesDir, transaction); transaction.mkdirs()
        val old = File(transaction, "old").apply { mkdirs() }
        val files = manifest.getJSONArray("files")
        val changes = JSONArray()
        var oldSize = 0L
        for (i in 0 until files.length()) {
            val name = files.getJSONObject(i).getString("name")
            val prefix = name.substringBefore('/')
            val category = if (prefix in setOf("side-artwork", "covers")) "customization" else ConsoleBackupPaths.category(prefix)
            if (category !in categories || prefix !in currentRoots) continue
            val root = currentRoots.getValue(prefix).canonicalFile
            val target = File(root, name.substringAfter('/')).canonicalFile
            if (!target.path.startsWith(root.path + File.separator)) invalid()
            if (target.isDirectory) invalid()
            oldSize += if (target.isFile) target.length() else 0
            changes.put(JSONObject().put("name", name).put("target", target.path).put("existed", target.isFile))
        }
        if (oldSize > old.usableSpace - SPACE_RESERVE) throw DriveBackupException("space")
        // Finish the complete rollback image before publishing a journal or changing any target.
        val oldSettings = exportSettings()
        writeAtomic(File(transaction, "settings.json"), oldSettings)
        writeAtomic(File(transaction, "per-game.json"), perGame.exportJson())
        writeAtomic(File(transaction, "cheats.json"), cheats.exportJson())
        writeAtomic(File(transaction, "core-options.json"), coreOptions.exportJson())
        for (i in 0 until changes.length()) {
            val change = changes.getJSONObject(i)
            if (change.getBoolean("existed")) {
                val saved = File(old, change.getString("name"))
                saved.parentFile!!.mkdirs()
                File(change.getString("target")).inputStream().use { input ->
                    saved.outputStream().use { output -> copy(input, output); output.fd.sync() }
                }
            }
        }
        val record = JSONObject().put("changes", changes).put("committed", false)
        writeAtomic(journal.baseFile, record)
        try {
            for (i in 0 until changes.length()) {
                currentCoroutineContext().ensureActive()
                val change = changes.getJSONObject(i)
                replaceFile(File(stage, change.getString("name")), File(change.getString("target")))
            }
            if ("settings" in categories || "customization" in categories) {
                val saved = portableSettings(JSONObject(File(stage, "settings.json").readText()))
                val incoming = if ("settings" in categories) saved else JSONObject(oldSettings.toString())
                STYLE_KEYS.forEach { key ->
                    val source = if ("customization" in categories) saved else oldSettings
                    if (source.has(key)) incoming.put(key, source.get(key)) else incoming.remove(key)
                }
                LOCAL_KEYS.forEach { key ->
                    if (oldSettings.has(key)) incoming.put(key, oldSettings.get(key)) else incoming.remove(key)
                }
                if ("memory-cards" !in categories) {
                    listOf("memoryCardSlot1", "memoryCardSlot2").forEach { key ->
                        incoming.put(key, oldSettings.opt(key))
                    }
                }
                importSettings(incoming)
                if ("settings" in categories) {
                    perGame.importJson(portablePerGame(JSONObject(File(stage, "per-game.json").readText())))
                    File(stage, "core-options.json").takeIf(File::isFile)?.let { coreOptions.importJson(JSONObject(it.readText())) }
                }
            }
            if ("cheat-files" in categories) cheats.importJson(JSONObject(File(stage, "cheats.json").readText()))
            writeAtomic(journal.baseFile, record.put("committed", true))
        } catch (error: Throwable) {
            withContext(NonCancellable) { recoverPending() }
            throw error
        }
        // Keep one private rollback image; a later restore replaces it only after recovery.
        journal.delete()
    }

    /** Also called before VM startup so a process death cannot expose a half-restored card. */
    suspend fun recoverPending() {
        if (!journal.baseFile.exists() && !File(transaction, "journal.json.bak").exists()) return
        val record = JSONObject(journal.openRead().bufferedReader().use { it.readText() })
        if (!record.optBoolean("committed")) {
            val changes = record.getJSONArray("changes")
            for (i in 0 until changes.length()) {
                val change = changes.getJSONObject(i)
                val target = File(change.getString("target"))
                if (change.getBoolean("existed")) replaceFile(File(transaction, "old/${change.getString("name")}"), target)
                else if (target.exists() && !target.delete()) throw DriveBackupException("restore")
            }
            importSettings(JSONObject(File(transaction, "settings.json").readText()))
            perGame.importJson(JSONObject(File(transaction, "per-game.json").readText()))
            cheats.importJson(JSONObject(File(transaction, "cheats.json").readText()))
            File(transaction, "core-options.json").takeIf(File::isFile)?.let { coreOptions.importJson(JSONObject(it.readText())) }
        }
        journal.delete()
    }

    private fun replaceFile(source: File, target: File) {
        target.parentFile!!.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.drive-restore")
        try {
            source.inputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private suspend fun copy(input: InputStream, output: OutputStream, limit: Long = Long.MAX_VALUE, consume: (ByteArray) -> Unit = {}): Long {
        val buffer = ByteArray(128 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            BackupSessionGate.checkpoint()
            val count = input.read(buffer)
            if (count < 0) break
            if (count.toLong() > limit - total) invalid()
            total += count
            output.write(buffer, 0, count)
            consume(buffer.copyOf(count))
        }
        return total
    }

    companion object {
        const val FORMAT = "emucored-drive-backup"
        val ALL_CATEGORIES = setOf("settings", "memory-stick", "memory-cards", "save-states", "cheat-files", "patches", "customization", "textures")
        private val JSON_FILES = setOf("settings.json", "per-game.json", "cheats.json")
        private val LOCAL_KEYS = setOf("hiddenGamePaths", "gamePath", "gamePaths", "emulatorDataPath", "customDriverPath", "gpuDriverType", "gpuHardwareProfile", "onboardingCompleted", "coverDownloadBaseUrl", "arcadeCoverDownloadBaseUrl")
        private val STYLE_KEYS = setOf("themeMode", "customTheme", "customThemeLibrary", "appFontChoice", "appFontScale", "customFontName", "homeGridScale", "homeBackgroundDim", "homeBackgroundType", "homeBackgroundPreset", "emulationSideArtworkDim", "emulationSideArtwork", "touchControlVisualStyle", "touchControlPressEffect", "gameMenuLayoutStyle", "drawerVisualStyle", "coverArtStyle")
        private const val MAX_JSON_BYTES = 32L * 1024 * 1024
        private const val SPACE_RESERVE = 32L * 1024 * 1024
        fun hasPendingRecovery(context: Context): Boolean =
            File(context.noBackupFilesDir, "drive-restore/journal.json").exists() ||
                File(context.noBackupFilesDir, "drive-restore/journal.json.bak").exists()
        private fun invalid(): Nothing = throw DriveBackupException("invalid")
        fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
        fun writeAtomic(file: File, json: JSONObject) {
            file.parentFile!!.mkdirs()
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try { output.write(json.toString().toByteArray()); atomic.finishWrite(output) }
            catch (error: Throwable) { atomic.failWrite(output); throw error }
        }
        fun clearChild(parent: File, child: File) {
            require(child.canonicalPath.startsWith(parent.canonicalPath + File.separator))
            if (child.exists() && !child.deleteRecursively()) throw DriveBackupException("storage")
        }
    }
}
