package com.sbro.emucored.core

import com.sbro.emucored.core.nes.NesFirmwarePolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NesFirmwarePolicyTest {
    @Test fun actualFdsHeadersRequireBiosRegardlessOfExtensionAndRejectInvalidFirmware() {
        val directory = Files.createTempDirectory("nes-firmware-test").toFile()
        try {
            val image = File(directory, "image.nes")
            val firmware = File(directory, "firmware/disksys.rom")
            image.writeBytes(byteArrayOf(0x46, 0x44, 0x53, 0x1a, 2))
            assertEquals("firmware_missing_disksys_rom", NesFirmwarePolicy.failureReason(image.path, directory))
            firmware.parentFile!!.mkdirs()
            firmware.writeBytes(ByteArray(7))
            assertEquals("firmware_invalid_disksys_rom", NesFirmwarePolicy.failureReason(image.path, directory))
            firmware.writeBytes(ByteArray(8192))
            assertNull(NesFirmwarePolicy.failureReason(image.path, directory))
            firmware.delete()
            image.writeBytes(byteArrayOf(1, 0x2a, 0x4e, 0x49, 0))
            assertEquals("firmware_missing_disksys_rom", NesFirmwarePolicy.failureReason(image.path, directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun filenameAloneCannotRequireFdsBiosForCartridgeAndUnreadableImagesRemainLoaderErrors() {
        val directory = Files.createTempDirectory("nes-cartridge-test").toFile()
        try {
            val image = File(directory, "misnamed.fds")
            image.writeBytes(byteArrayOf(0x4e, 0x45, 0x53, 0x1a))
            assertNull(NesFirmwarePolicy.failureReason(image.path, directory))
            image.delete()
            assertNull(NesFirmwarePolicy.failureReason(image.path, directory))
        } finally {
            directory.deleteRecursively()
        }
    }
}
