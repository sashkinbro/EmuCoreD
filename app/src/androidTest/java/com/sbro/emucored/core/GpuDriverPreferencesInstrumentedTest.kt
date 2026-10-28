package com.sbro.emucored.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucored.core.platform.ConsolePlatform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpuDriverPreferencesInstrumentedTest {
    @Test fun perGameSystemAndDriverSelectionsDoNotLeakAcrossCores() {
        testStorageContext().use { context ->
            val settings = GpuDriverPreferences(context)
            settings.setGlobal(ConsolePlatform.N3DS, "turnip")
            assertEquals("turnip", settings.effective(ConsolePlatform.N3DS, "game-a"))
            assertNull(settings.effective(ConsolePlatform.NDS, "game-a"))
            settings.setGame(ConsolePlatform.N3DS, "game-a", null)
            assertNull(settings.effective(ConsolePlatform.N3DS, "game-a"))
            assertEquals("turnip", settings.effective(ConsolePlatform.N3DS, "game-b"))
            settings.setGame(ConsolePlatform.N3DS, "game-a", "another")
            assertEquals("another", settings.effective(ConsolePlatform.N3DS, "game-a"))
            settings.clearGame(ConsolePlatform.N3DS, "game-a")
            assertEquals("turnip", settings.effective(ConsolePlatform.N3DS, "game-a"))
        }
    }
}
