package com.sbro.emucored.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

data class N3dsGameMetadata(val title: String?, val serial: String?)

/**
 * Reads the SMDH application metadata (title and icon) from Nintendo 3DS game
 * images. The SMDH block lives inside the NCCH ExeFS of .3ds/.cci/.cxi files
 * and at the end of homebrew .3dsx files, so the reader scans a bounded window
 * of the image for the "SMDH" magic instead of parsing the full container.
 */
object N3dsGameMetadataReader {
    private const val SMDH_MAGIC = 0x534D4448 // "SMDH"
    private const val SMDH_SIZE = 0x2A00
    private const val TITLE_ENGLISH_OFFSET = 0x108
    private const val TITLE_LENGTH = 0x100
    private const val PRODUCT_CODE_OFFSET = 0x1030
    private const val PRODUCT_CODE_LENGTH = 0x10
    private const val LARGE_ICON_OFFSET = 0x1800
    private const val LARGE_ICON_SIZE = 48
    private const val SCAN_HEAD_BYTES = 16L * 1024 * 1024
    private const val SCAN_TAIL_BYTES = 2L * 1024 * 1024
    private const val CHUNK_BYTES = 1 * 1024 * 1024
    private const val NCCH_HEADER_SIZE = 0x200

    fun read(context: Context, path: String): N3dsGameMetadata? = runCatching {
        val smdh = readSmdh(context, path)
        val title = smdh?.let { decodeTitle(slice(it, TITLE_ENGLISH_OFFSET, TITLE_LENGTH)) }
        val smdhProductCode = smdh?.let { decodeProductCode(slice(it, PRODUCT_CODE_OFFSET, PRODUCT_CODE_LENGTH)) }
        // The NCCH header carries the 16 hex digit title ID, the 3DS equivalent
        // of a disc serial, plus a product code (CTR-P-XXXX).
        val ncch = readNcchIdentity(context, path)
        val serial = ncch?.titleId?.takeIf { it != 0L }
            ?.let { String.format(Locale.US, "%016x", it) }
            ?: ncch?.productCode
            ?: smdhProductCode
        if (title == null && serial == null) null else N3dsGameMetadata(title, serial)
    }.getOrNull()

    private data class NcchIdentity(val titleId: Long, val productCode: String?)

    private fun readNcchIdentity(context: Context, path: String): NcchIdentity? {
        val magic = findMagic(context, path, "NCCH", SCAN_HEAD_BYTES) ?: return null
        val header = readAt(context, path, magic, NCCH_HEADER_SIZE) ?: return null
        if (header.size < NCCH_HEADER_SIZE) return null
        val titleId = ByteBuffer.wrap(header, 0x18, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val productCode = decodeProductCode(slice(header, 0x50, PRODUCT_CODE_LENGTH))
        return NcchIdentity(titleId, productCode)
    }

    private fun findMagic(context: Context, path: String, magic: String, scanBytes: Long): Long? {
        openRandom(context, path)?.use { file ->
            val length = file.length()
            if (length < magic.length) return null
            val buffer = ByteArray(CHUNK_BYTES + magic.length)
            val pattern = magic.toByteArray(Charsets.US_ASCII)
            var position = 0L
            var carry = 0
            val limit = minOf(length, scanBytes)
            while (position < limit) {
                file.seek(position)
                val read = file.read(buffer, carry, minOf(CHUNK_BYTES.toLong(), limit - position).toInt())
                if (read <= 0) return null
                val total = carry + read
                var index = 0
                while (index + pattern.size <= total) {
                    var matches = true
                    for (i in pattern.indices) {
                        if (buffer[index + i] != pattern[i]) {
                            matches = false
                            break
                        }
                    }
                    if (matches) return position + index - carry
                    index++
                }
                if (total >= pattern.size) {
                    System.arraycopy(buffer, total - pattern.size, buffer, 0, pattern.size)
                    carry = pattern.size
                }
                position += read
            }
        }
        return null
    }

    private fun readAt(context: Context, path: String, offset: Long, size: Int): ByteArray? =
        openRandom(context, path)?.use { file ->
            if (offset + size > file.length()) return null
            file.seek(offset)
            val bytes = ByteArray(size)
            var filled = 0
            while (filled < size) {
                val read = file.read(bytes, filled, size - filled)
                if (read <= 0) break
                filled += read
            }
            bytes.copyOf(filled)
        }

    private fun openRandom(context: Context, path: String): RandomAccessFile? {
        if (path.startsWith("content://")) {
            val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                ?: return null
            // The descriptor stays open through the returned handle; close it
            // once the RandomAccessFile is closed.
            return object : RandomAccessFile("/proc/self/fd/${descriptor.fd}", "r") {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        descriptor.close()
                    }
                }
            }
        }
        val file = File(uriToPath(path) ?: path)
        if (!file.isFile) return null
        return RandomAccessFile(file, "r")
    }

    fun extractIcon(context: Context, path: String): String? = runCatching {
        val directory = File(context.cacheDir, "game-covers/embedded").apply { mkdirs() }
        val identity = if (path.startsWith("content://")) {
            val document = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, Uri.parse(path))
            "$path:${document?.length()}:${document?.lastModified()}"
        } else {
            val file = File(uriToPath(path) ?: path)
            "$path:${file.length()}:${file.lastModified()}"
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = File(directory, "$hash.png")
        if (isImage(target)) return@runCatching target.absolutePath

        val smdh = readSmdh(context, path) ?: return@runCatching null
        val raw = slice(smdh, LARGE_ICON_OFFSET, LARGE_ICON_SIZE * LARGE_ICON_SIZE * 2)
        if (raw.size < LARGE_ICON_SIZE * LARGE_ICON_SIZE * 2) return@runCatching null

        val bitmap = Bitmap.createBitmap(LARGE_ICON_SIZE, LARGE_ICON_SIZE, Bitmap.Config.ARGB_8888)
        val colors = IntArray(LARGE_ICON_SIZE * LARGE_ICON_SIZE)
        for (index in colors.indices) {
            val value = (raw[index * 2].toInt() and 0xFF) or ((raw[index * 2 + 1].toInt() and 0xFF) shl 8)
            val red = (value shr 11) and 0x1F
            val green = (value shr 5) and 0x3F
            val blue = value and 0x1F
            colors[index] = 0xFF000000.toInt() or
                ((red shl 3 or (red shr 2)) shl 16) or
                ((green shl 2 or (green shr 4)) shl 8) or
                (blue shl 3 or (blue shr 2))
        }
        bitmap.setPixels(colors, 0, LARGE_ICON_SIZE, 0, 0, LARGE_ICON_SIZE, LARGE_ICON_SIZE)
        val temporary = File.createTempFile(hash, ".tmp", directory)
        try {
            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!isImage(temporary)) return@runCatching null
            if (!temporary.renameTo(target)) temporary.copyTo(target, overwrite = true)
            target.takeIf(::isImage)?.absolutePath
        } finally {
            temporary.delete()
            bitmap.recycle()
        }
    }.getOrNull()

    private fun readSmdh(context: Context, path: String): ByteArray? {
        if (path.startsWith("content://")) {
            val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                ?: return null
            descriptor.use { parcel ->
                RandomAccessFile("/proc/self/fd/${parcel.fd}", "r").use { file ->
                    return readSmdh(file)
                }
            }
        }
        val file = File(uriToPath(path) ?: path)
        if (!file.isFile) return null
        RandomAccessFile(file, "r").use { return readSmdh(it) }
    }

    private fun uriToPath(path: String): String? =
        if (path.startsWith("file://")) runCatching { Uri.parse(path).path }.getOrNull() else null

    /** Scans bounded windows of the image and returns the SMDH block. */
    private fun readSmdh(file: RandomAccessFile): ByteArray? {
        val length = file.length()
        if (length < SMDH_SIZE) return null
        val headEnd = minOf(length, SCAN_HEAD_BYTES)
        if (findMagic(file, 0, headEnd)) return readExact(file, SMDH_SIZE)
        if (length > SCAN_TAIL_BYTES && findMagic(file, length - SCAN_TAIL_BYTES, length)) {
            return readExact(file, SMDH_SIZE)
        }
        return null
    }

    /** Leaves the file positioned at the first "SMDH" magic inside [start, end). */
    private fun findMagic(file: RandomAccessFile, start: Long, end: Long): Boolean {
        val buffer = ByteArray(CHUNK_BYTES + 3)
        var position = start
        var carry = 0
        while (position < end) {
            val toRead = minOf(CHUNK_BYTES.toLong(), end - position).toInt()
            file.seek(position)
            val read = file.read(buffer, carry, toRead)
            if (read <= 0) return false
            val total = carry + read
            var index = 0
            while (index + 3 < total) {
                val magic = ((buffer[index].toInt() and 0xFF) shl 24) or
                    ((buffer[index + 1].toInt() and 0xFF) shl 16) or
                    ((buffer[index + 2].toInt() and 0xFF) shl 8) or
                    (buffer[index + 3].toInt() and 0xFF)
                if (magic == SMDH_MAGIC) {
                    file.seek(position + index - carry)
                    return true
                }
                index++
            }
            if (total >= 3) {
                buffer[0] = buffer[total - 3]
                buffer[1] = buffer[total - 2]
                buffer[2] = buffer[total - 1]
                carry = 3
            }
            position += read
        }
        return false
    }

    private fun readExact(file: RandomAccessFile, size: Int): ByteArray? {
        val bytes = ByteArray(size)
        var filled = 0
        while (filled < size) {
            val read = file.read(bytes, filled, size - filled)
            if (read <= 0) return null
            filled += read
        }
        return bytes
    }

    private fun slice(bytes: ByteArray, offset: Int, size: Int): ByteArray {
        if (offset >= bytes.size) return ByteArray(0)
        val end = minOf(bytes.size, offset + size)
        return bytes.copyOfRange(offset, end)
    }

    private fun decodeTitle(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val title = String(bytes, Charsets.UTF_16LE).substringBefore('\u0000').trim()
        return title.takeIf { it.isNotBlank() }
    }

    private fun decodeProductCode(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val code = String(bytes, Charsets.US_ASCII).substringBefore('\u0000').trim()
        return code.takeIf { it.isNotBlank() && it.all { ch -> ch.isLetterOrDigit() || ch == '-' } }
    }

    private fun isImage(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0
    }
}
