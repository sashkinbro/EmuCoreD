// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import androidx.annotation.StringRes
import com.sbro.emucored.R

/**
 * Help hints for the built-in Azahar core options. The hint text itself lives
 * in the strings.xml resources of every supported locale like every other
 * setting hint; this table only maps the stable option key to its resource.
 */
@StringRes
fun azaharCoreOptionHelpRes(key: String): Int? = when (key) {
    "use_cpu_jit" -> R.string.settings_help_core_use_cpu_jit
    "cpu_clock_percentage" -> R.string.settings_help_core_cpu_clock_percentage
    "is_new_3ds" -> R.string.settings_help_core_is_new_3ds
    "region_value" -> R.string.settings_help_core_region_value
    "language" -> R.string.settings_help_core_language_value
    "apply_region_free_patch" -> R.string.settings_help_core_region_free_patch
    "audio_emulation" -> R.string.settings_help_core_audio_emulation
    "enable_audio_stretching" -> R.string.settings_help_core_audio_stretching
    "enable_realtime_audio" -> R.string.settings_help_core_realtime_audio
    "volume" -> R.string.settings_help_core_volume
    "input_type" -> R.string.settings_help_core_input_type
    "graphics_api" -> R.string.settings_help_core_graphics_api
    "use_hw_shader" -> R.string.settings_help_core_use_hw_shader
    "use_shader_jit" -> R.string.settings_help_core_use_shader_jit
    "shaders_accurate_mul" -> R.string.settings_help_core_shaders_accurate_mul
    "use_disk_shader_cache" -> R.string.settings_help_core_use_disk_shader_cache
    "use_vsync" -> R.string.settings_help_core_use_vsync
    "use_skip_duplicate_frames" -> R.string.settings_help_core_skip_duplicate_frames
    "resolution_factor" -> R.string.settings_help_core_resolution_factor
    "texture_filter" -> R.string.settings_help_core_texture_filter
    "texture_sampling" -> R.string.settings_help_core_texture_sampling
    "custom_textures" -> R.string.settings_help_core_custom_textures
    "dump_textures" -> R.string.settings_help_core_dump_textures
    "layout_option" -> R.string.settings_help_core_layout_option
    "swap_screen" -> R.string.settings_help_core_swap_screen
    "aspect_ratio" -> R.string.settings_help_core_aspect_ratio
    "use_virtual_sd" -> R.string.settings_help_core_use_virtual_sd
    "motion_device" -> R.string.settings_help_core_motion_device
    "touch_device" -> R.string.settings_help_core_touch_device
    "udp_pad_index" -> R.string.settings_help_core_udp_pad_index
    else -> null
}
