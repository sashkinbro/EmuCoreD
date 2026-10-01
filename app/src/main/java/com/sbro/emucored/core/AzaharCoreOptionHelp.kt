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
    "citra_use_cpu_jit" -> R.string.settings_help_core_use_cpu_jit
    "citra_cpu_clock_percentage" -> R.string.settings_help_core_cpu_clock_percentage
    "citra_is_new_3ds" -> R.string.settings_help_core_is_new_3ds
    "citra_region_value" -> R.string.settings_help_core_region_value
    "citra_language_value" -> R.string.settings_help_core_language_value
    "citra_audio_emulation" -> R.string.settings_help_core_audio_emulation
    "citra_input_type" -> R.string.settings_help_core_input_type
    "citra_graphics_api" -> R.string.settings_help_core_graphics_api
    "citra_use_hw_shader" -> R.string.settings_help_core_use_hw_shader
    "citra_use_shader_jit" -> R.string.settings_help_core_use_shader_jit
    "citra_shaders_accurate_mul" -> R.string.settings_help_core_shaders_accurate_mul
    "citra_use_disk_shader_cache" -> R.string.settings_help_core_use_disk_shader_cache
    "citra_resolution_factor" -> R.string.settings_help_core_resolution_factor
    "citra_texture_filter" -> R.string.settings_help_core_texture_filter
    "citra_texture_sampling" -> R.string.settings_help_core_texture_sampling
    "citra_custom_textures" -> R.string.settings_help_core_custom_textures
    "citra_dump_textures" -> R.string.settings_help_core_dump_textures
    "citra_layout_option" -> R.string.settings_help_core_layout_option
    "citra_swap_screen" -> R.string.settings_help_core_swap_screen
    "citra_swap_screen_mode" -> R.string.settings_help_core_swap_screen_mode
    "citra_large_screen_proportion" -> R.string.settings_help_core_large_screen_proportion
    "citra_render_3d" -> R.string.settings_help_core_render_3d
    "citra_factor_3d" -> R.string.settings_help_core_factor_3d
    "citra_use_virtual_sd" -> R.string.settings_help_core_use_virtual_sd
    "citra_use_libretro_save_path" -> R.string.settings_help_core_use_libretro_save_path
    "citra_analog_function" -> R.string.settings_help_core_analog_function
    "citra_analog_deadzone" -> R.string.settings_help_core_analog_deadzone
    "citra_enable_mouse_touchscreen" -> R.string.settings_help_core_enable_mouse_touchscreen
    "citra_enable_touch_touchscreen" -> R.string.settings_help_core_enable_touch_touchscreen
    "citra_enable_touch_pointer_timeout" -> R.string.settings_help_core_enable_touch_pointer_timeout
    "citra_enable_motion" -> R.string.settings_help_core_enable_motion
    "citra_motion_sensitivity" -> R.string.settings_help_core_motion_sensitivity
    else -> null
}
