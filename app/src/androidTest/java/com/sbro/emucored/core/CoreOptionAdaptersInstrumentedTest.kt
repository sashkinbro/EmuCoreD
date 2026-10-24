package com.sbro.emucored.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.gb.SameBoySessionEngine
import com.sbro.emucored.core.gba.MgbaSessionEngine
import com.sbro.emucored.core.nes.NestopiaSessionEngine
import com.sbro.emucored.core.snes.BsnesSessionEngine
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreOptionSchema
import com.sbro.emucored.core.platform.session.CoreSessionEngine
import com.sbro.emucored.core.platform.session.EngineLaunchRequest
import com.sbro.emucored.core.platform.session.EngineStartResult
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exhausts the advertised option values against production JNI, including invalid input rejection. */
@RunWith(AndroidJUnit4::class)
class CoreOptionAdaptersInstrumentedTest {
    @Test(timeout = 120_000) fun frameCoreOptionsAcceptDeclaredValuesAndContinueEmulating() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("coreOptions") == "true")
        testStorageContext().use { context ->
            val engines: List<Pair<ConsolePlatform, () -> CoreSessionEngine>> = listOf(
                ConsolePlatform.GBA to { MgbaSessionEngine() },
                ConsolePlatform.GB to { SameBoySessionEngine() },
                ConsolePlatform.GBC to { SameBoySessionEngine() },
                ConsolePlatform.NES to { NestopiaSessionEngine() },
                ConsolePlatform.SNES to { BsnesSessionEngine() }
            )
            for ((platform, create) in engines) {
                val rom = File(context.cacheDir, "options.${platform.id}").apply {
                    writeBytes(OriginalConsolePrograms.forPlatform(platform))
                }
                val core = create()
                try {
                    assertEquals("$platform defaults", EngineStartResult.Started,
                        core.start(EngineLaunchRequest(context, platform, rom.path, "options-${platform.id}",
                            context.filesDir, CoreOptionSchema.resolve(platform), startPaused = true)))
                    for (option in CoreOptionSchema.options(platform)) {
                        assertFalse("$platform ${option.key} accepted invalid input", core.applyOption(option.key, "invalid"))
                        for (value in option.values) {
                            assertTrue("$platform ${option.key}=$value", core.applyOption(option.key, value))
                        }
                        assertTrue(core.applyOption(option.key, option.defaultValue))
                    }
                    assertTrue(core.applyOption("audio_enabled", "false"))
                    core.resume()
                    withTimeout(20_000) { while (core.frameCount() < 3) delay(10) }
                    core.pause()
                    assertTrue("$platform stopped after option changes", core.frameCount() >= 3)
                } finally { core.stop() }
            }
        }
    }
}
