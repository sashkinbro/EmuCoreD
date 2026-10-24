package com.sbro.emucored.data

import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.session.ConsoleSessionStorage
import com.sbro.emucored.core.platform.session.ConsoleStateSlotTransaction
import java.io.File
import org.json.JSONObject

internal data class ConsoleSaveStateRecord(
    val file: File, val platform: GamePlatform, val gamePath: String,
    val slot: Int, val title: String, val metadata: JSONObject
)

/** Indexes published files only. Directory names and sidecar identity must agree. */
internal class ConsoleSaveStateIndex(private val filesDirectory: File) {
    private val root get() = ConsoleSessionStorage.stateRoot(filesDirectory)
    fun entries(gamePath: String? = null): List<ConsoleSaveStateRecord> {
        val result = mutableListOf<ConsoleSaveStateRecord>()
        ConsolePlatform.entries.filter { it !in setOf(ConsolePlatform.N3DS, ConsolePlatform.NDS) }.forEach { console ->
            File(root, console.id).listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[0-9a-f]{64}")) }.forEach directoryLoop@ { directory ->
                if (directory.canonicalFile.parentFile != File(root, console.id).canonicalFile) return@directoryLoop
                if (!ConsoleStateSlotTransaction.recoverDirectory(directory)) return@directoryLoop
                directory.listFiles().orEmpty().filter { it.isFile && it.extension == "ecs" }.forEach { file ->
                    read(file)?.takeIf { gamePath == null || it.gamePath == gamePath }?.let(result::add)
                }
            }
        }
        return result
    }

    fun read(file: File): ConsoleSaveStateRecord? = runCatching {
        require(ConsoleStateSlotTransaction.recover(file))
        val sidecar = File("${file.path}.json")
        require(sidecar.isFile && sidecar.length() in 1..65_536)
        val metadata = JSONObject(sidecar.readText())
        val platform = GamePlatform.valueOf(metadata.getString("platform"))
        require(platform !in setOf(GamePlatform.N3DS, GamePlatform.NDS))
        val gamePath = metadata.getString("gamePath").also { require(it.isNotBlank() && it.length <= 8192) }
        val slot = metadata.getInt("slot")
        val expected = ConsoleSessionStorage.stateFile(filesDirectory, platform.console, gamePath, slot).canonicalFile
        require(file.canonicalFile == expected && expected.path.startsWith(root.canonicalPath + File.separator))
        require(SaveStateFileIntegrity.validate(file, platform))
        val digest = metadata.optString("sha256")
        if (platform in setOf(GamePlatform.GAMECUBE, GamePlatform.WII) || digest.isNotBlank()) {
            require(digest.matches(Regex("[0-9a-f]{64}")) && SaveStateFileIntegrity.sha256(file) == digest)
        }
        metadata.optString("core").takeIf(String::isNotBlank)?.let { require(it == platform.console.core.name) }
        ConsoleSaveStateRecord(file, platform, gamePath, slot,
            metadata.optString("title").takeIf(String::isNotBlank)?.take(4096) ?: File(gamePath).nameWithoutExtension, metadata)
    }.getOrNull()
}
