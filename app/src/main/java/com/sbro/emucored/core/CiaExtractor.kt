package com.sbro.emucored.core

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale

/** Raised when a CIA cannot be prepared for the libretro core. */
class CiaExtractionException(
    message: String,
    @androidx.annotation.StringRes val localizedRes: Int? = null,
) : Exception(message)

/**
 * The bundled Azahar libretro core loads NCCH images but not CIA installers, so
 * this extractor copies the game's NCCH partition out of a decrypted CIA and
 * hands the core a regular .cxi path.
 *
 * A CIA stores several contents (the game, its manual, sometimes Download
 * Play); only the NCCH headers are needed to tell them apart, so the extractor
 * scans the content area for NCCH magics and picks the largest executable
 * partition. Encrypted retail CIAs cannot be decrypted by the app: the core
 * reports the usual "must be decrypted" error for those.
 */
object CiaExtractor {
    private const val TAG = "CiaExtractor"
    private const val CIA_HEADER_SIZE = 0x2020
    private const val SECTION_ALIGN = 0x40
    private const val COPY_BUFFER_BYTES = 1 shl 20
    private const val NCCH_HEADER_SIZE = 0x200
    private const val SCAN_LIMIT_BYTES = 128L * 1024 * 1024

    private data class Candidate(
        val offset: Long,
        val size: Long,
        val programId: Long,
        val noCrypto: Boolean,
        val exefsOffset: Long,
        val exefsSize: Long,
        val romfsOffset: Long,
        val romfsSize: Long,
    ) {
        /** A plausible executable NCCH header rather than encrypted noise. */
        val looksLikeExecutable: Boolean
            get() = (programId ushr 48) == 0x0004L &&
                size >= 0x100000 &&
                exefsSize > 0 &&
                exefsOffset + exefsSize <= size &&
                (romfsSize == 0L || romfsOffset + romfsSize <= size)
    }

    private class Source(val channel: FileChannel, private val owner: Closeable?) : Closeable {
        fun size(): Long = channel.size()
        fun read(position: Long, length: Int): ByteArray {
            val buffer = ByteBuffer.allocate(length)
            channel.position(position)
            var read = 0
            while (read < length) {
                val result = channel.read(buffer)
                if (result <= 0) break
                read += result
            }
            return buffer.array().copyOf(read)
        }
        override fun close() {
            try {
                channel.close()
            } finally {
                owner?.close()
            }
        }
    }

    fun ensureExtracted(context: Context, path: String): File {
        openSource(context, path).use { source ->
            val fileSize = source.size()
            if (fileSize < CIA_HEADER_SIZE + NCCH_HEADER_SIZE) {
                throw CiaExtractionException("The selected file is not a valid CIA container")
            }
            val header = source.read(0, CIA_HEADER_SIZE)
            if (u32(header, 0x00) != CIA_HEADER_SIZE.toLong()) {
                throw CiaExtractionException("The selected file is not a CIA container")
            }
            val certSize = u32(header, 0x08)
            val ticketSize = u32(header, 0x0C)
            val tmdSize = u32(header, 0x10)
            val contentSize = u64(header, 0x18)
            if (contentSize <= 0L || tmdSize <= 0L) {
                throw CiaExtractionException("The CIA header looks corrupt")
            }
            var offset = align(CIA_HEADER_SIZE.toLong())
            offset = align(offset + certSize)
            offset = align(offset + ticketSize)
            offset = align(offset + tmdSize)

            val candidates = scanCandidates(source, offset, minOf(fileSize, offset + SCAN_LIMIT_BYTES))
            val boot = candidates
                .filter { it.looksLikeExecutable && it.size <= contentSize }
                .maxByOrNull { it.size }
            // An encrypted CIA hides its main NCCH header, leaving only small
            // readable side contents (manuals). Those are never close in size
            // to the whole content area, so a tiny best candidate means the
            // game itself is encrypted.
            if (boot == null || boot.size < contentSize / 4) {
                throw CiaExtractionException(
                    "This CIA is encrypted or compressed. Azahar only loads decrypted CIA files.",
                    com.sbro.emucored.R.string.cia_decrypt_required
                )
            }
            if (!boot.noCrypto) {
                Log.w(TAG, "CIA boot content may be encrypted; the core will decide whether it can load it")
            }

            val outputDirectory = File(context.getExternalFilesDir(null) ?: context.filesDir, "cia")
                .apply { mkdirs() }
            val name = if (boot.programId != 0L && (boot.programId ushr 48) == 0x0004L) {
                String.format(Locale.US, "%016x.cxi", boot.programId)
            } else {
                "content_${boot.offset.toString(16)}.cxi"
            }
            val output = File(outputDirectory, name)
            if (output.isFile && output.length() == boot.size) return output

            val temporary = File(outputDirectory, output.name + ".tmp")
            try {
                temporary.outputStream().use { target ->
                    source.channel.position(boot.offset)
                    var remaining = boot.size
                    val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
                    while (remaining > 0) {
                        buffer.clear()
                        buffer.limit(minOf(remaining, COPY_BUFFER_BYTES.toLong()).toInt())
                        val read = source.channel.read(buffer)
                        if (read <= 0) throw CiaExtractionException("Unexpected end of the CIA content")
                        target.write(buffer.array(), 0, read)
                        remaining -= read
                    }
                }
                if (!temporary.renameTo(output)) {
                    temporary.copyTo(output, overwrite = true)
                    temporary.delete()
                }
            } catch (error: Throwable) {
                temporary.delete()
                throw error
            }
            Log.i(
                TAG,
                "Extracted CIA boot content to ${output.absolutePath} " +
                    "(program=${String.format(Locale.US, "%016x", boot.programId)}, ${output.length()} bytes)"
            )
            return output
        }
    }

    /** Finds every NCCH header in the content area and reads its basic fields. */
    private fun scanCandidates(source: Source, start: Long, end: Long): List<Candidate> {
        val candidates = ArrayList<Candidate>()
        val chunkSize = COPY_BUFFER_BYTES
        var position = start
        var carry = 0
        val buffer = ByteArray(chunkSize + 3)
        while (position < end) {
            val bytes = source.read(position, chunkSize)
            if (bytes.isEmpty()) break
            System.arraycopy(bytes, 0, buffer, carry, bytes.size)
            val total = carry + bytes.size
            var index = 0
            while (index + NCCH_HEADER_SIZE < total) {
                if (isNcch(buffer, index)) {
                    val absolute = position - carry + index
                    val header = source.read(absolute, NCCH_HEADER_SIZE)
                    if (header.size >= NCCH_HEADER_SIZE) {
                        val size = u32(header, 0x04)
                        val programId = u64(header, 0x18)
                        val flags = header[0x8F].toInt() and 0xFF
                        candidates.add(
                            Candidate(
                                offset = absolute,
                                size = size,
                                programId = programId,
                                noCrypto = (flags and 0x04) != 0,
                                exefsOffset = u32(header, 0xA0),
                                exefsSize = u32(header, 0xA4),
                                romfsOffset = u32(header, 0xB0),
                                romfsSize = u32(header, 0xB4),
                            )
                        )
                    }
                    index += NCCH_HEADER_SIZE
                    continue
                }
                index++
            }
            if (total >= 3) {
                buffer[0] = buffer[total - 3]
                buffer[1] = buffer[total - 2]
                buffer[2] = buffer[total - 1]
                carry = 3
            }
            position += bytes.size
        }
        return candidates
    }

    private fun isNcch(bytes: ByteArray, offset: Int): Boolean =
        bytes[offset] == 'N'.code.toByte() &&
            bytes[offset + 1] == 'C'.code.toByte() &&
            bytes[offset + 2] == 'C'.code.toByte() &&
            bytes[offset + 3] == 'H'.code.toByte()

    private fun openSource(context: Context, path: String): Source {
        if (path.startsWith("content://")) {
            val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                ?: throw CiaExtractionException("Cannot open the selected CIA file")
            val stream = object : FileInputStream(descriptor.fileDescriptor) {}
            return Source(stream.channel, Closeable { descriptor.close() })
        }
        val local = if (path.startsWith("file://")) {
            runCatching { Uri.parse(path).path }.getOrNull() ?: path
        } else {
            path
        }
        val file = File(local)
        if (!file.isFile) throw CiaExtractionException("The CIA file does not exist")
        return Source(FileInputStream(file).channel, null)
    }

    private fun align(value: Long): Long =
        (value + SECTION_ALIGN - 1) / SECTION_ALIGN * SECTION_ALIGN

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun u64(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long
}
