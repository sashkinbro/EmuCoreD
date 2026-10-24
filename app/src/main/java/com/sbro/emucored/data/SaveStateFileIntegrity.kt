package com.sbro.emucored.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Offline framing/integrity checks; the engine still verifies its ROM and internal state on load. */
object SaveStateFileIntegrity {
    const val MAX_STATE_BYTES = 1_073_741_824L
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun azaharTitleId(file: File): String? = runCatching {
        file.inputStream().use { input ->
            val bytes = ByteArray(12)
            require(input.read(bytes) == bytes.size && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(67, 83, 84, 27)))
            "%016X".format(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(4))
        }
    }.getOrNull()

    fun validate(file: File, platform: GamePlatform): Boolean = runCatching {
        require(file.isFile && file.length() in 17..MAX_STATE_BYTES)
        file.inputStream().buffered().use { input ->
            val header = ByteArray(minOf(file.length(), 256).toInt())
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                require(read > 0)
                offset += read
            }
            val bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            when (platform) {
                GamePlatform.N3DS -> {
                    require(header.size == 256 && header.copyOfRange(0, 4).contentEquals(byteArrayOf(67, 83, 84, 27)))
                    require(file.length() > 260)
                    // Standard Zstandard frame magic, after Azahar's fixed CST header.
                    require(ByteArray(4) { input.read().also { require(it >= 0) }.toByte() }
                        .contentEquals(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())))
                }
                GamePlatform.NDS -> {
                    require(header.copyOfRange(0, 4).contentEquals("MELN".toByteArray()))
                    require(bytes.getShort(4).toInt() == 13)
                    require((bytes.getInt(8).toLong() and 0xFFFFFFFFL) == file.length())
                }
                GamePlatform.GAMECUBE, GamePlatform.WII -> {
                    require(header.size >= 48 && header.take(6).all { it.toInt() in 32..126 })
                    require(bytes.getInt(8) == 0)
                    require((bytes.getInt(24).toLong() and 0xFFFFFFFFL) == 0xBAADBABEL + 192)
                    val versionLength = bytes.getInt(28)
                    require(versionLength in 1..1024 && file.length() > 48L + versionLength)
                    // Extended header follows a variable-length build string.
                    file.inputStream().use { extended ->
                        var remaining = 32L + versionLength
                        while (remaining > 0) { val skipped = extended.skip(remaining); require(skipped > 0); remaining -= skipped }
                        val fields = ByteArray(16)
                        var read = 0
                        while (read < fields.size) { val n = extended.read(fields, read, fields.size - read); require(n > 0); read += n }
                        val e = ByteBuffer.wrap(fields).order(ByteOrder.LITTLE_ENDIAN)
                        require(e.short.toInt() == 1 && e.short.toInt() in 0..1)
                        require(e.int == 0 && e.long in 1..MAX_STATE_BYTES)
                    }
                }
                else -> {
                    require(header.size >= 32 && bytes.long == 0x3145544154534345L)
                    bytes.long // Native ROM identity. Native load compares it to the actual loaded ROM.
                    val expectedChecksum = bytes.long
                    val size = bytes.long
                    require(size in 1..67_108_864L && file.length() == size + 32)
                    var checksum = 1469598103934665603L
                    file.inputStream().buffered().use { payload ->
                        require(payload.skip(32) == 32L)
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val n = payload.read(buffer)
                            if (n < 0) break
                            for (i in 0 until n) checksum = (checksum xor (buffer[i].toLong() and 255)) * 1099511628211L
                        }
                    }
                    require(checksum == expectedChecksum)
                }
            }
        }
        true
    }.getOrDefault(false)
}
