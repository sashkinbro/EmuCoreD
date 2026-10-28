package com.sbro.emucored.core

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

/** Extract into private staging before replacing an installed driver. */
internal object GpuDriverArchive {
    private const val MAX_BYTES = 512L * 1024 * 1024
    private const val MAX_ENTRIES = 2048

    @Synchronized
    fun install(
        input: InputStream,
        archiveName: String,
        root: File,
        maxBytes: Long = MAX_BYTES,
        maxEntries: Int = MAX_ENTRIES,
        validate: (File, List<String>) -> Unit
    ): String {
        require(maxBytes > 0 && maxEntries > 0)
        check(root.isDirectory || root.mkdirs()) { "Could not create driver directory" }
        val name = archiveName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.')
            .take(120).ifBlank { "custom-driver" }
        val target = File(root, name)
        val stage = Files.createTempDirectory(root.toPath(), ".incoming-").toFile()
        var backup: File? = null
        try {
            val files = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            var count = 0
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= maxEntries) { "Too many files in driver archive" }
                    val path = entry.name.replace('\\', '/')
                    val segments = path.trimEnd('/').split('/')
                    require(!path.startsWith('/') && segments.all { it.isNotBlank() && it != "." && it != ".." && ':' !in it }) {
                        "Invalid path in driver archive"
                    }
                    val relative = segments.joinToString("/")
                    require(seen.add(relative)) { "Duplicate path in driver archive" }
                    val output = File(stage, relative)
                    require(output.canonicalFile.toPath().startsWith(stage.canonicalFile.toPath()))
                    if (entry.isDirectory) {
                        check(output.isDirectory || output.mkdirs())
                    } else {
                        check(output.parentFile.isDirectory || output.parentFile.mkdirs())
                        output.outputStream().use { out ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= maxBytes) { "Driver archive is too large" }
                                out.write(buffer, 0, read)
                            }
                        }
                        files += relative
                    }
                    zip.closeEntry()
                }
            }
            validate(stage, files)
            if (target.exists()) {
                backup = Files.createTempDirectory(root.toPath(), ".backup-").toFile()
                check(backup.delete() && target.renameTo(backup)) { "Could not preserve existing driver" }
            }
            check(stage.renameTo(target)) { "Could not publish driver" }
            backup?.deleteRecursively()
            backup = null
            return name
        } finally {
            if (backup != null && !target.exists()) check(backup.renameTo(target)) { "Could not restore existing driver" }
            stage.deleteRecursively()
        }
    }
}
