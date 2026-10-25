package com.sbro.emucored.data

import android.content.Context
import android.util.AtomicFile
import com.sbro.emucored.core.EmulatorStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import com.sbro.emucored.core.platform.session.CoreCheatCode
import com.sbro.emucored.core.platform.session.CoreCheatFormats
import com.sbro.emucored.data.cheats.TypedCheatCatalog
import com.sbro.emucored.data.cheats.TypedCheatPack
import com.sbro.emucored.data.cheats.CheatGameIdentity

data class CheatBlock(
    val id: String,
    val title: String,
    val lines: List<String>,
    val enabled: Boolean,
    val author: String? = null,
    val format: String = "3ds-gateway",
    val sourceUrl:String?=null,
    val sourcePackId:String?=null,
    val license:String?=null,
    val manualAssociation:Boolean=false
)

data class CheatGameConfig(
    val gameKey: String,
    val serial: String,
    val crc: String?,
    val sourceFileName: String?,
    val blocks: List<CheatBlock>
)

data class CheatFileEntry(
    val gameKey: String,
    val fileName: String,
    val displayName: String,
    val blockCount: Int
)

class CheatRepository(private val context: Context) {
    private val preferences = AppPreferences(context)
    private val importedDir = EmulatorStorage.importedCheatsDir(context)
    private val stateFile = File(EmulatorStorage.appStateDir(context), "cheat-state.json")
    private val activeTargetsFile = File(EmulatorStorage.appStateDir(context), "cheat-active-targets.json")

    fun typedGameConfig(platform:GamePlatform,path:String):CheatGameConfig? =
        getGameConfig(TypedCheatCatalog.gameKey(platform,path),"",null)

    fun enabledTypedCodes(platform:GamePlatform,path:String):List<CoreCheatCode> =
        typedGameConfig(platform,path)?.blocks.orEmpty().filter { it.enabled }.map { CoreCheatCode(it.format,it.lines.joinToString("\n")) }
            .filter { CoreCheatFormats.accepts(platform.console.core,it) }

    fun importTypedPack(gamePath:String,identity:CheatGameIdentity,pack:TypedCheatPack,raw:String,manual:Boolean=false):Int {
        require(pack.platform==identity.platform)
        require(manual || pack.matches(identity)){"Cheat pack does not match selected game"}
        require(TypedCheatCatalog.sha256(raw.toByteArray(Charsets.UTF_8))==pack.sha256)
        val blocks=TypedCheatCatalog.parsePack(raw,pack).map{it.copy(manualAssociation=manual)}
        return importCheatFile(TypedCheatCatalog.gameKey(identity.platform,gamePath),TypedCheatCatalog.serialize(blocks),
            enableAllByDefault=false,mergeWithExisting=true)
    }

    fun getGameConfig(gameKey: String, serial: String, crc: String?): CheatGameConfig? {
        val sourceFile = resolveImportedFile(gameKey)
        if (!sourceFile.exists()) return null
        val normalizedGameKey = sourceFile.nameWithoutExtension
        val raw = runCatching { sourceFile.readText() }.getOrNull() ?: return null
        val enabledIds = storedValues(loadEnabledIds(), normalizedGameKey, gameKey)
        val blocks = parseCheatBlocks(raw).map { it.copy(enabled = enabledIds.contains(it.id)) }
        return CheatGameConfig(
            gameKey = normalizedGameKey,
            serial = serial,
            crc = crc,
            sourceFileName = sourceFile.name,
            blocks = blocks
        )
    }

    fun getGameConfig(gameKeys: List<String>, serial: String, crc: String?): CheatGameConfig? {
        return gameKeys.firstNotNullOfOrNull { gameKey ->
            getGameConfig(gameKey, serial, crc)
        }
    }

    fun listImportedCheatFiles(): List<CheatFileEntry> {
        return importedDir.listFiles { file -> file.isFile && file.extension.equals("txt", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { file ->
                val raw = runCatching { file.readText() }.getOrDefault("")
                CheatFileEntry(
                    gameKey = file.nameWithoutExtension,
                    fileName = file.name,
                    displayName = file.nameWithoutExtension.replace('_', ' '),
                    blockCount = parseCheatBlocks(raw).size
                )
            }
            .orEmpty()
    }

    fun getImportedCheatText(gameKey: String): String? {
        val file = resolveImportedFile(gameKey)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun updateImportedCheatText(gameKey: String, contents: String): Int {
        return importCheatFile(gameKey = gameKey, contents = contents)
    }

    fun importCheatFile(
        gameKey: String,
        contents: String,
        enableAllByDefault: Boolean = false,
        mergeWithExisting: Boolean = false
    ): Int = synchronized(CHEAT_IO_LOCK) {
        val normalizedGameKey = normalizeGameKey(gameKey)
        val target = importedFile(normalizedGameKey)
        val state = loadEnabledIds()
        val old = storedValues(state, normalizedGameKey, gameKey)
        val existingBlocks = if (target.exists()) {
            runCatching { parseCheatBlocks(target.readText()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val oldEnabledSignatures = existingBlocks
                .filter { old.contains(it.id) }
                .associate { it.id to cheatBlockSignature(it) }
        val importedBlocks = parseCheatBlocks(contents)
        if (importedBlocks.isEmpty()) return@synchronized 0
        val blocks = if (mergeWithExisting) {
            mergeCheatBlocks(existingBlocks, importedBlocks)
        } else {
            importedBlocks
        }
        val storedContents = if (mergeWithExisting) serializeCheatBlocks(blocks) else contents
        target.parentFile?.mkdirs()
        writeTextAtomically(target, storedContents)
        val enabledIds = if (enableAllByDefault) {
            blocks.map { it.id }
        } else {
            blocks.filter { oldEnabledSignatures[it.id] == cheatBlockSignature(it) }.map { it.id }
        }
        state.put(normalizedGameKey, JSONArray(enabledIds))
        if (normalizedGameKey != gameKey) state.remove(gameKey)
        writeEnabledIds(state)
        blocks.size
    }

    private fun mergeCheatBlocks(
        existing: List<CheatBlock>,
        imported: List<CheatBlock>
    ): List<CheatBlock> {
        val merged = linkedMapOf<String, CheatBlock>()
        (existing + imported).forEach { block ->
            // Enabled state is keyed by ID too: a format change must replace
            // the previous block rather than create two blocks sharing a toggle.
            val key = block.id
            val previous = merged[key]
            merged[key] = if (previous == null) {
                block
            } else {
                block.copy(enabled=false,author=block.author ?: previous.author)
            }
        }
        return merged.values.toList()
    }

    private fun serializeCheatBlocks(blocks: List<CheatBlock>): String = serializeImportedCheatBlocks(blocks)

    fun setEnabledBlocks(gameKey: String, enabledIds: Set<String>) = synchronized(CHEAT_IO_LOCK) {
        val normalizedGameKey = normalizeGameKey(gameKey)
        val state = loadEnabledIds()
        state.put(normalizedGameKey, JSONArray(enabledIds.toList()))
        if (normalizedGameKey != gameKey) state.remove(gameKey)
        writeEnabledIds(state)
    }

    /**
     * Adds or removes a single block from the stored enabled set. Reads the
     * latest state from disk first so toggles made in another screen (the cheat
     * manager vs. the in-game menu) are never overwritten with a stale copy.
     */
    fun setBlockEnabled(gameKey: String, blockId: String, enabled: Boolean) = synchronized(CHEAT_IO_LOCK) {
        val normalizedGameKey = normalizeGameKey(gameKey)
        val state = loadEnabledIds()
        val enabledIds = storedValues(state, normalizedGameKey, gameKey).toMutableSet()
        if (enabled) enabledIds.add(blockId) else enabledIds.remove(blockId)
        state.put(normalizedGameKey, JSONArray(enabledIds.toList()))
        if (normalizedGameKey != gameKey) state.remove(gameKey)
        writeEnabledIds(state)
    }

    /** Enables or disables a whole group of blocks with the same read-modify-write. */
    fun setBlocksEnabled(gameKey: String, blockIds: Collection<String>, enabled: Boolean) = synchronized(CHEAT_IO_LOCK) {
        val normalizedGameKey = normalizeGameKey(gameKey)
        val state = loadEnabledIds()
        val enabledIds = storedValues(state, normalizedGameKey, gameKey).toMutableSet()
        if (enabled) enabledIds.addAll(blockIds) else enabledIds.removeAll(blockIds.toSet())
        state.put(normalizedGameKey, JSONArray(enabledIds.toList()))
        if (normalizedGameKey != gameKey) state.remove(gameKey)
        writeEnabledIds(state)
    }

    fun syncActiveCheats(
        gameKey: String,
        serial: String?,
        crc: String?,
        includeCheats: Boolean = true
    ) = synchronized(CHEAT_IO_LOCK) {
        if(GamePlatform.entries.any{it!=GamePlatform.N3DS && gameKey.startsWith("${it.name}_")})return@synchronized
        val source = resolveImportedFile(gameKey)
        if(source.isFile && parseCheatBlocks(source.readText()).any { it.format!="3ds-gateway" }) return@synchronized
        val normalizedGameKey = source.nameWithoutExtension
        val normalizedCrc = effectiveCrc(crc, serial, normalizedGameKey)
        val normalizedSerial = serial?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        val enabledCheatBlocks = if (source.exists() && includeCheats) {
            val enabledIds = storedValues(loadEnabledIds(), normalizedGameKey, gameKey)
            runCatching { parseCheatBlocks(source.readText()) }
                .getOrDefault(emptyList())
                .filter { enabledIds.contains(it.id) }
        } else {
            emptyList()
        }
        val blocks = enabledCheatBlocks.distinctBy(::cheatBlockSignature)
        val candidates = activeCheatFileCandidates(normalizedSerial, normalizedCrc)
        val target = canonicalActiveCheatFile(normalizedSerial, normalizedCrc)
        if (blocks.isEmpty()) {
            val stale = (candidates + recordedActiveCheatFiles(normalizedGameKey))
                .distinctBy(File::getAbsolutePath)
            stale.forEach { if (it.exists()) it.delete() }
            clearRecordedActiveCheatFiles(normalizedGameKey)
            return@synchronized
        }
        activeDir().mkdirs()
        val contents = buildCoreCheatContents(blocks, normalizedSerial) ?: return@synchronized
        candidates.filterNot { it == target }.forEach { if (it.exists()) it.delete() }
        recordedActiveCheatFiles(normalizedGameKey).filterNot { it == target }.forEach { if (it.exists()) it.delete() }
        writeTextAtomically(target, contents)
        recordActiveCheatFile(normalizedGameKey, target)
    }

    /** Gateway cheat file the core loads for the active imported selection. */
    fun activeCoreCheatFile(gameKey: String, serial: String?, crc: String?): File? {
        val normalizedCrc = effectiveCrc(crc, serial, normalizeGameKey(gameKey))
        val normalizedSerial = serial?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        val canonical = canonicalActiveCheatFile(normalizedSerial, normalizedCrc)
        if (canonical.exists()) return canonical
        return recordedActiveCheatFiles(normalizeGameKey(gameKey)).firstOrNull { it.exists() }
    }

    /**
     * Disc CRC is only known while a session is running, so imported cheats are
     * still usable outside a game by falling back to the serial or game key.
     */
    private fun effectiveCrc(crc: String?, serial: String?, gameKey: String): String {
        crc?.trim()?.uppercase()?.takeIf { it.isNotBlank() }?.let { return it }
        serial?.trim()?.uppercase()?.replace(Regex("[^A-Z0-9]"), "")?.takeIf { it.isNotBlank() }?.let { return it }
        return normalizeGameKey(gameKey).uppercase().takeIf { it.isNotBlank() } ?: "UNKNOWN"
    }

    fun deleteImportedCheats(gameKey: String, serial: String?, crc: String?) = synchronized(CHEAT_IO_LOCK) {
        val source = resolveImportedFile(gameKey)
        val normalizedGameKey = source.nameWithoutExtension
        source.delete()
        setEnabledBlocks(normalizedGameKey, emptySet())
        recordedActiveCheatFiles(normalizedGameKey).forEach { if (it.exists()) it.delete() }
        clearRecordedActiveCheatFiles(normalizedGameKey)
        val normalizedCrc = crc?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            ?: return@synchronized
        val normalizedSerial = serial?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        activeCheatFileCandidates(normalizedSerial, normalizedCrc).forEach { if (it.exists()) it.delete() }
    }

    fun exportJson(): JSONObject {
        return JSONObject().put("enabled", loadEnabledIds())
    }

    fun importJson(json: JSONObject) {
        writeEnabledIds(json.optJSONObject("enabled") ?: JSONObject())
    }

    private fun importedFile(gameKey: String): File = File(importedDir, "${sanitizeFileName(gameKey)}.txt")

    private fun resolveImportedFile(gameKey: String): File {
        // Serial-style keys reach this point with either '-' or '_' as the
        // separator depending on whether they came from the imported file
        // name, the game metadata or the cheat catalog. Try every spelling
        // before giving up, otherwise an existing pack is ignored (and its
        // active cheats get cleared) on a mere formatting difference.
        val exact = importedFile(normalizeGameKey(gameKey))
        val variants = linkedSetOf(
            exact.nameWithoutExtension,
            sanitizeFileName(gameKey.replace('-', '_')),
            sanitizeFileName(gameKey.replace('_', '-'))
        )
        for (variant in variants) {
            val candidate = importedFile(variant)
            val matches = importedDir.listFiles { file ->
                file.isFile && file.extension.equals("txt", ignoreCase = true) &&
                    file.nameWithoutExtension.equals(candidate.nameWithoutExtension, ignoreCase = true)
            }.orEmpty()
            matches.firstOrNull { it.name == candidate.name }?.let { return it }
            matches.firstOrNull()?.let { return it }
        }
        return exact
    }

    private fun loadEnabledIds(): JSONObject {
        if (!stateFile.exists()) return JSONObject()
        return runCatching { JSONObject(stateFile.readText()) }.getOrDefault(JSONObject())
    }

    private fun writeEnabledIds(json: JSONObject) {
        writeTextAtomically(stateFile, json.toString())
    }

    private fun sanitizeFileName(value: String): String {
        return value.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun normalizeGameKey(gameKey: String): String = sanitizeFileName(gameKey).ifBlank { "cheat" }

    private fun storedValues(state: JSONObject, normalizedKey: String, legacyKey: String): Set<String> {
        val caseInsensitiveKey = state.keys().asSequence().firstOrNull { key ->
            key.equals(normalizedKey, ignoreCase = true) || key.equals(legacyKey, ignoreCase = true)
        }
        return (state.optJSONArray(normalizedKey)
            ?: state.optJSONArray(legacyKey)
            ?: caseInsensitiveKey?.let(state::optJSONArray))
            ?.toStringSet()
            .orEmpty()
    }

    private fun activeCheatFileCandidates(serial: String?, crc: String): List<File> {
        val names = linkedSetOf<String>()
        if (!serial.isNullOrBlank()) names += "${sanitizeFileName(serial)}_$crc.txt"
        names += "$crc.pnach"
        val root = activeDir().canonicalFile
        return names.map { File(root, it) }
    }

    private fun canonicalActiveCheatFile(serial: String?, crc: String): File {
        // Azahar loads cheats/<TITLEID>.txt from its own user directory; the
        // active directory already points at that folder.
        if (serial != null && serial.matches(Regex("[0-9a-fA-F]{16}"))) {
            return File(activeDir().canonicalFile, "${serial.uppercase(Locale.US)}.txt")
        }
        val name = if (serial.isNullOrBlank()) {
            "$crc.txt"
        } else {
            "${sanitizeFileName(serial)}_$crc.txt"
        }
        return File(activeDir().canonicalFile, name)
    }

    /**
     * Azahar reads Gateway cheat files (`[name]` sections with raw code lines)
     * from its own cheats directory, keyed by the 16 hex digit title ID.
     */
    private fun buildCoreCheatContents(
        blocks: List<CheatBlock>,
        @Suppress("UNUSED_PARAMETER") serial: String?
    ): String? {
        val output = StringBuilder()
        blocks.forEach { block ->
            val label = cheatBlockLabel(block).replace('\n', ' ').replace('\r', ' ').trim()
            if (label.isEmpty()) return@forEach
            output.append('[').append(label).append("]\n")
            if (block.enabled) output.append("*citra_enabled\n")
            block.lines.forEach { line -> output.append(line).append('\n') }
            output.append('\n')
        }
        return output.toString().ifBlank { null }
    }

    private fun recordedActiveCheatFiles(gameKey: String): List<File> {
        val names = loadActiveTargets().optJSONArray(gameKey)?.toStringSet().orEmpty()
        val root = activeDir().canonicalFile
        return names.mapNotNull { name ->
            if (name != File(name).name || !name.endsWith(".txt", ignoreCase = true)) return@mapNotNull null
            File(root, name).canonicalFile.takeIf { it.parentFile == root }
        }
    }

    private fun recordActiveCheatFile(gameKey: String, target: File) {
        val state = loadActiveTargets()
        state.put(gameKey, JSONArray(listOf(target.name)))
        writeActiveTargets(state)
    }

    private fun clearRecordedActiveCheatFiles(gameKey: String) {
        val state = loadActiveTargets()
        state.remove(gameKey)
        writeActiveTargets(state)
    }

    private fun loadActiveTargets(): JSONObject {
        if (!activeTargetsFile.exists()) return JSONObject()
        return runCatching { JSONObject(activeTargetsFile.readText()) }.getOrDefault(JSONObject())
    }

    private fun writeActiveTargets(json: JSONObject) {
        writeTextAtomically(activeTargetsFile, json.toString())
    }

    private fun writeTextAtomically(file: File, contents: String) {
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(contents.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun activeDir(): File {
        return EmulatorStorage.cheatsDir(context, preferences.getEmulatorDataPathSync())
    }

    private companion object {
        val CHEAT_IO_LOCK = Any()
    }

    private fun cheatBlockLabel(block: CheatBlock): String {
        val author = block.author?.trim().orEmpty()
        return if (author.isEmpty()) block.title else "${block.title} (by $author)"
    }

    private fun cheatBlockSignature(block: CheatBlock): String {
        return block.title.trim().lowercase() + "\u0000" +
            block.format + "\u0000" +
            block.author?.trim().orEmpty() + "\u0000" +
            block.lines.joinToString("\n")
    }
}

private fun JSONArray.toStringSet(): Set<String> {
    return buildSet {
        for (index in 0 until length()) {
            val value = optString(index)
            if (value.isNotBlank()) add(value)
        }
    }
}

/** Parses the Gateway format used by Azahar: `[name]` sections with code lines. */
internal fun parseGatewayCheatBlocks(raw: String): List<CheatBlock> {
    val lines = raw.trimStart('\uFEFF').lineSequence().map { it.trim() }.toList()
    if (lines.none { it.startsWith("[") && it.endsWith("]") }) return emptyList()
    val blocks = mutableListOf<CheatBlock>()
    var title: String? = null
    var enabled = false
    var index = 1
    val code = mutableListOf<String>()

    fun flush() {
        val current = title
        if (current != null && code.isNotEmpty()) {
            val slug = current.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
            blocks += CheatBlock(
                id = "${slug.ifBlank { "cheat" }}_$index",
                title = current,
                lines = code.toList(),
                enabled = enabled
            )
            index++
        }
        code.clear()
        enabled = false
    }

    lines.forEach { line ->
        when {
            line.startsWith("[") && line.endsWith("]") -> {
                flush()
                title = line.substring(1, line.length - 1)
            }
            line.equals("*citra_enabled", ignoreCase = true) -> enabled = true
            line.isEmpty() || line.startsWith("//") || line.startsWith(";") -> Unit
            title != null -> code += line
        }
    }
    flush()
    return blocks
}

internal fun parseCheatBlocks(raw: String, serialFilter: String? = null): List<CheatBlock> {
    if(raw.trimStart().startsWith("{")) return runCatching { com.sbro.emucored.data.cheats.TypedCheatCatalog.parseStored(raw) }.getOrDefault(emptyList())
    return parseGatewayCheatBlocks(raw)
}

internal fun serializeImportedCheatBlocks(blocks:List<CheatBlock>):String {
    if(blocks.any{it.format!="3ds-gateway"}) return TypedCheatCatalog.serialize(blocks)
    return buildString { blocks.forEach { block ->
        append('[').append(block.title.replace('\n',' ').replace('\r',' ').replace(']',')')).append("]\n")
        if(block.enabled)append("*citra_enabled\n")
        block.lines.forEach{append(it).append('\n')};append('\n')
    } }
}
