package com.sbro.emucored.core

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Raised when a CIA cannot be prepared for the libretro core. */
class CiaExtractionException(
    message: String,
    @androidx.annotation.StringRes val localizedRes: Int? = null,
) : Exception(message)

/**
 * Prepares CIA containers for the libretro core.
 *
 * The core cannot install CIA files, so the boot content is extracted as a
 * plain NCCH image. Decryption (ticket AES-CBC plus NCCH secure crypto) runs
 * inside the vendored core, which owns the built-in keys and mirrors Azahar's
 * installer; this class only parses the container header, asks the core for the
 * boot content and caches the result next to the app data.
 */
object CiaExtractor {
    private const val TAG = "CiaExtractor"
    private const val CIA_HEADER_SIZE = 0x2020
    private const val SECTION_ALIGN = 0x40

    /** Reads the 16-digit title id from a CIA container without extracting it. */
    fun readTitleId(context: Context, path: String): String? = runCatching {
        openSource(context, path).use { descriptor ->
            val fileDescriptor = descriptor.fileDescriptor
            val header = readAt(fileDescriptor, 0, CIA_HEADER_SIZE) ?: return@use null
            if (u32(header, 0x00) != CIA_HEADER_SIZE.toLong()) return@use null
            val certSize = u32(header, 0x08)
            val ticketSize = u32(header, 0x0C)
            val tmdSize = u32(header, 0x10)
            if (tmdSize <= 0L || tmdSize > 16L * 1024 * 1024) return@use null
            val ticketOffset = align(align(CIA_HEADER_SIZE.toLong()) + certSize)
            val tmdOffset = align(ticketOffset + ticketSize)
            val tmd = readAt(fileDescriptor, tmdOffset, tmdSize.toInt()) ?: return@use null
            val base = findIssuer(tmd) ?: return@use null
            val titleId = u64be(tmd, base + 0x4C)
            titleId.takeIf { it != 0L }?.let { String.format(Locale.US, "%016x", it) }
        }
    }.getOrNull()

    fun ensureExtracted(context: Context, path: String): File {
        val outputDirectory = File(context.getExternalFilesDir(null) ?: context.filesDir, "cia")
            .apply { mkdirs() }

        openSource(context, path).use { descriptor ->
            val rawFd = descriptor.fd
            val fileDescriptor = descriptor.fileDescriptor
            val header = readAt(fileDescriptor, 0, CIA_HEADER_SIZE)
                ?: throw CiaExtractionException("The selected file is not a valid CIA container")
            if (u32(header, 0x00) != CIA_HEADER_SIZE.toLong()) {
                throw CiaExtractionException("The selected file is not a CIA container")
            }
            val certSize = u32(header, 0x08)
            val ticketSize = u32(header, 0x0C)
            val tmdSize = u32(header, 0x10)
            if (tmdSize <= 0L || tmdSize > 16L * 1024 * 1024) {
                throw CiaExtractionException("The CIA title metadata looks corrupt")
            }
            val ticketOffset = align(align(CIA_HEADER_SIZE.toLong()) + certSize)
            val tmdOffset = align(ticketOffset + ticketSize)
            val tmd = readAt(fileDescriptor, tmdOffset, tmdSize.toInt())
                ?: throw CiaExtractionException("The CIA title metadata is truncated")
            val base = findIssuer(tmd)
                ?: throw CiaExtractionException("The CIA title metadata is unreadable")
            val bootIndex = u16be(tmd, base + 0xA0)
            val titleId = u64be(tmd, base + 0x4C)

            val output = File(
                outputDirectory,
                if (titleId != 0L) String.format(Locale.US, "%016x.cxi", titleId)
                else "content_${tmdOffset.toString(16)}.cxi"
            )
            val marker = File(outputDirectory, output.name + ".source")
            val signature = sourceSignature(context, path)
            if (output.isFile && output.length() > 0x200 && marker.isFile &&
                marker.readText() == signature
            ) {
                return output
            }
            output.delete()
            marker.delete()

            val result = NativeCoreBridge().extractCiaContent(rawFd, output.absolutePath, bootIndex)
            when (result) {
                0 -> {
                    marker.writeText(signature)
                    Log.i(TAG, "Prepared CIA boot content at ${output.absolutePath} (${output.length()} bytes)")
                    return output
                }
                -2, -3 -> throw CiaExtractionException(
                    "This CIA is encrypted and the required keys are unavailable",
                    com.sbro.emucored.R.string.cia_decrypt_required
                )
                else -> throw CiaExtractionException(
                    "The CIA content could not be prepared (code $result)",
                    com.sbro.emucored.R.string.cia_unsupported
                )
            }
        }
    }

    private fun sourceSignature(context: Context, path: String): String {
        if (path.startsWith("content://")) {
            val document = DocumentFile.fromSingleUri(context, Uri.parse(path))
            return "$path:${document?.length()}:${document?.lastModified()}"
        }
        val local = uriToPath(path) ?: path
        val file = File(local)
        return "$path:${file.length()}:${file.lastModified()}"
    }

    private fun openSource(context: Context, path: String): ParcelFileDescriptor {
        if (path.startsWith("content://")) {
            return context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                ?: throw CiaExtractionException("Cannot open the selected CIA file")
        }
        val local = uriToPath(path) ?: path
        val file = File(local)
        if (!file.isFile) throw CiaExtractionException("The CIA file does not exist")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun uriToPath(path: String): String? =
        if (path.startsWith("file://")) runCatching { Uri.parse(path).path }.getOrNull() else null

    private fun readAt(fd: FileDescriptor, offset: Long, size: Int): ByteArray? {
        if (size <= 0) return null
        val bytes = ByteArray(size)
        var filled = 0
        while (filled < size) {
            val read = runCatching {
                Os.pread(fd, bytes, filled, size - filled, offset + filled)
            }.getOrElse { return null }
            if (read <= 0) return null
            filled += read
        }
        return bytes
    }

    private fun findIssuer(tmd: ByteArray): Int? {
        val needle = "Root-CA".toByteArray(Charsets.US_ASCII)
        val limit = minOf(tmd.size - needle.size, 0x300)
        for (start in 0..limit) {
            var matches = true
            for (i in needle.indices) {
                if (tmd[start + i] != needle[i]) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return null
    }

    private fun align(value: Long): Long =
        (value + SECTION_ALIGN - 1) / SECTION_ALIGN * SECTION_ALIGN

    private fun u16be(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun u64be(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.BIG_ENDIAN).long
}
