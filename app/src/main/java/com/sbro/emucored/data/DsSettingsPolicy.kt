package com.sbro.emucored.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlin.math.roundToInt

/** DS values never pass through Azahar's renderer normalization or resolution limits. */
object DsSettingsPolicy {
    const val DEFAULT_RENDERER = 14
    // Native resolution is the default; DS 2D artwork gains little from upscaling.
    const val DEFAULT_UPSCALE_MULTIPLIER = 1f
    const val MAX_UPSCALE_MULTIPLIER = 8
    private val rendererKey = intPreferencesKey("ds_renderer")
    private val upscaleKey = floatPreferencesKey("ds_upscale_multiplier")

    fun normalizeRenderer(value: Int): Int = if (value == 13) 13 else DEFAULT_RENDERER

    fun normalizeUpscaleMultiplier(value: Float): Float =
        if (value.isFinite()) value.coerceIn(1f, MAX_UPSCALE_MULTIPLIER.toFloat()).roundToInt().toFloat()
        else DEFAULT_UPSCALE_MULTIPLIER

    internal fun readRenderer(prefs: Preferences): Int = normalizeRenderer(prefs[rendererKey] ?: DEFAULT_RENDERER)
    internal fun readUpscaleMultiplier(prefs: Preferences): Float =
        normalizeUpscaleMultiplier(prefs[upscaleKey] ?: DEFAULT_UPSCALE_MULTIPLIER)

    internal fun storeRenderer(prefs: MutablePreferences, value: Int) {
        prefs[rendererKey] = normalizeRenderer(value)
    }

    internal fun storeUpscaleMultiplier(prefs: MutablePreferences, value: Float) {
        prefs[upscaleKey] = normalizeUpscaleMultiplier(value)
    }
}

/** The selected platform's global graphics baseline, before per-game overrides. */
fun SettingsSnapshot.forPlatform(platform: GamePlatform): SettingsSnapshot =
    if (platform == GamePlatform.NDS) copy(
        renderer = DsSettingsPolicy.normalizeRenderer(dsRenderer),
        upscaleMultiplier = DsSettingsPolicy.normalizeUpscaleMultiplier(dsUpscaleMultiplier)
    ) else this
