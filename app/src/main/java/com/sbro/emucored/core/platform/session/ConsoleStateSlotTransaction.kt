package com.sbro.emucored.core.platform.session

import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.SaveStateFileIntegrity
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Publishes a state and its identity metadata as one recoverable transaction. Canonical slot paths stay stable. */
object ConsoleStateSlotTransaction {
    private val mutex = Mutex()
    private data class Paths(val target: File) {
        val metadata = File("${target.path}.json")
        val preview = File("${target.path}.png")
        val stagedState = File("${target.path}.transaction-state.pending")
        val stagedMetadata = File("${target.path}.transaction-metadata.pending")
        val stagedPreview = File("${target.path}.transaction-preview.pending")
        val journal = File("${target.path}.transaction.json")
        val journalPending = File("${target.path}.transaction.pending")
        val oldState = File("${target.path}.previous")
        val oldMetadata = File("${target.path}.previous.json")
        val oldPreview = File("${target.path}.previous.png")
        val staged get() = listOf(stagedState, stagedMetadata, stagedPreview, journalPending, File("${stagedState.path}.tmp"))
        val backups get() = listOf(oldState, oldMetadata, oldPreview)
    }

    suspend fun save(target: File, writeState: suspend (File) -> Boolean, metadata: (File) -> JSONObject): Boolean =
        saveInternal(target, replaceExisting = true, preview = null, writeState, metadata)

    /** Imports reserve an empty slot under the same lock used by running sessions. */
    suspend fun saveNew(target: File, preview: File? = null, writeState: suspend (File) -> Boolean,
                        metadata: (File) -> JSONObject): Boolean =
        saveInternal(target, replaceExisting = false, preview, writeState, metadata)

    private suspend fun saveInternal(target: File, replaceExisting: Boolean, preview: File?,
                                     writeState: suspend (File) -> Boolean, metadata: (File) -> JSONObject): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                val p = Paths(target.canonicalFile)
                if (!recoverLocked(p) || (!replaceExisting && p.target.exists())) return@withLock false
                var prepared = false
                try {
                    require(p.target.parentFile!!.let { it.isDirectory || it.mkdirs() })
                    if (!writeState(p.stagedState)) return@withLock false
                    require(p.stagedState.isFile)
                    val json = metadata(p.stagedState)
                    val platform = GamePlatform.valueOf(json.getString("platform"))
                    require(SaveStateFileIntegrity.validate(p.stagedState, platform))
                    val digest = SaveStateFileIntegrity.sha256(p.stagedState)
                    json.optString("sha256").takeIf(String::isNotBlank)?.let { require(it == digest) }
                    json.put("sha256", digest)
                    val bytes = json.toString().toByteArray()
                    require(bytes.size <= 65_536)
                    sync(p.stagedState)
                    writeSynced(p.stagedMetadata, bytes)
                    preview?.let { copySynced(it, p.stagedPreview) }
                    val record = JSONObject().put("version", 1).put("phase", "prepared")
                        .put("hadState", p.target.isFile).put("hadMetadata", p.metadata.isFile).put("hadPreview", p.preview.isFile)
                        .put("sha256", digest).put("platform", platform.name)
                    if (p.target.isFile) copySynced(p.target, p.oldState)
                    if (p.metadata.isFile) copySynced(p.metadata, p.oldMetadata)
                    if (p.preview.isFile) copySynced(p.preview, p.oldPreview)
                    journal(p, record)
                    prepared = true
                    move(p.stagedState, p.target)
                    move(p.stagedMetadata, p.metadata)
                    if (preview != null) move(p.stagedPreview, p.preview)
                    else if (p.preview.exists()) require(p.preview.delete())
                    journal(p, record.put("phase", "committed"))
                    cleanup(p)
                    true
                } catch (_: Exception) {
                    if (prepared) runCatching { recoverLocked(p) }
                    false
                } finally {
                    if (!p.journal.exists()) {
                        p.staged.forEach(::deleteFile)
                        p.backups.forEach(::deleteFile)
                    }
                }
            }
        }

    suspend fun load(target: File, readState: suspend (File) -> Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val p = Paths(target.canonicalFile)
            if (!recoverLocked(p) || !p.target.isFile) return@withLock false
            readState(p.target)
        }
    }

    /** A manager never blocks the UI behind native I/O or reads an unfinished pair. */
    fun recover(target: File): Boolean {
        if (!mutex.tryLock()) return false
        return try { recoverLocked(Paths(target.canonicalFile)) } finally { mutex.unlock() }
    }

    fun recoverDirectory(directory: File): Boolean {
        if (!mutex.tryLock()) return false
        return try {
            directory.listFiles().orEmpty().filter { file ->
                listOf(".ecs.transaction.json", ".dss.transaction.json", ".cst.transaction.json").any(file.name::endsWith)
            }.all { journal ->
                recoverLocked(Paths(File(journal.path.removeSuffix(".transaction.json"))))
            }
        } finally { mutex.unlock() }
    }

    fun delete(target: File): Boolean {
        if (!mutex.tryLock()) return false
        return try {
            val p = Paths(target.canonicalFile)
            if (!recoverLocked(p) || !p.target.isFile || !p.target.delete()) false
            else { deleteFile(p.metadata); deleteFile(p.preview); cleanup(p); true }
        } finally { mutex.unlock() }
    }

    private fun recoverLocked(p: Paths): Boolean = runCatching {
        if (!p.journal.isFile) {
            // A pre-journal crash cannot have changed the canonical pair.
            p.staged.forEach(::deleteFile)
            p.backups.forEach(::deleteFile)
            return@runCatching true
        }
        require(p.journal.length() in 1..65_536)
        val record = JSONObject(p.journal.readText())
        require(record.getInt("version") == 1)
        val phase = record.getString("phase")
        require(phase == "prepared" || phase == "committed")
        val committed = phase == "committed" && p.target.isFile && p.metadata.isFile && runCatching {
            val platform = GamePlatform.valueOf(record.getString("platform"))
            SaveStateFileIntegrity.validate(p.target, platform) &&
                SaveStateFileIntegrity.sha256(p.target) == record.getString("sha256") &&
                JSONObject(p.metadata.readText()).getString("sha256") == record.getString("sha256")
        }.getOrDefault(false)
        if (!committed) {
            // Keep backups until the whole rollback finishes: recovery itself is crash-safe and repeatable.
            restore(p.target, p.oldState, record.getBoolean("hadState"))
            restore(p.metadata, p.oldMetadata, record.getBoolean("hadMetadata"))
            restore(p.preview, p.oldPreview, record.getBoolean("hadPreview"))
        }
        cleanup(p)
        true
    }.getOrDefault(false)

    private fun restore(target: File, backup: File, existed: Boolean) {
        if (existed) {
            require(backup.isFile)
            val temporary = File("${target.path}.recovering")
            try { copySynced(backup, temporary); move(temporary, target) } finally { deleteFile(temporary) }
        } else if (target.exists()) require(target.isFile && target.delete())
    }

    private fun journal(p: Paths, record: JSONObject) {
        writeSynced(p.journalPending, record.toString().toByteArray())
        move(p.journalPending, p.journal)
    }

    private fun cleanup(p: Paths) {
        // Delete the journal first only after a complete committed pair or a complete rollback exists.
        require(!p.journal.exists() || (p.journal.isFile && p.journal.delete()))
        p.staged.forEach(::deleteFile)
        p.backups.forEach(::deleteFile)
    }

    private fun deleteFile(file: File) { if (file.isFile) file.delete() }
    private fun sync(file: File) { FileOutputStream(file, true).use { it.fd.sync() } }
    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.flush(); it.fd.sync() }
    }
    private fun copySynced(source: File, target: File) {
        require(source.isFile)
        source.inputStream().use { input -> FileOutputStream(target).use { output ->
            input.copyTo(output); output.flush(); output.fd.sync()
        } }
        target.setLastModified(source.lastModified())
    }
    private fun move(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
