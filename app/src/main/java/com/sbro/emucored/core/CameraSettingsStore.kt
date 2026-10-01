package com.sbro.emucored.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Per-camera configuration for the 3DS cameras. The emulator feeds a
 * user-selected still image to the core; the image is decoded on the Kotlin
 * side and handed over as RGB565 pixels.
 */
object CameraSettingsStore {
    const val SLOT_OUTER_RIGHT = 0
    const val SLOT_INNER = 1
    const val SLOT_OUTER_LEFT = 2

    const val FLIP_NONE = 0
    const val FLIP_HORIZONTAL = 1
    const val FLIP_VERTICAL = 2
    const val FLIP_REVERSE = 3

    const val FRAME_WIDTH = 640
    const val FRAME_HEIGHT = 480

    data class CameraConfig(
        val enabled: Boolean = false,
        val imagePath: String? = null,
        val flip: Int = FLIP_NONE,
    )

    private const val PREFS_NAME = "camera_settings"

    fun config(context: Context, slot: Int): CameraConfig {
        val prefs = prefs(context)
        return CameraConfig(
            enabled = prefs.getBoolean("slot$slot.enabled", false),
            imagePath = prefs.getString("slot$slot.path", null),
            flip = prefs.getInt("slot$slot.flip", FLIP_NONE).coerceIn(FLIP_NONE, FLIP_REVERSE),
        )
    }

    fun setConfig(context: Context, slot: Int, config: CameraConfig) {
        prefs(context).edit()
            .putBoolean("slot$slot.enabled", config.enabled)
            .putString("slot$slot.path", config.imagePath)
            .putInt("slot$slot.flip", config.flip.coerceIn(FLIP_NONE, FLIP_REVERSE))
            .apply()
    }

    /** Pushes the configured cameras (image pixels and enable state) to the core. */
    fun applyToCore(context: Context, bridge: NativeCoreBridge) {
        for (slot in SLOT_OUTER_RIGHT..SLOT_OUTER_LEFT) {
            val config = config(context, slot)
            val bitmap = config.imagePath
                ?.takeIf(String::isNotBlank)
                ?.let { decode(context, it) }
            if (config.enabled && bitmap != null) {
                bridge.nativeSetCameraFrame(slot, FRAME_WIDTH, FRAME_HEIGHT, toRgb565(bitmap))
                bridge.nativeSetCameraConfig(slot, true, config.flip)
            } else {
                bridge.nativeSetCameraConfig(slot, false, FLIP_NONE)
            }
        }
    }

    private fun decode(context: Context, path: String): Bitmap? = runCatching {
        val bitmap = if (path.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(path))
                ?.use(BitmapFactory::decodeStream)
        } else {
            BitmapFactory.decodeFile(path)
        }
        bitmap?.let { Bitmap.createScaledBitmap(it, FRAME_WIDTH, FRAME_HEIGHT, true) }
    }.getOrNull()

    private fun toRgb565(bitmap: Bitmap): ShortArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ShortArray(pixels.size) { index ->
            val color = pixels[index]
            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            val blue = color and 0xFF
            (((red shr 3) shl 11) or ((green shr 2) shl 5) or (blue shr 3)).toShort()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
