package com.sbro.emucored.nintendoui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object NintendoImageDecoder {
    private const val TAG = "NintendoImageDecoder"

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
    fun enqueueDecode(path: String, maxDim: Int) {
        if (path.isBlank()) return
        if (!inFlight.add(path)) return
        val clampedMax = maxDim.coerceIn(64, 512)

        executor.execute {
            try {
                val decoded = decodeRgba(path, clampedMax)
                if (decoded != null) {
                    val (w, h, bytes) = decoded
                    NintendoShellNative.nativeNotifyImageDecoded(path, w, h, bytes)
                } else {
                    NintendoShellNative.nativeNotifyImageDecoded(path, 0, 0, null)
                }
                NintendoShellHost.onRequestRender()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed decoding image $path", t)
                NintendoShellNative.nativeNotifyImageDecoded(path, 0, 0, null)
            } finally {
                inFlight.remove(path)
            }
        }
    }

    private fun decodeRgba(rawPath: String, maxDim: Int): Triple<Int, Int, ByteArray>? {
        val localFile: File? = when {
            rawPath.startsWith("http://") || rawPath.startsWith("https://") -> resolveRemoteImage(rawPath)
            else -> null
        }
        val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val openStream: () -> InputStream? = {
            when {
                localFile != null -> if (localFile.isFile) localFile.inputStream() else null
                rawPath.startsWith("content://") ->
                    appContext?.contentResolver?.openInputStream(Uri.parse(rawPath))
                rawPath.startsWith("http://") || rawPath.startsWith("https://") -> null
                else -> {
                    val clean = rawPath.removePrefix("file://")
                    val f = File(clean)
                    if (f.isFile) f.inputStream() else null
                }
            }
        }

        val boundsStream = openStream() ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, optsBounds) }
        if (optsBounds.outWidth <= 0 || optsBounds.outHeight <= 0) return null

        var sample = 1
        while (optsBounds.outWidth / sample > maxDim * 2 || optsBounds.outHeight / sample > maxDim * 2) {
            sample *= 2
        }

        val optsDecode = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBmp = openStream()?.use { BitmapFactory.decodeStream(it, null, optsDecode) } ?: return null

        val maxSide = maxOf(rawBmp.width, rawBmp.height)
        val bmp = if (maxSide > maxDim) {
            val scale = maxDim.toFloat() / maxSide.toFloat()
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

    private fun resolveRemoteImage(urlString: String): File? {
        val ctx = appContext ?: return null
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(urlString.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val ext = urlString.substringAfterLast('.', "").substringBefore('?').lowercase()
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "img"

        // Check app's shared remote-image-cache first so cached covers load offline instantaneously.
        val sharedCacheDir = File(ctx.cacheDir, "remote-image-cache")
        val existingShared = File(sharedCacheDir, "$hash.$ext")
        if (existingShared.isFile && existingShared.length() > 0L) return existingShared

        val shellCacheDir = File(ctx.cacheDir, "nintendo-cover-cache").apply { mkdirs() }
        val target = File(shellCacheDir, "$hash.$ext")
        if (target.isFile && target.length() > 0L) return target
        val miss = File(shellCacheDir, "$hash.miss")
        if (miss.isFile && System.currentTimeMillis() - miss.lastModified() < 300_000L) return null

        val temp = File(shellCacheDir, "$hash.tmp")
        val ok = runCatching {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 8_000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode !in 200..299) {
                    if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) miss.writeText("404")
                    return@runCatching false
                }
                conn.inputStream.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                true
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)

        if (!ok || !temp.isFile || temp.length() <= 0L) {
            temp.delete()
            return null
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
        temp.delete()
        return target.takeIf { it.isFile && it.length() > 0L }
    }
}
