package com.sbro.emucored.core

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreOptionPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreOptionPreferencesInstrumentedTest {
    @Test fun globalsAndGameOverridesSurviveRecreationAndRemainConsoleScoped() {
        testStorageContext().use { context ->
            val store = CoreOptionPreferences(context)
            assertTrue(store.setGlobal(ConsolePlatform.GAMECUBE, "resolution", "2"))
            assertTrue(store.setGameOverride(ConsolePlatform.GAMECUBE, "game://one", "resolution", "4"))
            assertFalse(store.setGameOverride(ConsolePlatform.GAMECUBE, "game://one", "wii_profile", "remote"))
            assertFalse(store.setGlobal(ConsolePlatform.GAMECUBE, "resolution", "100"))
            val recreated = CoreOptionPreferences(context)
            assertEquals("4", recreated.effectiveOptions(ConsolePlatform.GAMECUBE, "game://one")["resolution"])
            assertEquals("2", recreated.effectiveOptions(ConsolePlatform.GAMECUBE, "game://two")["resolution"])
            assertEquals("1", recreated.effectiveOptions(ConsolePlatform.WII, "game://one")["resolution"])
            assertTrue(recreated.setGameOverride(ConsolePlatform.GAMECUBE, "game://one", "resolution", null))
            assertEquals("2", recreated.effectiveOptions(ConsolePlatform.GAMECUBE, "game://one")["resolution"])
            recreated.resetGlobal(ConsolePlatform.GAMECUBE)
            assertEquals("1", recreated.effectiveOptions(ConsolePlatform.GAMECUBE, "game://one")["resolution"])
        }
    }

    @Test fun malformedPersistedValuesFallBackWithoutBlockingStartup() {
        testStorageContext().use { context ->
            context.getSharedPreferences("core_options", Context.MODE_PRIVATE).edit()
                .putInt("gamecube.global.resolution", 99)
                .putString("wii.global.graphics_backend", "unsupported")
                .apply()
            val store = CoreOptionPreferences(context)
            assertEquals("1", store.effectiveOptions(ConsolePlatform.GAMECUBE)["resolution"])
            assertEquals("vulkan", store.effectiveOptions(ConsolePlatform.WII)["graphics_backend"])
        }
    }

    @Test fun backupRestorePreservesOverridesAndRejectsForeignOrMalformedKeys() {
        testStorageContext().use { context ->
            val store = CoreOptionPreferences(context)
            assertTrue(store.setGlobal(ConsolePlatform.WII, "resolution", "2"))
            assertTrue(store.setGameOverride(ConsolePlatform.WII, "stable-game", "wii_profile", "classic"))
            val exported = store.exportJson()
            val values = exported.getJSONObject("values")
                .put("wii.global.resolution", "99")
                .put("nes.global.wii_profile", "remote")
                .put("wii.game.not-a-hash.volume", "50")
                .put("unknown.global.volume", "20")
            store.resetAll()
            store.importJson(JSONObject().put("version", 1).put("values", values))
            assertEquals("1", store.effectiveOptions(ConsolePlatform.WII)["resolution"])
            assertEquals("classic", store.effectiveOptions(ConsolePlatform.WII, "stable-game")["wii_profile"])
            assertFalse(store.exportJson().getJSONObject("values").has("nes.global.wii_profile"))
            assertFalse(store.exportJson().getJSONObject("values").has("wii.game.not-a-hash.volume"))
            val before = store.exportJson().toString()
            assertThrows(IllegalArgumentException::class.java) { store.importJson(JSONObject().put("version", 2)) }
            assertEquals(before, store.exportJson().toString())
        }
    }
}
