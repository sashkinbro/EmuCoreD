// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import android.util.Log
import com.sbro.emucored.data.DisplayCrop
import java.io.File

/**
 * Writes EmuCoreD's settings into the core's own `config.ini`.
 *
 * The core reads its configuration from the user directory, so the app mirrors
 * the values it owns into that file and asks the core to reload them. Keys use
 * the core's ini section names; unknown keys are skipped.
 */
internal object CoreConfig {
    private const val TAG = "CoreConfig"
    private const val CONFIG_DIR = "config"
    private const val CONFIG_FILE_NAME = "config.ini"

    data class Values(
        val renderer: Int,
        val resolutionFactor: Float,
        val aspectRatio: Int,
        val displayCrop: DisplayCrop,
        val audioVolume: Int,
        val audioFastForwardVolume: Int,
        val audioMuted: Boolean,
        val audioOutputLatencyMs: Int,
        val audioMinimalOutputLatency: Boolean,
        val frameSkip: Int,
        val frameLimitEnabled: Boolean,
        val vSyncEnabled: Boolean,
        val fastForwardSpeed: Float,
        val shaderChainEnabled: Boolean = false,
        val shaderChainPreset: String = "",
        val customTextures: Boolean,
        val cheatsEnabled: Boolean,
        val performanceOverlay: Boolean,
        val mediatekAngleOpenGl: Boolean,
        /** -1 keeps the user's stored layout; any other value forces it. */
        val layoutOption: Int = -1,
        /** null keeps the user's stored swap preference. */
        val swapScreens: Boolean? = null
    )

    /** Latest applied app values, so runtime option changes can rewrite the file. */
    @Volatile
    private var lastApplied: Values? = null

    /** Per-session overrides (per-game core options); never persisted globally. */
    private val transientOverrides = linkedMapOf<String, String>()

    fun setTransientOverride(key: String, value: String) {
        synchronized(transientOverrides) { transientOverrides[key] = value }
    }

    fun clearTransientOverrides() {
        synchronized(transientOverrides) { transientOverrides.clear() }
    }

    private fun transientOverride(key: String): String? =
        synchronized(transientOverrides) { transientOverrides[key] }

    /** Options owned by dedicated app controls; the store never overrides them. */
    private val appOwnedKeys = setOf(
        "graphics_api",
        "resolution_factor",
        "volume",
        "aspect_ratio",
        "layout_option",
        "swap_screen",
        "use_vsync",
        "use_frame_limit",
        "turbo_limit",
        "custom_textures",
        "audio_output_latency_ms",
        "audio_low_latency"
    )

    /** Core ini section for each option the settings screen can change. */
    private val storedSectionByKey = mapOf(
        "use_cpu_jit" to "Core",
        "cpu_clock_percentage" to "Core",
        "is_new_3ds" to "System",
        "region_value" to "System",
        "language" to "System",
        "apply_region_free_patch" to "System",
        "audio_emulation" to "Audio",
        "enable_audio_stretching" to "Audio",
        "enable_realtime_audio" to "Audio",
        "input_type" to "Audio",
        "use_hw_shader" to "Renderer",
        "use_shader_jit" to "Renderer",
        "shaders_accurate_mul" to "Renderer",
        "use_disk_shader_cache" to "Renderer",
        "use_skip_duplicate_frames" to "Renderer",
        "texture_filter" to "Renderer",
        "texture_sampling" to "Renderer",
        "motion_device" to "Controls",
        "touch_device" to "Controls",
        "udp_pad_index" to "Controls",
        "udp_input_address" to "Controls",
        "udp_input_port" to "Controls"
    )

    fun apply(values: Values) {
        lastApplied = values
        val root = CoreRuntime.userDirectory()
        if (root.isBlank()) return
        // The core reads its settings from <user dir>/config/config.ini.
        val configDir = File(root, CONFIG_DIR)
        val configFile = File(configDir, CONFIG_FILE_NAME)
        // Start from the core's own default file so nothing is lost when the
        // user has never opened the settings screen.
        if (!configFile.isFile) {
            runCatching { EmuCoreNative.createConfigFile() }
        }
        val existing = runCatching {
            if (configFile.isFile) configFile.readText() else ""
        }.getOrDefault("")

        val overrides = linkedMapOf<String, LinkedHashMap<String, String>>()
        fun put(section: String, key: String, value: String) {
            overrides.getOrPut(section) { linkedMapOf() }[key] = value
        }

        put("Core", "use_cpu_jit", "true")
        put("Core", "cpu_clock_percentage", "100")
        put(
            "Renderer",
            "graphics_api",
            RendererDefaults.coreRendererName(RendererDefaults.toCoreRenderer(values.renderer))
        )
        put(
            "Renderer",
            "resolution_factor",
            Math.round(values.resolutionFactor).coerceIn(1, 10).toString()
        )
        put("Renderer", "use_hw_shader", "true")
        put("Renderer", "use_shader_jit", "true")
        put("Renderer", "use_disk_shader_cache", "true")
        put("Renderer", "use_vsync", values.vSyncEnabled.toString())
        put("Renderer", "use_frame_limit", values.frameLimitEnabled.toString())
        put("Renderer", "frame_limit", "100")
        put("Renderer", "turbo_limit", values.fastForwardSpeed.coerceIn(1f, 5f).toInt().toString())
        put("Renderer", "async_shader_compilation", "true")
        put("Renderer", "spirv_shader_gen", "true")
        put("Utility", "custom_textures", values.customTextures.toString())
        put(
            "Layout",
            "layout_option",
            (values.layoutOption.takeIf { it >= 0 }?.toString()
                ?: transientOverride("layout_option")
                ?: CoreOptionStore.value("layout_option") ?: "0")
        )
        put(
            "Layout",
            "swap_screen",
            (values.swapScreens?.toString()
                ?: transientOverride("swap_screen")
                ?: CoreOptionStore.value("swap_screen") ?: "false")
        )
        put("Layout", "aspect_ratio", aspectRatioValue(values.aspectRatio))
        put("Layout", "upright_screen", "false")
        put("Audio", "volume", values.audioVolume.toString())
        put("System", "is_new_3ds", "true")
        put("System", "region_value", "-1")
        put("Data Storage", "use_virtual_sd", "true")
        put("Miscellaneous", "check_for_update_on_start", "false")

        // User-chosen core options from the settings screen reach the ini for
        // every key the app does not own through a dedicated control.
        CoreOptionStore.persistedEntries().forEach { (key, value) ->
            if (key in appOwnedKeys) return@forEach
            val section = storedSectionByKey[key] ?: return@forEach
            val sectionValues = overrides.getOrPut(section) { linkedMapOf() }
            if (key !in sectionValues) {
                sectionValues[key] = value
            }
        }

        // Per-game options win for the running session only.
        synchronized(transientOverrides) {
            transientOverrides.forEach { (key, value) ->
                if (key in appOwnedKeys) return@forEach
                val section = storedSectionByKey[key] ?: return@forEach
                overrides.getOrPut(section) { linkedMapOf() }[key] = value
            }
        }

        val merged = mergeSectionValues(existing, overrides)
        runCatching {
            configDir.mkdirs()
            configFile.writeText(merged)
            // Older builds wrote an unused copy in the user root; drop it.
            File(root, CONFIG_FILE_NAME).takeIf { it.isFile }?.delete()
        }
        runCatching { EmuCoreNative.reloadSettings() }
    }

    /** Rewrites the core config after a runtime option change. */
    fun reapply() {
        val values = lastApplied ?: return
        runCatching { apply(values) }
            .onFailure { Log.w(TAG, "Unable to reapply the core config", it) }
    }

    /** EmuCoreD aspect choice -> core aspect ratio value. */
    private fun aspectRatioValue(appValue: Int): String = when (appValue) {
        2 -> "2"    // 4:3
        3 -> "1"    // 16:9
        0 -> "5"    // stretch
        else -> "0" // default / auto
    }

    /**
     * Stores the RetroArch shader chain choice and pushes it into the frontend
     * shader library, which builds its filter chain for the next present.
     */
    fun setShaderChain(enabled: Boolean, presetPath: String) {
        enabledFlag = enabled && presetPath.isNotBlank()
        presetPathValue = presetPath.trim()
        runCatching {
            shaderBridge.nativeSetShaderPreset(presetPathValue, enabledFlag)
        }.onFailure { Log.w(TAG, "Unable to apply the shader chain", it) }
    }

    fun shaderChainEnabled(): Boolean = enabledFlag

    fun shaderChainPresetPath(): String = presetPathValue

    @Volatile
    private var enabledFlag: Boolean = false

    @Volatile
    private var presetPathValue: String = ""

    private val shaderBridge: NativeCoreBridge by lazy { NativeCoreBridge() }

    /**
     * Rewrites the values of [overrides] inside [existing], keeping every other
     * key and comment untouched, and appends sections that are missing.
     */
    private fun mergeSectionValues(
        existing: String,
        overrides: Map<String, Map<String, String>>
    ): String {
        val lines = existing.lines().toMutableList()

        // Section positions are resolved against the current line list: earlier
        // insertions shift every following index.
        fun findSectionStart(name: String): Int {
            lines.forEachIndexed { index, line ->
                val header = line.trim()
                if (header.length >= 2 && header.startsWith("[") && header.endsWith("]") &&
                    header.substring(1, header.length - 1) == name
                ) {
                    return index
                }
            }
            return -1
        }

        overrides.forEach { (section, values) ->
            val start = findSectionStart(section)
            if (start < 0) {
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
                lines.add("[$section]")
                values.forEach { (key, value) -> lines.add("$key = $value") }
                return@forEach
            }
            var end = lines.size
            for (index in start + 1 until lines.size) {
                val header = lines[index].trim()
                if (header.startsWith("[") && header.endsWith("]")) {
                    end = index
                    break
                }
            }
            val existingKeys = HashMap<String, Int>()
            for (index in start + 1 until end) {
                val line = lines[index]
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim()
                if (!existingKeys.containsKey(key)) existingKeys[key] = index
            }
            values.forEach { (key, value) ->
                val at = existingKeys[key]
                if (at != null) {
                    lines[at] = "$key = $value"
                } else {
                    // Keep the insertion inside the section: inserting at the
                    // same index would walk the key out of its section.
                    lines.add(end, "$key = $value")
                    end++
                }
            }
        }
        return lines.joinToString("\n")
    }
}
