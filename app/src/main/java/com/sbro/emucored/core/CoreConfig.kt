// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

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
        val mediatekAngleOpenGl: Boolean
    )

    fun apply(values: Values) {
        val root = CoreRuntime.userDirectory()
        if (root.isBlank()) return
        val configFile = File(root, CONFIG_FILE_NAME)
        // Start from the core's own default file so nothing is lost when the
        // user has never opened the settings screen.
        runCatching { EmuCoreNative.createConfigFile() }
        val existing = runCatching {
            if (configFile.isFile) configFile.readText() else ""
        }.getOrDefault("")

        val overrides = linkedMapOf(
            "Core" to mapOf(
                "use_cpu_jit" to "true",
                "cpu_clock_percentage" to "100"
            ),
            "Renderer" to mapOf(
                "graphics_api" to RendererDefaults.coreRendererName(
                    RendererDefaults.toCoreRenderer(values.renderer)
                ),
                "resolution_factor" to Math.round(values.resolutionFactor).coerceIn(1, 10).toString(),
                "use_hw_shader" to "true",
                "use_shader_jit" to "true",
                "shaders_accurate_mul" to "true",
                "use_disk_shader_cache" to "true",
                "use_vsync" to values.vSyncEnabled.toString(),
                "use_frame_limit" to values.frameLimitEnabled.toString(),
                "frame_limit" to "100",
                "turbo_limit" to values.fastForwardSpeed.coerceIn(1f, 5f).toInt().toString(),
                "aspect_ratio" to aspectRatioValue(values.aspectRatio),
                "texture_filter" to "false",
                "texture_sampling" to "0",
                "custom_textures" to values.customTextures.toString(),
                "async_shader_compilation" to "true",
                "spirv_shader_gen" to "true"
            ),
            "Layout" to mapOf(
                "layout_option" to "0",
                "swap_screen" to "false",
                "upright_screen" to "false"
            ),
            "Audio" to mapOf(
                "audio_emulation" to "hle",
                "enable_audio_stretching" to "true",
                "enable_realtime_audio" to "true",
                "volume" to values.audioVolume.toString()
            ),
            "Utility" to mapOf(
                "use_virtual_sd" to "true",
                "is_new_3ds" to "true"
            ),
            "System" to mapOf(
                "region_value" to "-1",
                "language" to "1"
            ),
            "Miscellaneous" to mapOf(
                "check_for_update_on_start" to "false"
            )
        )

        val merged = mergeSectionValues(existing, overrides)
        runCatching {
            configFile.parentFile?.mkdirs()
            configFile.writeText(merged)
        }
        runCatching { EmuCoreNative.reloadSettings() }
    }

    /** EmuCoreD aspect choice -> core aspect ratio value (1 auto, 2 4:3, 3 16:9). */
    private fun aspectRatioValue(appValue: Int): String = when (appValue) {
        2 -> "2"
        3 -> "3"
        else -> "1"
    }

    /**
     * Rewrites the values of [overrides] inside [existing], keeping every other
     * key and comment untouched, and appends sections that are missing.
     */
    private fun mergeSectionValues(existing: String, overrides: Map<String, Map<String, String>>): String {
        val lines = existing.lines().toMutableList()
        val sectionIndex = HashMap<String, Int>()
        lines.forEachIndexed { index, line ->
            val header = line.trim()
            if (header.startsWith("[") && header.endsWith("]")) {
                val name = header.removeSurrounding("[", "]")
                if (!sectionIndex.containsKey(name)) sectionIndex[name] = index
            }
        }

        overrides.forEach { (section, values) ->
            val start = sectionIndex[section]
            if (start == null) {
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
                    lines.add(end, "$key = $value")
                }
            }
        }
        return lines.joinToString("\n")
    }
}
