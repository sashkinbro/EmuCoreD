package com.sbro.emucored.core

import android.widget.TextView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.ds.DsSession
import com.sbro.emucored.core.ds.EmuCoreDsNative
import com.sbro.emucored.core.dolphin.DolphinSessionEngine
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.session.CoreCheatCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Original programs and isolated files only. Opt in with -e typedCheats true. */
@RunWith(AndroidJUnit4::class)
class TypedCheatDeviceInstrumentedTest {
    @get:Rule val compose=createComposeRule()
    @Test(timeout=120_000)
    fun dsActionReplayChangesGuestRamOnlyAfterResumeAndRejectedReplacementKeepsOldSet()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("typedCheats")=="true")
        compose.setContent{AndroidView(factory={TextView(it).apply{keepScreenOn=true;text="Typed cheat runtime test"}})}
        val oldRenderer=DsSession.currentRenderer();val oldScale=DsSession.currentRenderScale()
        testStorageContext().use { context ->
            val rom=File(context.cacheDir,"original-cheat-irq.nds").apply{writeBytes(irqFixture())}
            try {
                for(renderer in listOf(13,14)) {
                    assertTrue(DsSession.start(context,rom.path,"original-cheat-$renderer.nds",1,renderer,startPaused=true))
                    try {
                        DsSession.resume();advance(3);DsSession.pause()
                        val baseline=File(context.cacheDir,"cheat-baseline-$renderer.dss")
                        assertTrue(EmuCoreDsNative.nativeSaveState(baseline.path))
                        val initial=ramWord(baseline);assertNotEquals(0x12345678,initial)
                        val frames=EmuCoreDsNative.nativeGetFrameCount()
                        assertTrue(DsSession.replaceCheats(listOf(CoreCheatCode("nds-action-replay","02010000 12345678"))))
                        assertEquals(frames,EmuCoreDsNative.nativeGetFrameCount())
                        val changed=File(context.cacheDir,"cheat-changed-$renderer.dss")
                        assertTrue(EmuCoreDsNative.nativeSaveState(changed.path));assertEquals(initial,ramWord(changed))
                        DsSession.resume();advance(3);DsSession.pause()
                        assertTrue(EmuCoreDsNative.nativeSaveState(changed.path));assertEquals(0x12345678,ramWord(changed))
                        assertFalse(DsSession.replaceCheats(listOf(CoreCheatCode("nds-action-replay","E2010000 00000020\n11111111 22222222"))))
                        assertTrue(EmuCoreDsNative.nativeLoadState(baseline.path))
                        DsSession.resume();advance(3);DsSession.pause()
                        assertTrue(EmuCoreDsNative.nativeSaveState(changed.path));assertEquals(0x12345678,ramWord(changed))
                        assertTrue(DsSession.replaceCheats(emptyList()))
                        assertTrue(EmuCoreDsNative.nativeLoadState(baseline.path))
                        DsSession.resume();advance(3);DsSession.pause()
                        assertTrue(EmuCoreDsNative.nativeSaveState(changed.path));assertEquals(initial,ramWord(changed))
                    }finally{DsSession.stop()}
                }
            }finally{DsSession.stop();DsSession.setRenderer(oldRenderer);DsSession.setRenderScale(oldScale)}
        }
    }
    @Test fun dolphinIdentityReadsHeaderGameIdAndRevisionWithoutBootOrFilenameGuess() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("typedCheats")=="true")
        testStorageContext().use { context ->
            val image=ByteArray(64*1024)
            "GTSE01".toByteArray().copyInto(image);image[7]=3
            ByteBuffer.wrap(image).order(ByteOrder.BIG_ENDIAN).putInt(0x1c,0xc2339f3d.toInt())
            val file=File(context.cacheDir,"misleading-wii-name.iso").apply{writeBytes(image)}
            val identity=DolphinSessionEngine.probeGameIdentity(file.path)
            assertNotNull(identity);assertEquals(ConsolePlatform.GAMECUBE,identity!!.platform)
            assertEquals("GTSE01",identity.gameId);assertEquals(3,identity.revision)
        }
    }
    private suspend fun advance(count:Long) {
        val start=EmuCoreDsNative.nativeGetFrameCount()
        withTimeout(10_000){while(EmuCoreDsNative.nativeGetFrameCount()<start+count)delay(10)}
    }
    private fun ramWord(file:File):Int {
        val bytes=file.readBytes();val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset=16
        while(offset+20<bytes.size) {
            if(String(bytes,offset,4,Charsets.US_ASCII)=="NDSG")return buffer.getInt(offset+20+0x10000)
            val length=buffer.getInt(offset+4);require(length>=16 && offset+length<=bytes.size);offset+=length
        }
        error("Missing NDSG state section")
    }
    private fun irqFixture():ByteArray {
        val rom=createDsScreenSentinelRom();val words=mutableListOf<Int>();val literals=mutableListOf<Triple<Int,Int,Int>>()
        fun load(register:Int,value:Int){literals+=Triple(words.size,register,value);words+=0}
        load(0,0x03fffffc);load(1,0x03800080);words+=0xe5801000.toInt()
        load(0,0x04000004);words+=0xe3a01008.toInt();words+=0xe1c010b0.toInt()
        load(0,0x04000210);words+=0xe3a01001.toInt();words+=0xe5801000.toInt()
        load(0,0x04000208);words+=0xe5801000.toInt();words+=0xe321f01f.toInt();words+=0xeafffffe.toInt()
        while(words.size<32)words+=0xe1a00000.toInt()
        load(0,0x04000214);words+=0xe3a01001.toInt();words+=0xe5801000.toInt();words+=0xe12fff1e.toInt()
        for((index,register,value) in literals){val displacement=(words.size-index-2)*4
            words[index]=0xe59f0000.toInt() or (register shl 12) or displacement;words+=value}
        val buffer=ByteBuffer.wrap(rom).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x3c,words.size*4);words.forEachIndexed{index,word->buffer.putInt(0x1000+index*4,word)}
        return rom
    }
}
