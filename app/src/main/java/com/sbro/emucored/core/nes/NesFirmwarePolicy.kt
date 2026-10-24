package com.sbro.emucored.core.nes

import java.io.File

/** Header evidence also works for a held SAF descriptor (/proc/self/fd/N). */
internal object NesFirmwarePolicy {
    fun failureReason(launchPath: String, userDirectory: File): String? {
        val header = runCatching {
            File(launchPath).inputStream().use { input ->
                val bytes = ByteArray(4)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                bytes.takeIf { count == bytes.size }
            }
        }.getOrNull() ?: return null
        val fds = header.contentEquals(byteArrayOf(0x46, 0x44, 0x53, 0x1a)) ||
            header.contentEquals(byteArrayOf(1, 0x2a, 0x4e, 0x49))
        if (!fds) return null
        val firmware = File(userDirectory, "firmware/disksys.rom")
        if (!firmware.exists()) return "firmware_missing_disksys_rom"
        if (!firmware.isFile || !firmware.canRead() || firmware.length() != 8192L)
            return "firmware_invalid_disksys_rom"
        return null
    }
}
