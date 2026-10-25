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
    "cpu_clock_percentage" -> R.string.legacy_help_3ds_cpu_clock_percentage
    "is_new_3ds" -> R.string.legacy_help_3ds_is_new_3ds
    "region_value" -> R.string.legacy_help_3ds_region_value
    "language" -> R.string.legacy_help_3ds_language
    "apply_region_free_patch" -> R.string.legacy_help_3ds_apply_region_free_patch
    "audio_emulation" -> R.string.settings_help_core_audio_emulation
    "enable_audio_stretching" -> R.string.legacy_help_3ds_enable_audio_stretching
    "enable_realtime_audio" -> R.string.legacy_help_3ds_enable_realtime_audio
    "volume" -> R.string.legacy_help_3ds_volume
    "input_type" -> R.string.legacy_help_3ds_input_type
    "graphics_api" -> R.string.legacy_help_3ds_graphics_api
    "use_hw_shader" -> R.string.settings_help_core_use_hw_shader
    "use_shader_jit" -> R.string.settings_help_core_use_shader_jit
    "shaders_accurate_mul" -> R.string.legacy_help_3ds_shaders_accurate_mul
    "use_disk_shader_cache" -> R.string.legacy_help_3ds_use_disk_shader_cache
    "use_vsync" -> R.string.legacy_help_3ds_use_vsync
    "use_skip_duplicate_frames" -> R.string.legacy_help_3ds_use_skip_duplicate_frames
    "resolution_factor" -> R.string.legacy_help_3ds_resolution_factor
    "texture_filter" -> R.string.legacy_help_3ds_texture_filter
    "texture_sampling" -> R.string.settings_help_core_texture_sampling
    "custom_textures" -> R.string.settings_help_core_custom_textures
    "dump_textures" -> R.string.settings_help_core_dump_textures
    "layout_option" -> R.string.legacy_help_3ds_layout_option
    "swap_screen" -> R.string.legacy_help_3ds_swap_screen
    "aspect_ratio" -> R.string.legacy_help_3ds_aspect_ratio
    "use_virtual_sd" -> R.string.legacy_help_3ds_use_virtual_sd
    "motion_device" -> R.string.legacy_help_3ds_motion_device
    "touch_device" -> R.string.legacy_help_3ds_touch_device
    "udp_pad_index" -> R.string.legacy_help_3ds_udp_pad_index
    else -> null
}
