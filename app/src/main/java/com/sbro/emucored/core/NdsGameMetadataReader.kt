// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.Locale

data class NdsGameMetadata(
    val title: String?,
    val serial: String?
)

object NdsGameMetadataReader {
    private const val HEADER_SIZE = 0x200

    fun read(context: Context, path: String): NdsGameMetadata? = runCatching {
        val header = openHeader(context, path) ?: return@runCatching null
        if (header.size < 0x0C) return@runCatching null
        val title = String(header, 0, 12, Charsets.ISO_8859_1)
            .substringBefore('\u0000')
            .trim()
            .takeIf { it.isNotBlank() }
        val gameCode = if (header.size >= 0x10) {
            String(header, 0x0C, 4, Charsets.ISO_8859_1)
                .trim()
                .takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (title == null && gameCode == null) null
        else NdsGameMetadata(
            title = title,
            serial = gameCode?.uppercase(Locale.ROOT)
        )
    }.getOrNull()

    private fun openHeader(context: Context, path: String): ByteArray? {
        val stream = when {
            path.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(path))
            path.startsWith("file://") -> File(Uri.parse(path).path.orEmpty()).inputStream()
            else -> File(path).inputStream()
        } ?: return null
        return stream.use { input ->
            val buffer = ByteArray(HEADER_SIZE)
            var read = 0
            while (read < buffer.size) {
                val count = input.read(buffer, read, buffer.size - read)
                if (count <= 0) break
                read += count
            }
            if (read == 0) null else buffer.copyOf(read)
        }
    }
}
