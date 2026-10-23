// SPDX-License-Identifier: CC0-1.0
package com.sbro.emucored.core

import com.sbro.emucored.core.platform.ConsolePlatform
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Original CPU programs. No commercial ROM, vendor logo or firmware is included. */
internal object OriginalConsolePrograms {
    fun forPlatform(platform: ConsolePlatform): ByteArray = when (platform) {
        ConsolePlatform.GAMECUBE, ConsolePlatform.WII -> dolphin(platform == ConsolePlatform.WII)
        ConsolePlatform.GBA -> ByteArray(512).apply {
            put(0, 0x2e, 0, 0, 0xea) // ARM branch over cartridge header.
            this[0xb2] = 0x96.toByte()
            // Mode 3/BG2, fill all 240x160 VRAM pixels red, then loop.
            val words = intArrayOf(0xe3a00301.toInt(), 0xe59f101c.toInt(), 0xe1c010b0.toInt(),
                0xe3a00406.toInt(), 0xe3a0101f.toInt(), 0xe59f2010.toInt(), 0xe0c010b2.toInt(),
                0xe2522001.toInt(), 0x1afffffc, 0xeafffffe.toInt(), 0x403, 38400)
            ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).apply {
                position(0xc0); words.forEach { putInt(it) }
            }
        }
        ConsolePlatform.GB, ConsolePlatform.GBC -> ByteArray(32768).apply {
            put(0x100, 0xc3, 0x50, 1)
            "EMUCORE TEST".toByteArray().copyInto(this, 0x134)
            this[0x143] = if (platform == ConsolePlatform.GBC) 0x80.toByte() else 0
            // LCD on, blank tile 0 in white DMG/CGB palette; spin forever.
            put(0x150, 0x3e, 0x91, 0xe0, 0x40, 0x18, 0xfe)
            var sum = 0
            for (i in 0x134..0x14c) sum = (sum - (this[i].toInt() and 255) - 1) and 255
            this[0x14d] = sum.toByte()
        }
        ConsolePlatform.NES -> ByteArray(16 + 32768 + 8192).apply {
            put(0, 0x4e, 0x45, 0x53, 0x1a, 2, 1, 2)
            fill(0xea.toByte(), 16, 16 + 32768)
            val program = intArrayOf(0x78, 0xd8, 0xa2, 0xff, 0x9a, 0xa9, 0, 0x8d,
                0, 0x20, 0x8d, 1, 0x20, 0xad, 2, 0x20,
                0xa9, 0x3f, 0x8d, 6, 0x20, 0xa9, 0, 0x8d,
                6, 0x20, 0xa9, 0x21, 0x8d, 7, 0x20, 0xa9,
                0x0a, 0x8d, 1, 0x20, 0x4c, 0x23, 0xe0)
            repeat(4) { bank ->
                put(16 + bank * 8192, *program)
                for (vector in 0x1ffa until 8192 step 2) put(16 + bank * 8192 + vector, 0, 0xe0)
            }
        }
        ConsolePlatform.SNES -> ByteArray(32768).apply {
            put(0, 0x78, 0x18, 0xfb, 0xc2, 0x10, 0xe2, 0x20,
                0xa9, 0x80, 0x8d, 0, 0x21, 0xa9, 0, 0x8d, 0x21, 0x21,
                0xa9, 0x1f, 0x8d, 0x22, 0x21, 0xa9, 0, 0x8d, 0x22, 0x21,
                0xa9, 0x0f, 0x8d, 0, 0x21, 0x80, 0xfe)
            "EMUCORE SNES TEST     ".toByteArray().copyInto(this, 0x7fc0)
            put(0x7fd5, 0x20, 2, 5, 1, 1)
            put(0x7fdc, 0xcb, 0xed, 0x34, 0x12)
            put(0x7ffc, 0, 0x80)
        }
        else -> error("No framebuffer fixture for $platform")
    }
    private fun dolphin(wii: Boolean): ByteArray {
        val code = mutableListOf(0x3c60cc00, 0x60632000)
        for ((offset, value) in listOf(0 to ((240 shl 4) or 6), 2 to 5, 12 to 5,
            14 to 22, 16 to 4, 18 to 23, 72 to 0x1414)) {
            code += 0x38800000 or value
            code += 0xb0830000.toInt() or offset
        }
        code += listOf(0x3c800010, 0x9083001c.toInt(), 0x90830024.toInt(), 0x48000000)
        if (wii) code += 0x7c13fba6
        while (code.size % 8 != 0) code += 0x60000000
        val textSize = code.size * 4
        return ByteBuffer.allocate(256 + textSize + 320 * 240 * 2).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0, 256); putInt(0x48, 0x80004000.toInt()); putInt(0x90, textSize)
            putInt(0x1c, 256 + textSize); putInt(0x64, 0x80100000.toInt()); putInt(0xac, 320 * 240 * 2)
            putInt(0xe0, 0x80004000.toInt()); position(256)
            code.forEach { putInt(it) }
            val colors = intArrayOf(0x5a5a5af0, 0x91369122.toInt(), 0x29f0296e)
            repeat(240) { repeat(160) { x -> putInt(colors[minOf(2, x * 3 / 160)]) } }
        }.array()
    }
    private fun ByteArray.put(offset: Int, vararg bytes: Int) {
        bytes.forEachIndexed { index, byte -> this[offset + index] = byte.toByte() }
    }
}
