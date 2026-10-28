package com.sbro.emucored.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import org.junit.Assert.*
import org.junit.Test

class GpuDriverLibraryValidationTest {
    @Test fun rejectsTruncatedAndWrongArchitectureLibraries() {
        val dir = createTempDirectory("driver-elf-").toFile()
        try {
            val file = dir.resolve("libvulkan.so")
            file.writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
            assertFalse(isValidDriverLibrary(file))
            val header = ByteArray(120)
            header[0] = 0x7f; header[1] = 0x45; header[2] = 0x4c; header[3] = 0x46
            header[4] = 2; header[5] = 1; header[6] = 1
            header[16] = 3; header[18] = 62
            ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
                putLong(32, 64); putShort(52, 64); putShort(54, 56); putShort(56, 1)
            }
            file.writeBytes(header)
            assertFalse(isValidDriverLibrary(file))
            header[18] = 0xb7.toByte()
            file.writeBytes(header)
            assertTrue(isValidDriverLibrary(file))
        } finally { dir.deleteRecursively() }
    }
}
