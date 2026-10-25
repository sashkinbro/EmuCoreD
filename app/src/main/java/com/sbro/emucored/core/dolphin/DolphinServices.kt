// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core.dolphin

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.annotation.Keep
import androidx.documentfile.provider.DocumentFile
import java.io.File

/** Services used by Dolphin's Android audio backend, without upstream UI classes. */
@Keep
internal object DolphinServices {
    @Volatile private var outputRate = 48000
    @Volatile private var outputFrames = 256
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun configure(context: Context) {
        initialize(context)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        outputRate = audio?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()?.takeIf { it in 8000..192000 } ?: 48000
        outputFrames = audio?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()?.takeIf { it in 16..8192 } ?: 256
    }

    @JvmStatic fun sampleRate(): Int = outputRate
    @JvmStatic fun framesPerBuffer(): Int = outputFrames
    // Microphone peripherals have no frontend permission workflow yet. Playback
    // audio remains available; do not trigger unsolicited permission dialogs.
    @JvmStatic fun hasMicrophonePermission(): Boolean = false
    @JvmStatic fun requestMicrophonePermission() = Unit

    /**
     * Dolphin's native file layer asks for a seekable descriptor for a SAF
     * document. Keep the URI grant intact; resolving to a raw /storage path
     * loses it on scoped storage.
     */
    @JvmStatic fun openContentFd(uri: String, mode: String): Int = runCatching {
        val context = appContext ?: return -1
        val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(uri), mode)
            ?: return -1
        descriptor.detachFd()
    }.getOrDefault(-1)

    /** -2 directory, -1 missing, otherwise the document size in bytes. */
    @JvmStatic fun contentSizeAndIsDirectory(uri: String): Long = runCatching {
        val context = appContext ?: return -1L
        val parsed = Uri.parse(uri)
        context.contentResolver.query(
            parsed,
            arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (mimeIndex >= 0 &&
                    cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR
                ) return -2L
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    return cursor.getLong(sizeIndex).coerceAtLeast(0L)
                }
                return 0L
            }
        }
        val document = DocumentFile.fromSingleUri(context, parsed) ?: return -1L
        when {
            document.isDirectory -> -2L
            !document.exists() -> -1L
            else -> document.length().coerceAtLeast(0L)
        }
    }.getOrDefault(-1L)

    @JvmStatic fun contentDisplayName(uri: String): String? = runCatching {
        val context = appContext ?: return null
        val parsed = Uri.parse(uri)
        context.contentResolver.query(
            parsed,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        DocumentFile.fromSingleUri(context, parsed)?.name?.takeIf { it.isNotBlank() }
    }.getOrNull()

    @JvmStatic fun deleteContent(uri: String): Boolean = runCatching {
        val context = appContext ?: return false
        DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uri))
    }.getOrDefault(false)

    fun isReadableGamePath(path: String): Boolean = when {
        path.startsWith("content://") -> runCatching {
            val context = appContext ?: return false
            context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { true } ?: false
        }.getOrDefault(false)
        path.startsWith("file://") -> File(Uri.parse(path).path.orEmpty()).isFile
        else -> File(path.removePrefix("!")).isFile
    }
}
