// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Lists and stages individual games without changing the source ZIP. */
internal object GameZipArchive {
    private const val MAX_ENTRIES = 20_000

    fun entries(archive: File, checkActive: () -> Unit = {}, supports: (String) -> Boolean): List<String> =
        ZipFile(archive).use { zip ->
            validatedEntries(zip, checkActive).filter { !it.isDirectory && supports(it.name) }.map { it.name }
        }

    /** Caller owns the returned file and must remove it when the guest releases it. */
    fun extract(
        archive: File, entry: String, directory: File, maxBytes: Long,
        checkActive: () -> Unit = {}
    ): File = ZipFile(archive).use { zip ->
        require(maxBytes > 0)
        val selected = validatedEntries(zip, checkActive).singleOrNull { !it.isDirectory && it.name == entry }
            ?: throw IllegalArgumentException("Game entry is missing")
        require(selected.size in 1..maxBytes) { "Game entry has an invalid size" }
        val extension = entry.substringAfterLast('.', "rom").lowercase()
        require(extension.length in 1..12 && extension.all { it in 'a'..'z' || it in '0'..'9' })
        require(directory.isDirectory || directory.mkdirs())
        val target = File.createTempFile("game-", ".$extension", directory)
        var completed = false
        try {
            val crc = CRC32()
            var size = 0L
            zip.getInputStream(selected).use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(count.toLong() <= maxBytes - size) { "Game entry is too large" }
                        output.write(buffer, 0, count)
                        crc.update(buffer, 0, count)
                        size += count
                    }
                }
            }
            checkActive()
            require(size == selected.size && crc.value == selected.crc) { "Game entry is corrupt" }
            completed = true
            target
        } finally {
            if (!completed) target.delete()
        }
    }

    private fun validatedEntries(zip: ZipFile, checkActive: () -> Unit): List<ZipEntry> {
        val seen = HashSet<String>()
        val result = ArrayList<ZipEntry>()
        val iterator = zip.entries()
        while (iterator.hasMoreElements()) {
            checkActive()
            require(result.size < MAX_ENTRIES) { "Archive has too many entries" }
            val entry = iterator.nextElement()
            val name = entry.name
            require(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name && ':' !in name &&
                '\u0000' !in name && name.split('/').none { it == "." || it == ".." }) {
                "Archive contains an unsafe entry name"
            }
            require(seen.add(name)) { "Archive contains duplicate entry names" }
            result.add(entry)
        }
        return result
    }
}
