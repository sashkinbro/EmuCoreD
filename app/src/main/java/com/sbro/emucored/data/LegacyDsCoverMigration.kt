package com.sbro.emucored.data

import java.io.File
import java.security.MessageDigest

/** The unnamespaced cache may contain either platform; filenames alone prove no identity. */
internal fun isVerifiedLegacyDsCover(file: File, serial: String?, indexedSha256: String?): Boolean {
    if (serial.isNullOrBlank() || indexedSha256?.matches(Regex("[a-fA-F0-9]{64}")) != true) return false
    if (!file.isFile) return false
    return runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }.equals(indexedSha256, ignoreCase = true)
    }.getOrDefault(false)
}
