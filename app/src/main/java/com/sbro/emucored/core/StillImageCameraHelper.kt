// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.Keep

/**
 * Still-image camera source for the emulated 3DS cameras. The core asks the
 * frontend for an image whenever a game captures a frame.
 */
@Keep
object StillImageCameraHelper {
    private var imageProvider: (() -> Uri?)? = null

    /** Sets the picker used when a game asks for a still image. */
    fun setImageProvider(provider: () -> Uri?) {
        imageProvider = provider
    }

    /** Opens the picker and returns the selected image path, or null. */
    @JvmStatic
    fun openFilePicker(): String? = imageProvider?.invoke()?.toString()

    /** Decodes [path] into a bitmap of the requested camera resolution. */
    @JvmStatic
    fun loadImageFromFile(path: String, width: Int, height: Int): Bitmap? {
        val file = runCatching { java.io.File(path) }.getOrNull() ?: return null
        if (!file.isFile) return null
        return runCatching {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                ?.let { decoded ->
                    Bitmap.createScaledBitmap(decoded, width, height, true)
                }
        }.getOrNull()
    }
}
