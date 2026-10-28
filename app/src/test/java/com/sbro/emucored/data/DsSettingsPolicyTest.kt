package com.sbro.emucored.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import org.junit.Assert.*
import org.junit.Test

class DsSettingsPolicyTest {
    @Test fun softwareIsTheDefaultRenderer() {
        assertEquals(DsSettingsPolicy.RENDERER_SOFTWARE, DsSettingsPolicy.DEFAULT_RENDERER)
    }

    @Test fun freshDsDefaultsDoNotInheritThreeDsGraphics() {
        val settings = SettingsSnapshot(renderer = 12, upscaleMultiplier = 7f)
        val ds = settings.forPlatform(GamePlatform.NDS)
        assertEquals(13, ds.renderer)
        assertEquals(1f, ds.upscaleMultiplier, 0f)
        assertSame(settings, settings.forPlatform(GamePlatform.N3DS))
    }

    @Test fun dsSoftwareAndScaleAreIndependentOfThreeDsDefaults() {
        val settings = SettingsSnapshot(renderer = 12, upscaleMultiplier = 7f,
            dsRenderer = 13, dsUpscaleMultiplier = 4f)
        val ds = settings.forPlatform(GamePlatform.NDS)
        assertEquals(13, ds.renderer)
        assertEquals(4f, ds.upscaleMultiplier, 0f)
        assertEquals(12, settings.forPlatform(GamePlatform.N3DS).renderer)
        assertEquals(7f, settings.forPlatform(GamePlatform.N3DS).upscaleMultiplier, 0f)
    }

    @Test fun dsValuesAreBoundedWithoutAcceptingThreeDsOpenGl() {
        assertEquals(13, DsSettingsPolicy.normalizeRenderer(12))
        assertEquals(13, DsSettingsPolicy.normalizeRenderer(-1))
        assertEquals(13, DsSettingsPolicy.normalizeRenderer(13))
        assertEquals(14, DsSettingsPolicy.normalizeRenderer(14))
        assertEquals(1f, DsSettingsPolicy.normalizeUpscaleMultiplier(-10f), 0f)
        assertEquals(8f, DsSettingsPolicy.normalizeUpscaleMultiplier(100f), 0f)
        assertEquals(3f, DsSettingsPolicy.normalizeUpscaleMultiplier(2.6f), 0f)
        assertEquals(1f, DsSettingsPolicy.normalizeUpscaleMultiplier(Float.NaN), 0f)
        assertEquals(1f, DsSettingsPolicy.normalizeUpscaleMultiplier(Float.POSITIVE_INFINITY), 0f)
    }

    @Test fun dsPreferenceWritesLeaveThreeDsStorageUntouchedAndReloadBothValues() {
        val threeDsRenderer = intPreferencesKey("renderer")
        val threeDsUpscale = floatPreferencesKey("upscale_multiplier_v2")
        val prefs = mutablePreferencesOf(threeDsRenderer to 12, threeDsUpscale to 7f)
        assertEquals(13, DsSettingsPolicy.readRenderer(prefs))
        assertEquals(1f, DsSettingsPolicy.readUpscaleMultiplier(prefs), 0f)
        DsSettingsPolicy.storeRenderer(prefs, 13)
        DsSettingsPolicy.storeUpscaleMultiplier(prefs, 4f)
        val reloaded = prefs.toPreferences()
        assertEquals(13, DsSettingsPolicy.readRenderer(reloaded))
        assertEquals(4f, DsSettingsPolicy.readUpscaleMultiplier(reloaded), 0f)
        assertEquals(12, reloaded[threeDsRenderer])
        assertEquals(7f, reloaded[threeDsUpscale]!!, 0f)
        DsSettingsPolicy.storeRenderer(prefs, 12)
        DsSettingsPolicy.storeUpscaleMultiplier(prefs, 50f)
        assertEquals(13, DsSettingsPolicy.readRenderer(prefs))
        assertEquals(8f, DsSettingsPolicy.readUpscaleMultiplier(prefs), 0f)
        DsSettingsPolicy.storeRenderer(prefs, 14)
        assertEquals(14, DsSettingsPolicy.readRenderer(prefs))
    }
}
