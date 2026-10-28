package com.sbro.emucored.core

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class RemoteGpuDriver(
    val id: String,
    val name: String,
    val variant: String,
    val gpu: String,
    val description: String,
    val recommended: Boolean,
    val downloadUrl: String,
    val sourceUrl: String,
    val credits: String,
    val sizeBytes: Long?
)

class GpuDriverCatalogRepository(private val context: Context) {

    fun loadCatalog(): List<RemoteGpuDriver> {
        var lastFailure: Throwable? = null
        for (catalogUrl in CATALOG_URLS) {
            val result = runCatching {
                val connection = openConnection(catalogUrl, "application/json,text/plain,*/*")
                try {
                    ensureSuccess(connection, "driver catalog")
                    connection.inputStream.bufferedReader().use { reader ->
                        parseCatalog(reader.readText())
                    }
                } finally {
                    connection.disconnect()
                }
            }
            result.onSuccess { return it }
            lastFailure = result.exceptionOrNull()
        }
        throw IOException("Driver catalog unavailable", lastFailure)
    }

    fun downloadDriver(driver: RemoteGpuDriver, onProgress: (Float) -> Unit): File {
        val target = File(context.cacheDir, "gpu-drivers/${driver.safeArchiveName()}")
        target.parentFile?.mkdirs()
        if (target.exists()) {
            target.delete()
        }

        val connection = openConnection(driver.downloadUrl, "application/zip,application/octet-stream,*/*").apply {
            readTimeout = 60_000
        }
        try {
            ensureSuccess(connection, "driver archive")
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: driver.sizeBytes ?: -1L
            if (total > MAX_ARCHIVE_BYTES) throw IOException("Driver archive exceeds the size limit")
            var copied = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        copied += read
                        if (copied > MAX_ARCHIVE_BYTES) throw IOException("Driver archive exceeds the size limit")
                        output.write(buffer, 0, read)
                        if (total > 0L) {
                            onProgress((copied.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
        // A truncated or empty archive would install a corrupt .so that later
        // crashes natively inside the third-party driver. Fail here instead.
        if (!target.isFile || target.length() <= 0L) {
            target.delete()
            throw IOException("Driver archive download is empty")
        }
        if (driver.sizeBytes != null && target.length() != driver.sizeBytes) {
            target.delete()
            throw IOException(
                "Driver archive size mismatch (got ${target.length()}, expected ${driver.sizeBytes}); please retry"
            )
        }
        onProgress(1f)
        return target
    }

    private fun parseCatalog(json: String): List<RemoteGpuDriver> {
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val downloadUrl = item.optString("downloadUrl")
                val id = item.optString("id")
                if (id.isBlank() || downloadUrl.isBlank()) continue
                add(
                    RemoteGpuDriver(
                        id = id,
                        name = item.optString("name", id),
                        variant = item.optString("variant"),
                        gpu = item.optString("gpu", "Adreno"),
                        description = item.optString("description"),
                        recommended = item.optBoolean("recommended", false),
                        downloadUrl = downloadUrl,
                        sourceUrl = item.optString("sourceUrl"),
                        credits = item.optString("credits"),
                        sizeBytes = item.optLong("sizeBytes").takeIf { it > 0L }
                    )
                )
            }
        }
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true)) { "Driver downloads require HTTPS" }
        return (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "EmuCoreD/${context.packageName}")
        }
    }

    private fun ensureSuccess(connection: HttpURLConnection, label: String) {
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val responseMessage = connection.responseMessage.orEmpty().ifBlank { "HTTP $responseCode" }
            throw IOException("Could not load $label: $responseMessage")
        }
    }

    companion object {
        private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        val CATALOG_URLS = listOf(
            "https://raw.githubusercontent.com/sashkinbro/EmuCoreV-Drivers/main/drivers.json",
            "https://github.com/sashkinbro/EmuCoreV-Drivers/raw/main/drivers.json",
            "https://cdn.jsdelivr.net/gh/sashkinbro/EmuCoreV-Drivers@main/drivers.json"
        )
    }
}

/** Catalog IDs distinguish releases that publish archives with identical filenames. */
internal fun RemoteGpuDriver.safeArchiveName(): String {
    val rawName = downloadUrl.substringBefore('?').substringAfterLast('/').ifBlank { "driver.zip" }
    val basename = rawName.removeSuffix(".zip").removeSuffix(".ZIP")
        .replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').take(80).ifBlank { "driver" }
    val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "$basename-$digest.zip"
}
