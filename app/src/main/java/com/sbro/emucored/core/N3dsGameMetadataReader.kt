package com.sbro.emucored.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class N3dsGameMetadata(val title: String?, val serial: String?)

/**
 * Reads the SMDH application metadata (title and icon) from Nintendo 3DS game
 * images. The SMDH block lives inside the NCCH ExeFS of .3ds/.cci/.cxi files,
 * at the end of homebrew .3dsx files and inside the meta section of .cia
 * packages. The reader parses those container headers directly and only falls
 * back to a bounded magic scan for structures it does not recognise, so a
 * library scan reads a few kilobytes per game instead of tens of megabytes.
 */
object N3dsGameMetadataReader {
    private const val SMDH_MAGIC = 0x534D4448 // "SMDH"
    private const val SMDH_SIZE = 0x36C0
    private const val TITLE_ENGLISH_OFFSET = 0x208
    private const val TITLE_LENGTH = 0x80
    private const val PRODUCT_CODE_LENGTH = 0x10
    private const val LARGE_ICON_OFFSET = 0x24C0
    private const val LARGE_ICON_SIZE = 48

    // SMDH icons are stored in 8x8 Morton (Z-order) tiles; these lookup tables
    // match VideoCore::MortonInterleave from the core.
    private val MORTON_X = intArrayOf(0x00, 0x01, 0x04, 0x05, 0x10, 0x11, 0x14, 0x15)
    private val MORTON_Y = intArrayOf(0x00, 0x02, 0x08, 0x0A, 0x20, 0x22, 0x28, 0x2A)

    // NCSD/NCCH container layout.
    private const val NCSD_MAGIC_OFFSET = 0x100
    private const val NCSD_PARTITION_TABLE_OFFSET = 0x120
    private const val NCCH_HEADER_SIZE = 0x200
    private const val NCCH_MAGIC_OFFSET = 0x100
    private const val NCCH_PROGRAM_ID_OFFSET = 0x118
    private const val NCCH_PRODUCT_CODE_OFFSET = 0x150
    private const val NCCH_EXEFS_OFFSET_OFFSET = 0x1A0
    private const val MEDIA_UNIT = 0x200L
    private const val EXEFS_ENTRY_COUNT = 8
    private const val EXEFS_ENTRY_SIZE = 16
    private const val MAX_NCSD_PARTITIONS = 8

    // CIA container layout (matches FileSys::CIAContainer).
    private const val CIA_HEADER_SIZE = 0x2020
    private const val CIA_SECTION_ALIGNMENT = 0x40L
    private const val CIA_META_SMDH_OFFSET = 0x400L

    // Bounded scan fallback for images whose headers cannot be parsed.
    private const val SCAN_HEAD_BYTES = 4L * 1024 * 1024
    private const val SCAN_TAIL_BYTES = 2L * 1024 * 1024
    private const val CHUNK_BYTES = 1 * 1024 * 1024

    private const val CACHE_LIMIT = 1024
    private val metadataCache = ConcurrentHashMap<String, N3dsGameMetadata>()
    private val smdhCache = ConcurrentHashMap<String, ByteArray>()

    /**
     * Random access to a game image. SAF descriptors are read through
     * [Os.pread] on the original file descriptor: reopening the provider
     * through /proc is not permitted under scoped storage.
     */
    private class ImageHandle(
        val length: Long,
        private val file: RandomAccessFile?,
        private val descriptor: ParcelFileDescriptor?
    ) : AutoCloseable {
        fun readAt(offset: Long, size: Int): ByteArray? {
            if (offset < 0 || size <= 0 || offset + size > length) return null
            val parcel = descriptor
            if (parcel != null) {
                val bytes = ByteArray(size)
                var filled = 0
                while (filled < size) {
                    val read = runCatching {
                        Os.pread(parcel.fileDescriptor, bytes, filled, size - filled, offset + filled)
                    }.getOrDefault(-1)
                    if (read <= 0) break
                    filled += read
                }
                return if (filled == size) bytes else bytes.copyOf(filled)
            }
            val handle = file ?: return null
            handle.seek(offset)
            val bytes = ByteArray(size)
            var filled = 0
            while (filled < size) {
                val read = handle.read(bytes, filled, size - filled)
                if (read <= 0) break
                filled += read
            }
            return if (filled == size) bytes else bytes.copyOf(filled)
        }

        override fun close() {
            runCatching { file?.close() }
            runCatching { descriptor?.close() }
        }
    }

    private class ImageStructure(
        val smdh: ByteArray?,
        val titleId: Long,
        val productCode: String?
    )

    fun read(context: Context, path: String): N3dsGameMetadata? = runCatching {
        val identity = cacheIdentity(context, path)
        metadataCache[identity]?.let { return@runCatching it }
        val metadata = openImage(context, path)?.use { image ->
            val structure = analyze(context, path, image)
            val smdh = structure.smdh ?: nativeSmdh(identity, path)
            val title = smdh?.let { decodeTitle(slice(it, TITLE_ENGLISH_OFFSET, TITLE_LENGTH)) }
            val serial = structure.titleId.takeIf { it != 0L }
                ?.let { String.format(Locale.US, "%016x", it) }
                ?: structure.productCode
            if (title == null && serial == null) null else N3dsGameMetadata(title, serial)
        }
        if (metadata != null && metadataCache.size < CACHE_LIMIT) {
            metadataCache[identity] = metadata
        }
        metadata
    }.getOrNull()

    fun extractIcon(context: Context, path: String): String? = runCatching {
        val directory = File(context.cacheDir, "game-covers/embedded-v3").apply { mkdirs() }
        val identity = cacheIdentity(context, path)
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = File(directory, "$hash.png")
        if (isImage(target)) return@runCatching target.absolutePath

        var smdh = openImage(context, path)?.use { image -> analyze(context, path, image).smdh }
        if (smdh == null) {
            smdh = nativeSmdh(identity, path)
        }
        if (smdh == null) return@runCatching null
        val raw = slice(smdh, LARGE_ICON_OFFSET, LARGE_ICON_SIZE * LARGE_ICON_SIZE * 2)
        if (raw.size < LARGE_ICON_SIZE * LARGE_ICON_SIZE * 2) return@runCatching null

        val bitmap = Bitmap.createBitmap(LARGE_ICON_SIZE, LARGE_ICON_SIZE, Bitmap.Config.ARGB_8888)
        val colors = IntArray(LARGE_ICON_SIZE * LARGE_ICON_SIZE)
        for (x in 0 until LARGE_ICON_SIZE) {
            val coarseX = x and 7.inv()
            for (y in 0 until LARGE_ICON_SIZE) {
                val coarseY = y and 7.inv()
                val morton = MORTON_X[x % 8] + MORTON_Y[y % 8] + coarseX * 8
                val index = morton * 2 + coarseY * LARGE_ICON_SIZE * 2
                val value = (raw[index].toInt() and 0xFF) or
                    ((raw[index + 1].toInt() and 0xFF) shl 8)
                val red = (value shr 11) and 0x1F
                val green = (value shr 5) and 0x3F
                val blue = value and 0x1F
                colors[y * LARGE_ICON_SIZE + x] = 0xFF000000.toInt() or
                    ((red shl 3 or (red shr 2)) shl 16) or
                    ((green shl 2 or (green shr 4)) shl 8) or
                    (blue shl 3 or (blue shr 2))
            }
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

    /**
     * Locates the SMDH block and the NCCH identity by walking the container
     * header chain: NCSD partition table -> NCCH header -> ExeFS "icon".
     */
    private fun analyze(context: Context, path: String, image: ImageHandle): ImageStructure {
        val extension = extensionOf(context, path)
        if (extension in ZIPPED_EXTENSIONS) {
            return ImageStructure(null, 0L, null)
        }
        when (extension) {
            "cia" -> return analyzeCia(image)
            "3dsx" -> return ImageStructure(findSmdhAtTail(image), 0L, null)
            "3ds", "cci" -> analyzeNcsd(image)?.let { return it }
            "cxi", "app" -> analyzeNcch(image, 0L)?.let { return it }
        }
        // Unknown container: fall back to the bounded magic scan.
        return ImageStructure(scanForSmdh(image), 0L, null)
    }

    /**
     * Reads the SMDH block through the core when the pure-Kotlin container
     * parse cannot: retail images keep the icon section encrypted and only
     * the core owns the decryption keys.
     */
    private fun nativeSmdh(identity: String, path: String): ByteArray? {
        smdhCache[identity]?.let { return it }
        if (!EmuCoreNative.isLoaded) return null
        // Local filesystem paths must reach the core with the "!" native-path
        // marker; without it the core resolves them against its user directory
        // and the icon read fails for installed titles.
        val nativePath = when {
            path.startsWith("content://") || path.startsWith("!") -> path
            path.startsWith("file://") -> runCatching { Uri.parse(path).path }
                .getOrNull()
                ?.let { "!$it" }
                ?: path
            else -> "!$path"
        }
        val smdh = runCatching { EmuCoreNative.getGameSmdh(nativePath) }.getOrNull() ?: return null
        if (smdh.size < 0x2A00) return null
        if (smdhCache.size < CACHE_LIMIT) smdhCache[identity] = smdh
        return smdh
    }

    private fun analyzeCia(image: ImageHandle): ImageStructure {
        val header = image.readAt(0L, CIA_HEADER_SIZE) ?: return ImageStructure(null, 0L, null)
        if (header.size < CIA_HEADER_SIZE) return ImageStructure(null, 0L, null)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buffer.getInt(0x00).toLong() and 0xFFFFFFFFL
        val certSize = buffer.getInt(0x08).toLong() and 0xFFFFFFFFL
        val ticketSize = buffer.getInt(0x0C).toLong() and 0xFFFFFFFFL
        val tmdSize = buffer.getInt(0x10).toLong() and 0xFFFFFFFFL
        val metaSize = buffer.getInt(0x14).toLong() and 0xFFFFFFFFL
        val contentSize = buffer.getLong(0x18)

        val certificateOffset = alignUp(headerSize, CIA_SECTION_ALIGNMENT)
        val ticketOffset = alignUp(certificateOffset + certSize, CIA_SECTION_ALIGNMENT)
        val tmdOffset = alignUp(ticketOffset + ticketSize, CIA_SECTION_ALIGNMENT)
        val contentOffset = alignUp(tmdOffset + tmdSize, CIA_SECTION_ALIGNMENT)
        val titleId = readCiaTitleId(image, tmdOffset, tmdSize) ?: 0L

        if (metaSize < CIA_META_SMDH_OFFSET + SMDH_SIZE) {
            return ImageStructure(null, titleId, null)
        }
        val metadataOffset = alignUp(contentOffset + contentSize, CIA_SECTION_ALIGNMENT)
        val smdhOffset = metadataOffset + CIA_META_SMDH_OFFSET
        return ImageStructure(readSmdhAt(image, smdhOffset), titleId, null)
    }

    /** The CIA title id lives in the TMD, after the variable-length signature. */
    private fun readCiaTitleId(image: ImageHandle, tmdOffset: Long, tmdSize: Long): Long? {
        if (tmdSize < 0xA4L) return null
        val size = minOf(tmdSize, 0x4000L).toInt()
        val tmd = image.readAt(tmdOffset, size) ?: return null
        val issuer = "Root-CA".toByteArray(Charsets.US_ASCII)
        var base = -1
        val limit = minOf(tmd.size - issuer.size, 0x300)
        for (index in 0..limit) {
            var matches = true
            for (offset in issuer.indices) {
                if (tmd[index + offset] != issuer[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                base = index
                break
            }
        }
        if (base < 0 || base + 0x54 > tmd.size) return null
        return ByteBuffer.wrap(tmd, base + 0x4C, 8).order(ByteOrder.BIG_ENDIAN).long
    }

    private fun analyzeNcsd(image: ImageHandle): ImageStructure? {
        val header = image.readAt(0L, NCSD_MAGIC_OFFSET + 4) ?: return null
        if (header.size < NCSD_MAGIC_OFFSET + 4) return null
        if (readMagic(header, NCSD_MAGIC_OFFSET) != NCSD_MAGIC) return null

        val table = image.readAt(NCSD_PARTITION_TABLE_OFFSET.toLong(), MAX_NCSD_PARTITIONS * 8) ?: return null
        val buffer = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until MAX_NCSD_PARTITIONS) {
            val offset = buffer.getInt(index * 8).toLong() and 0xFFFFFFFFL
            val size = buffer.getInt(index * 8 + 4).toLong() and 0xFFFFFFFFL
            if (offset == 0L || size == 0L) continue
            analyzeNcch(image, offset * MEDIA_UNIT)?.let { return it }
        }
        return null
    }

    private fun analyzeNcch(image: ImageHandle, ncchStart: Long): ImageStructure? {
        val header = image.readAt(ncchStart, NCCH_HEADER_SIZE) ?: return null
        if (header.size < NCCH_HEADER_SIZE) return null
        if (readMagic(header, NCCH_MAGIC_OFFSET) != NCCH_MAGIC) return null

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val titleId = buffer.getLong(NCCH_PROGRAM_ID_OFFSET)
        val productCode = decodeProductCode(
            slice(header, NCCH_PRODUCT_CODE_OFFSET, PRODUCT_CODE_LENGTH)
        )
        val exefsOffset = buffer.getInt(NCCH_EXEFS_OFFSET_OFFSET).toLong() and 0xFFFFFFFFL
        if (exefsOffset == 0L) return ImageStructure(null, titleId, productCode)

        val exefsStart = ncchStart + exefsOffset * MEDIA_UNIT
        val entries = image.readAt(exefsStart, EXEFS_ENTRY_COUNT * EXEFS_ENTRY_SIZE) ?: return ImageStructure(null, titleId, productCode)
        val entriesBuffer = ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until EXEFS_ENTRY_COUNT) {
            val base = index * EXEFS_ENTRY_SIZE
            val name = String(entries, base, 8, Charsets.US_ASCII).substringBefore('\u0000').trim()
            if (!name.equals("icon", ignoreCase = true)) continue
            val sectionOffset = entriesBuffer.getInt(base + 8).toLong() and 0xFFFFFFFFL
            val sectionSize = entriesBuffer.getInt(base + 12).toLong() and 0xFFFFFFFFL
            if (sectionSize < SMDH_SIZE.toLong()) continue
            val smdh = image.readAt(exefsStart + sectionOffset, SMDH_SIZE)
            return ImageStructure(smdh?.takeIf { readMagic(it, 0) == SMDH_MAGIC }, titleId, productCode)
        }
        return ImageStructure(null, titleId, productCode)
    }

    private fun findSmdhAtTail(image: ImageHandle): ByteArray? {
        val offset = image.length - SMDH_SIZE
        if (offset < 0) return null
        return readSmdhAt(image, offset)
    }

    private fun readSmdhAt(image: ImageHandle, offset: Long): ByteArray? {
        val smdh = image.readAt(offset, SMDH_SIZE) ?: return null
        return if (readMagic(smdh, 0) == SMDH_MAGIC) smdh else null
    }

    /** Bounded scan used only for structures without a parsed header chain. */
    private fun scanForSmdh(image: ImageHandle): ByteArray? {
        val length = image.length
        if (length < SMDH_SIZE) return null
        findMagic(image, 0L, minOf(length, SCAN_HEAD_BYTES))?.let { offset ->
            return readSmdhAt(image, offset)
        }
        if (length > SCAN_TAIL_BYTES) {
            findMagic(image, length - SCAN_TAIL_BYTES, length)?.let { offset ->
                return readSmdhAt(image, offset)
            }
        }
        return null
    }

    /** Returns the offset of the first "SMDH" magic inside [start, end). */
    private fun findMagic(image: ImageHandle, start: Long, end: Long): Long? {
        val buffer = ByteArray(CHUNK_BYTES + 3)
        var position = start
        var carry = 0
        while (position < end) {
            val toRead = minOf(CHUNK_BYTES.toLong(), end - position).toInt()
            val chunk = image.readAt(position, toRead) ?: return null
            if (chunk.isEmpty()) return null
            System.arraycopy(chunk, 0, buffer, carry, chunk.size)
            val total = carry + chunk.size
            var index = 0
            while (index + 3 < total) {
                if (readMagic(buffer, index) == SMDH_MAGIC) {
                    return position - carry + index
                }
                index++
            }
            if (total >= 3) {
                buffer[0] = buffer[total - 3]
                buffer[1] = buffer[total - 2]
                buffer[2] = buffer[total - 1]
                carry = 3
            }
            position += chunk.size
        }
        return null
    }

    private fun openImage(context: Context, path: String): ImageHandle? {
        if (path.startsWith("content://")) {
            val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                ?: return null
            val length = runCatching { descriptor.statSize }.getOrDefault(-1L)
            if (length <= 0L) {
                runCatching { descriptor.close() }
                return null
            }
            return ImageHandle(length, null, descriptor)
        }
        val localPath = if (path.startsWith("file://")) {
            runCatching { Uri.parse(path).path }.getOrNull() ?: path
        } else {
            path
        }
        val file = File(localPath)
        if (!file.isFile) return null
        return try {
            val handle = RandomAccessFile(file, "r")
            ImageHandle(handle.length(), handle, null)
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheIdentity(context: Context, path: String): String {
        if (path.startsWith("content://")) {
            val document = runCatching {
                DocumentFile.fromSingleUri(context, Uri.parse(path))
            }.getOrNull()
            return "$path:${document?.length() ?: -1L}:${document?.lastModified() ?: 0L}"
        }
        val localPath = if (path.startsWith("file://")) {
            runCatching { Uri.parse(path).path }.getOrNull() ?: path
        } else {
            path
        }
        val file = File(localPath)
        return "$path:${file.length()}:${file.lastModified()}"
    }

    private fun extensionOf(context: Context, path: String): String {
        val name = if (path.startsWith("content://")) {
            runCatching { DocumentPathResolver.getDisplayName(context, path) }.getOrNull().orEmpty()
        } else if (path.startsWith("file://")) {
            runCatching { Uri.parse(path).lastPathSegment }.getOrNull().orEmpty()
        } else {
            File(path).name
        }
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    }

    private fun alignUp(value: Long, alignment: Long): Long =
        (value + alignment - 1) / alignment * alignment

    private fun readMagic(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
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

    private const val NCSD_MAGIC = 0x4E435344 // "NCSD"
    private const val NCCH_MAGIC = 0x4E434348 // "NCCH"
    private val ZIPPED_EXTENSIONS = setOf("zcci", "zcxi", "z3dsx", "zcia", "zip")
}
