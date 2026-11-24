package com.sbro.emucored.nintendoui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object NintendoImageDecoder {
    private const val TAG = "NintendoImageDecoder"
    private const val MAX_DIM = 360

    @Volatile
    private var appContext: Context? = null
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newFixedThreadPool(2)

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    fun clearPending() {
        inFlight.clear()
    }

    @JvmStatic
    fun enqueueDecode(key: String, rawPath: String) {
        if (key.isBlank() || rawPath.isBlank()) {
            NintendoShellNative.nativeMarkImageFailed(key)
            return
        }
        if (!inFlight.add(key)) return

        executor.execute {
            try {
                val decoded = decodeRgba(rawPath)
                if (decoded != null) {
                    val (w, h, bytes) = decoded
                    NintendoShellNative.nativeUploadImage(key, w, h, bytes)
                } else {
                    NintendoShellNative.nativeMarkImageFailed(key)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed decoding image $key", t)
                NintendoShellNative.nativeMarkImageFailed(key)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private fun decodeRgba(rawPath: String): Triple<Int, Int, ByteArray>? {
        val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val openStream: () -> java.io.InputStream? = {
            if (rawPath.startsWith("content://")) {
                appContext?.contentResolver?.openInputStream(Uri.parse(rawPath))
            } else {
                val clean = rawPath.removePrefix("file://")
                val f = File(clean)
                if (f.isFile) f.inputStream() else null
            }
        }

        openStream()?.use { BitmapFactory.decodeStream(it, null, optsBounds) } ?: return null
        if (optsBounds.outWidth <= 0 || optsBounds.outHeight <= 0) return null

        var sample = 1
        while (optsBounds.outWidth / sample > MAX_DIM * 2 || optsBounds.outHeight / sample > MAX_DIM * 2) {
            sample *= 2
        }

        val optsDecode = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBmp = openStream()?.use { BitmapFactory.decodeStream(it, null, optsDecode) } ?: return null

        val maxSide = maxOf(rawBmp.width, rawBmp.height)
        val bmp = if (maxSide > MAX_DIM) {
            val scale = MAX_DIM.toFloat() / maxSide.toFloat()
            val tw = (rawBmp.width * scale).toInt().coerceAtLeast(1)
            val th = (rawBmp.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(rawBmp, tw, th, true).also {
                if (it !== rawBmp) rawBmp.recycle()
            }
        } else if (rawBmp.config != Bitmap.Config.ARGB_8888) {
            rawBmp.copy(Bitmap.Config.ARGB_8888, false).also { rawBmp.recycle() }
        } else {
            rawBmp
        }

        val buf = ByteBuffer.allocate(bmp.width * bmp.height * 4)
        bmp.copyPixelsToBuffer(buf)
        val w = bmp.width
        val h = bmp.height
        bmp.recycle()
        return Triple(w, h, buf.array())
    }
}
