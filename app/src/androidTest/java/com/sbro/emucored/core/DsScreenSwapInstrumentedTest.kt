package com.sbro.emucored.core

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.ds.DsSession
import com.sbro.emucored.core.ds.EmuCoreDsNative
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in with -e dsSwapSentinel true. The ROM is generated original ARM code, no game assets. */
@RunWith(AndroidJUnit4::class)
class DsScreenSwapInstrumentedTest {
    @Test(timeout = 120_000)
    fun physicalRedAndCyanScreensSwapForSoftwareAndVulkanAtBothScales() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dsSwapSentinel") == "true")
        assertTrue(EmuCoreDsNative.isLoaded)
        assertFalse(DsSession.isActive)
        val oldRenderer = DsSession.currentRenderer()
        val oldScale = DsSession.currentRenderScale()
        testStorageContext().use { context ->
            val rom = File(context.filesDir, "original-screen-sentinel.nds").apply { writeBytes(createDsScreenSentinelRom()) }
            val consumer = HandlerThread("DsSwapSentinel").apply { start() }
            val samples = AtomicReference<ScreenSample?>()
            val reader = ImageReader.newInstance(256, 386, PixelFormat.RGBA_8888, 3)
            reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { image ->
                    val plane = image.planes[0]
                    fun pixel(y: Int): Int {
                        val offset = y * plane.rowStride + image.width / 2 * plane.pixelStride
                        return ((plane.buffer.get(offset).toInt() and 255) shl 16) or
                            ((plane.buffer.get(offset + 1).toInt() and 255) shl 8) or
                            (plane.buffer.get(offset + 2).toInt() and 255)
                    }
                    samples.set(ScreenSample(pixel(image.height / 4), pixel(image.height * 3 / 4)))
                }
            }, Handler(consumer.looper))
            try {
                for (renderer in listOf(13, 14)) for (scale in listOf(1, 2)) {
                    samples.set(null)
                    DsSession.setLayout(DsSession.LAYOUT_STACKED)
                    DsSession.setSurface(reader.surface)
                    assertTrue("Start failed renderer=$renderer scale=$scale", DsSession.start(
                        context, rom.absolutePath, renderScale = scale, renderer = renderer
                    ))
                    awaitColors("Default bottom-first renderer=$renderer scale=$scale", samples, redOnTop = false)
                    DsSession.pause()
                    Thread.sleep(200) // Finish an in-flight frame before checking guest stability.
                    val pausedFrame = EmuCoreDsNative.nativeGetFrameCount()
                    DsSession.setSwapScreens(true)
                    awaitColors("Swapped top-first renderer=$renderer scale=$scale", samples, redOnTop = true)
                    assertEquals("Swap advanced the paused guest", pausedFrame, EmuCoreDsNative.nativeGetFrameCount())
                    DsSession.setSwapScreens(false)
                    awaitColors("Restored bottom-first renderer=$renderer scale=$scale", samples, redOnTop = false)
                    assertEquals("Restore advanced the paused guest", pausedFrame, EmuCoreDsNative.nativeGetFrameCount())
                    DsSession.resume()
                    DsSession.stop()
                }
            } finally {
                DsSession.stop()
                DsSession.setSurface(null)
                reader.setOnImageAvailableListener(null, null)
                consumer.quitSafely()
                consumer.join(5_000)
                reader.close()
                DsSession.setRenderer(oldRenderer)
                DsSession.setRenderScale(oldScale)
            }
        }
    }

    private data class ScreenSample(val top: Int, val bottom: Int)
    private fun awaitColors(label: String, samples: AtomicReference<ScreenSample?>, redOnTop: Boolean) {
        fun red(color: Int) = (color shr 16 and 255) > 200 && (color and 255) < 40
        // Cyan provides a green anchor: a red/blue channel inversion cannot masquerade as a screen swap.
        fun cyan(color: Int) = (color and 255) > 200 && (color shr 8 and 255) > 200 && (color shr 16 and 255) < 40
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val sample = samples.get()
            if (sample != null && if (redOnTop) red(sample.top) && cyan(sample.bottom)
                else cyan(sample.top) && red(sample.bottom)) return
            Thread.sleep(10)
        }
        throw AssertionError("$label: last pixels=${samples.get()}")
    }

}

internal fun createDsScreenSentinelRom(animatedPhase: Boolean = false): ByteArray {
        val instructions = mutableListOf<Int>()
        val literals = mutableListOf<Triple<Int, Int, Int>>()
        fun load(register: Int, value: Int) {
            literals += Triple(instructions.size, register, value)
            instructions += 0
        }
        fun write(address: Int, value: Int, opcode: Int = 0xE5801000.toInt()) {
            load(0, address); load(1, value); instructions += opcode
        }
        fun branch(target: Int, condition: Int = 0xE) {
            val displacement = target - instructions.size - 2
            instructions += (condition shl 28) or 0x0A000000 or (displacement and 0xFFFFFF)
        }
        write(0x04000304, 0x8203, 0xE1C010B0.toInt()) // LCD + both 2D engines, A on physical top.
        write(0x04000240, 0x81, 0xE5C01000.toInt()) // VRAM A: main BG.
        write(0x04000242, 0x84, 0xE5C01000.toInt()) // VRAM C: sub BG.
        for (base in listOf(0x04000000, 0x04001000)) {
            write(base, 0x10405) // Mode 5, BG2, normal display.
            write(base + 0x0C, 0x4084, 0xE1C010B0.toInt()) // 256x256 bitmap; bit 2 selects direct RGB.
            write(base + 0x20, 0x100) // Identity affine PA/PB.
            write(base + 0x24, 0x01000000) // Identity affine PC/PD.
        }
        for ((address, color) in listOf(0x06000000 to 0x801F801F.toInt(), 0x06200000 to 0xFFE0FFE0.toInt())) {
            load(0, address); load(1, color); load(2, 0x8000)
            instructions += 0xE4801004.toInt() // str r1,[r0],#4
            instructions += 0xE2522001.toInt() // subs r2,r2,#1
            instructions += 0x1AFFFFFC // bne to str
        }
        if (animatedPhase) {
            // Correlate texture data and scroll registers. The intended image stays
            // red on the left and cyan on the right even as both change in VBlank.
            write(0x0400000C, 0x6084, 0xE1C010B0.toInt()) // Enable bitmap overflow/wrap.
            instructions += 0xE3A04000.toInt() // mov r4,#0 (frame counter)
            val frameLoop = instructions.size
            load(0, 0x04000006) // VCOUNT
            val waitVisible = instructions.size
            instructions += 0xE1D030B0.toInt() // ldrh r3,[r0]
            instructions += 0xE35300C0.toInt() // cmp r3,#192
            branch(waitVisible, 2) // bhs: finish current VBlank
            val waitVblank = instructions.size
            instructions += 0xE1D030B0.toInt()
            instructions += 0xE35300C0.toInt()
            branch(waitVblank, 3) // blo: wait for next VBlank
            instructions += 0xE2844001.toInt() // add r4,r4,#1
            instructions += 0xE3540005.toInt() // cmp r4,#5
            instructions += 0x03A04000 // moveq r4,#0
            instructions += 0xE3540002.toInt() // cmp r4,#2
            instructions += 0x33A05000 // movlo r5,#0
            instructions += 0x23A05001 // movhs r5,#1
            // A five-frame period avoids aliasing with triple buffering or every-other-frame capture.
            instructions += 0xE3550000.toInt() // cmp r5,#0
            load(1, 0x801F801F.toInt())
            load(2, 0xFFE0FFE0.toInt())
            instructions += 0x11A08001 // movne r8,r1
            instructions += 0x11A01002 // movne r1,r2
            instructions += 0x11A02008 // movne r2,r8
            load(0, 0x06003800) // Rows 28–35 cover the sampled row at either output aspect ratio.
            instructions += 0xE3A06008.toInt() // mov r6,#8: only 1024 word stores per VBlank
            val row = instructions.size
            instructions += 0xE3A07040.toInt() // mov r7,#64 words (128 pixels)
            val left = instructions.size
            instructions += 0xE4801004.toInt() // str r1,[r0],#4
            instructions += 0xE2577001.toInt() // subs r7,r7,#1
            branch(left, 1)
            instructions += 0xE3A07040.toInt()
            val right = instructions.size
            instructions += 0xE4802004.toInt() // str r2,[r0],#4
            instructions += 0xE2577001.toInt()
            branch(right, 1)
            instructions += 0xE2566001.toInt() // subs r6,r6,#1
            branch(row, 1)
            instructions += 0xE3550000.toInt()
            instructions += 0x03A01000 // moveq r1,#0
            // 0x8000 = 128 pixels in 24.8 fixed point, encoded as imm 0x80 ROR 24.
            instructions += 0x13A01C80 // movne r1,#0x8000
            // Apply the affine reference explicitly at visible line 0, after
            // the core's VBlankEnd reload and well before sampled line 32.
            load(0, 0x04000006)
            val waitLineZero = instructions.size
            instructions += 0xE1D030B0.toInt()
            instructions += 0xE35300C0.toInt()
            branch(waitLineZero, 2)
            load(0, 0x04000028) // BG2X
            instructions += 0xE5801000.toInt()
            branch(frameLoop)
        }
        instructions += 0xEAFFFFFE.toInt()
        for ((index, register, value) in literals) {
            val displacement = (instructions.size - index - 2) * 4
            check(displacement in 0..4095)
            instructions[index] = 0xE59F0000.toInt() or (register shl 12) or displacement
            instructions += value
        }
        val rom = ByteBuffer.allocate(0x20000).order(ByteOrder.LITTLE_ENDIAN)
        "SCREEN PROBE".toByteArray().copyInto(rom.array())
        "####".toByteArray().copyInto(rom.array(), 0x0C)
        rom.putInt(0x20, 0x200); rom.putInt(0x24, 0x02000000)
        rom.putInt(0x28, 0x02000000); rom.putInt(0x2C, instructions.size * 4)
        rom.putInt(0x30, 0x1000); rom.putInt(0x34, 0x03800000)
        rom.putInt(0x38, 0x03800000); rom.putInt(0x3C, 4)
        rom.putInt(0x80, 0x20000); rom.putInt(0x84, 0x200)
        instructions.forEachIndexed { index, word -> rom.putInt(0x200 + index * 4, word) }
        rom.putInt(0x1000, 0xEAFFFFFE.toInt())
        return rom.array()
    }
