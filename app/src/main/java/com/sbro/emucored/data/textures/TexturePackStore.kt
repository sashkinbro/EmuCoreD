package com.sbro.emucored.data.textures

import com.sbro.emucored.core.platform.ConsolePlatform
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Identity must come from a parsed image header, never a guessed filename. */
internal data class TexturePackTarget(val platform: ConsolePlatform, val id: String) {
    init {
        require(platform in setOf(ConsolePlatform.N3DS, ConsolePlatform.GAMECUBE, ConsolePlatform.WII))
        require(id.matches(if (platform == ConsolePlatform.N3DS) Regex("[0-9A-F]{16}") else Regex("[A-Z0-9]{6}")))
        require(id.any { it != '0' })
    }
}
internal data class TexturePackLimits(val maxEntries: Int = 100_000, val maxFileBytes: Long = 268_435_456,
    val maxTotalBytes: Long = 17_179_869_184, val maxArchiveBytes: Long = 17_179_869_184)
internal data class TexturePackStats(
    val fileCount: Int = 0,
    val textureCount: Int = 0,
    val totalBytes: Long = 0,
    val lastModifiedAt: Long = 0
)

/** Bounded ZIP installation at the native core's existing directory, with recoverable directory publication. */
internal class TexturePackStore(private val filesDirectory: File, private val limits: TexturePackLimits = TexturePackLimits()) {
    fun directory(target: TexturePackTarget): File = (if (target.platform == ConsolePlatform.N3DS)
        File(filesDirectory, "azahar/load/textures/${target.id}")
    else File(filesDirectory, "consoles/dolphin/Load/Textures/${target.id}")).also {
        require(it.canonicalPath.startsWith(filesDirectory.canonicalPath + File.separator))
    }

    fun storageRoots(): List<File> = listOf(
        File(filesDirectory, "azahar/load/textures"),
        File(filesDirectory, "consoles/dolphin/Load/Textures")
    )

    fun inspect(target: TexturePackTarget): TexturePackStats = synchronized(lock) {
        recover(target)
        stats(directory(target))
    }

    /**
     * Lists installed pack directories from both core storage roots. Dolphin
     * shares one texture folder between GameCube and Wii, so those packs carry
     * the GameCube target identity for storage operations.
     */
    fun listInstalled(): List<TexturePackTarget> = synchronized(lock) {
        buildList {
            val n3dsRoot = File(filesDirectory, "azahar/load/textures")
            n3dsRoot.listFiles()?.filter { it.isDirectory && it.name.matches(N3DS_ID) }?.forEach {
                TexturePackTarget(ConsolePlatform.N3DS, it.name.uppercase(Locale.ROOT))
                    .takeIf { target -> runCatching { recover(target) }.isSuccess }
                    ?.let(::add)
            }
            val dolphinRoot = File(filesDirectory, "consoles/dolphin/Load/Textures")
            dolphinRoot.listFiles()?.filter { it.isDirectory && it.name.matches(DOLPHIN_ID) }?.forEach {
                TexturePackTarget(ConsolePlatform.GAMECUBE, it.name.uppercase(Locale.ROOT))
                    .takeIf { target -> runCatching { recover(target) }.isSuccess }
                    ?.let(::add)
            }
        }
    }

    fun detectTarget(archive: File): TexturePackTarget? {
        require(archive.isFile && archive.length() in 1..limits.maxArchiveBytes)
        return runCatching {
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val relative = entries.nextElement().name.replace('\\', '/')
                    val parts = relative.split('/').filter(String::isNotBlank)
                    for (part in parts) {
                        if (part.matches(N3DS_ID)) {
                            return@use TexturePackTarget(
                                ConsolePlatform.N3DS,
                                part.uppercase(Locale.ROOT)
                            )
                        }
                        if (part.matches(DOLPHIN_ID)) {
                            return@use TexturePackTarget(
                                ConsolePlatform.GAMECUBE,
                                part.uppercase(Locale.ROOT)
                            )
                        }
                    }
                }
                null
            }
        }.getOrNull()
    }

    fun install(target: TexturePackTarget, archive: File): TexturePackStats = synchronized(lock) {
        recover(target)
        require(archive.isFile && archive.length() in 1..limits.maxArchiveBytes)
        val destination = directory(target)
        val stage = sibling(target, "stage")
        require(!stage.exists())
        require(stage.mkdirs())
        try {
            val names = mutableSetOf<String>()
            var total = 0L
            var count = 0
            var textures = 0
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(++count <= limits.maxEntries)
                    val relative = relativeName(target, entry.name, entry.isDirectory)
                    if (relative.isEmpty()) continue
                    require(names.add(relative.lowercase(Locale.ROOT)))
                    val output = File(stage, relative)
                    require(output.canonicalPath.startsWith(stage.canonicalPath + File.separator))
                    if (entry.isDirectory) { require(output.mkdirs() || output.isDirectory); continue }
                    require(entry.size in 0..limits.maxFileBytes)
                    require(allowed(target, output))
                    output.parentFile!!.mkdirs()
                    var size = 0L
                    zip.getInputStream(entry).use { input -> FileOutputStream(output).use { stream ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            size += n; total += n
                            require(size <= limits.maxFileBytes && total <= limits.maxTotalBytes)
                            stream.write(buffer, 0, n)
                        }
                        stream.flush(); stream.fd.sync()
                    } }
                    require(size == entry.size)
                    if (isTexture(output)) { require(validTexture(output)); textures++ }
                    else if (output.name == "pack.json") {
                        require(output.length() <= 16_777_216)
                        JSONObject(output.readText())
                    }
                }
            }
            require(textures > 0)
            val result = stats(stage)
            publish(target, stage)
            result
        } finally {
            // A prepared transaction retains everything required by recovery.
            if (!sibling(target, "journal").exists()) removeTree(stage)
        }
    }

    fun export(target: TexturePackTarget, output: OutputStream) = synchronized(lock) {
        recover(target)
        val root = directory(target)
        require(stats(root).textureCount > 0)
        ZipOutputStream(output).use { zip ->
            var total = 0L
            var count = 0
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                require(++count <= limits.maxEntries && file.canonicalPath.startsWith(root.canonicalPath + File.separator))
                require(allowed(target, file) && file.length() <= limits.maxFileBytes)
                val name = file.relativeTo(root).invariantSeparatorsPath
                relativeName(target, name, false)
                zip.putNextEntry(ZipEntry("${target.id}/$name"))
                file.inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var size = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n; total += n
                        require(size <= limits.maxFileBytes && total <= limits.maxTotalBytes)
                        zip.write(buffer, 0, n)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    fun delete(target: TexturePackTarget) = synchronized(lock) {
        recover(target)
        val root = directory(target)
        if (root.exists()) {
            val old = sibling(target, "old")
            writeJournal(target, JSONObject().put("phase", "deleting"))
            move(root, old)
            removeTree(old)
            require(sibling(target, "journal").delete())
        }
    }

    private fun publish(target: TexturePackTarget, stage: File) {
        val destination = directory(target)
        val old = sibling(target, "old")
        require(!destination.exists() || destination.isDirectory)
        val record = JSONObject().put("phase", "prepared").put("hadOld", destination.isDirectory)
        writeJournal(target, record)
        try {
            if (destination.exists()) move(destination, old)
            move(stage, destination)
            writeJournal(target, record.put("phase", "committed"))
            require(sibling(target, "journal").delete())
            removeTree(old)
        } catch (error: Exception) {
            recover(target)
            throw error
        }
    }

    private fun recover(target: TexturePackTarget) {
        val root = directory(target)
        require(root.parentFile!!.let { it.isDirectory || it.mkdirs() })
        require(!root.exists() || root.isDirectory)
        val stage = sibling(target, "stage")
        val old = sibling(target, "old")
        val journal = sibling(target, "journal")
        if (journal.exists()) {
            require(journal.isFile && journal.length() in 1..4096)
            val record = JSONObject(journal.readText())
            when (record.getString("phase")) {
                "prepared" -> {
                    if (old.exists()) { removeTree(root); move(old, root) }
                    else if (!record.getBoolean("hadOld")) removeTree(root)
                    else require(root.isDirectory)
                }
                "committed" -> require(root.isDirectory)
                "deleting" -> { removeTree(root); removeTree(old) }
                else -> error("Unknown texture publication phase")
            }
            require(journal.delete())
        } else if (old.exists() && !root.exists()) move(old, root)
        removeTree(stage); removeTree(old)
        sibling(target, "journal-pending").takeIf { it.isFile }?.delete()
    }

    private fun writeJournal(target: TexturePackTarget, json: JSONObject) {
        val pending = sibling(target, "journal-pending")
        FileOutputStream(pending).use { it.write(json.toString().toByteArray()); it.flush(); it.fd.sync() }
        move(pending, sibling(target, "journal"))
    }
    private fun sibling(target: TexturePackTarget, suffix: String) = File(directory(target).parentFile, ".${target.id}.texture-$suffix")
    private fun move(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun removeTree(file: File) { require(!file.exists() || file.deleteRecursively()) }

    private fun relativeName(target: TexturePackTarget, raw: String, directory: Boolean): String {
        require(raw.length in 1..1024 && !raw.startsWith('/') && '\\' !in raw && ':' !in raw)
        val parts = raw.removeSuffix(if (directory) "/" else "").split('/').toMutableList()
        require(parts.all { it.isNotBlank() && it != "." && it != ".." && !it.startsWith('.') })
        if (parts.first().equals("load", true)) parts.removeAt(0)
        if (parts.firstOrNull().equals("textures", true)) parts.removeAt(0)
        if (parts.isEmpty() && directory) return ""
        val first = parts.firstOrNull() ?: error("Empty texture path")
        if (first == target.id || (target.platform != ConsolePlatform.N3DS && first == target.id.take(3))) parts.removeAt(0)
        else require(!first.matches(Regex("[0-9A-Fa-f]{16}|[A-Z0-9]{6}")))
        // Explicit wrapper directories are harmless and have no output payload.
        if (parts.isEmpty() && directory) return ""
        require(parts.isNotEmpty())
        return parts.joinToString("/")
    }

    private fun stats(root: File): TexturePackStats {
        if (!root.isDirectory) return TexturePackStats()
        var files = 0; var textures = 0; var bytes = 0L; var modified = root.lastModified()
        root.walkTopDown().filter { it.isFile }.forEach {
            require(it.canonicalPath.startsWith(root.canonicalPath + File.separator))
            require(++files <= limits.maxEntries)
            bytes += it.length(); require(bytes <= limits.maxTotalBytes)
            modified = maxOf(modified, it.lastModified())
            if (isTexture(it)) textures++
        }
        return TexturePackStats(files, textures, bytes, modified)
    }
    private fun allowed(target: TexturePackTarget, file: File): Boolean = when (file.extension.lowercase(Locale.ROOT)) {
        "png", "dds" -> true
        "ktx" -> target.platform == ConsolePlatform.N3DS
        "json" -> target.platform == ConsolePlatform.N3DS && file.name == "pack.json"
        "txt", "md" -> true
        else -> false
    }
    private fun isTexture(file: File) = file.extension.lowercase(Locale.ROOT) in setOf("png", "dds", "ktx")
    private fun validTexture(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 33)
            val header = ByteArray(minOf(input.length(), 128).toInt()); input.readFully(header)
            val little = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            fun dimensions(w: Int, h: Int) { require(w in 1..16384 && h in 1..16384 && w.toLong() * h <= 67_108_864) }
            when (file.extension.lowercase(Locale.ROOT)) {
                "png" -> {
                    require(header.copyOfRange(0, 8).contentEquals(PNG))
                    val big = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                    require(big.getInt(8) == 13 && big.getInt(12) == 0x49484452)
                    dimensions(big.getInt(16), big.getInt(20))
                    input.seek(8)
                    var ended = false
                    val buffer = ByteArray(64 * 1024)
                    while (input.filePointer + 12 <= input.length()) {
                        val length = input.readInt(); val type = input.readInt()
                        require(length >= 0 && input.filePointer + length + 4 <= input.length())
                        val crc = CRC32().apply { update(ByteBuffer.allocate(4).putInt(type).array()) }
                        var remaining = length
                        while (remaining > 0) { val n = minOf(remaining, buffer.size); input.readFully(buffer, 0, n); crc.update(buffer, 0, n); remaining -= n }
                        require((input.readInt().toLong() and 0xffffffffL) == crc.value)
                        if (type == 0x49454E44) { require(length == 0 && input.filePointer == input.length()); ended = true; break }
                    }
                    require(ended)
                }
                "dds" -> { require(header.size == 128 && String(header, 0, 4, Charsets.US_ASCII) == "DDS " && little.getInt(4) == 124 && input.length() > 128); dimensions(little.getInt(16), little.getInt(12)) }
                "ktx" -> {
                    require(header.size >= 64)
                    val identifier = header.copyOfRange(0, 12)
                    if (identifier.contentEquals(KTX1)) { require(little.getInt(12) == 0x04030201); dimensions(little.getInt(36), little.getInt(40)) }
                    else { require(identifier.contentEquals(KTX2)); dimensions(little.getInt(20), little.getInt(24)) }
                }
                else -> error("Unsupported texture")
            }
        }; true
    }.getOrDefault(false)

    companion object {
        private val lock = Any()
        private val N3DS_ID = Regex("[0-9A-Fa-f]{16}")
        private val DOLPHIN_ID = Regex("[A-Za-z0-9]{6}")
        private val PNG = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        private val KTX1 = byteArrayOf(171.toByte(),75,84,88,32,49,49,187.toByte(),13,10,26,10)
        private val KTX2 = byteArrayOf(171.toByte(),75,84,88,32,50,48,187.toByte(),13,10,26,10)
    }
}
