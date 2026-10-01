// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.core

import android.content.Context
import androidx.annotation.StringRes
import com.sbro.emucored.R

/**
 * Localized text for the Azahar libretro options.
 *
 * The core itself only reports English labels. The application keeps the
 * localized wording in strings.xml, so the option model stays stable (keys and
 * persisted values never change) while the settings UIs render every entry in
 * the selected language. Values that are technical tokens, percentages or
 * product names intentionally fall back to the core label.
 */
object AzaharCoreOptionLocalization {
    data class Text(
        val label: String,
        val choices: List<AzaharCoreOptions.Choice>,
    )

    @StringRes
    private fun labelRes(key: String): Int? = when (key) {
        "citra_use_cpu_jit" -> R.string.settings_core_label_use_cpu_jit
        "citra_cpu_clock_percentage" -> R.string.settings_core_label_cpu_clock_percentage
        "citra_is_new_3ds" -> R.string.settings_core_label_is_new_3ds
        "citra_region_value" -> R.string.settings_core_label_region_value
        "citra_language_value" -> R.string.settings_core_label_language_value
        "citra_audio_emulation" -> R.string.settings_core_label_audio_emulation
        "citra_input_type" -> R.string.settings_core_label_input_type
        "citra_graphics_api" -> R.string.settings_core_label_graphics_api
        "citra_use_hw_shader" -> R.string.settings_core_label_use_hw_shader
        "citra_use_shader_jit" -> R.string.settings_core_label_use_shader_jit
        "citra_shaders_accurate_mul" -> R.string.settings_core_label_shaders_accurate_mul
        "citra_use_disk_shader_cache" -> R.string.settings_core_label_use_disk_shader_cache
        "citra_resolution_factor" -> R.string.settings_core_label_resolution_factor
        "citra_texture_filter" -> R.string.settings_core_label_texture_filter
        "citra_texture_sampling" -> R.string.settings_core_label_texture_sampling
        "citra_custom_textures" -> R.string.settings_core_label_custom_textures
        "citra_dump_textures" -> R.string.settings_core_label_dump_textures
        "citra_layout_option" -> R.string.settings_core_label_layout_option
        "citra_swap_screen" -> R.string.settings_core_label_swap_screen
        "citra_swap_screen_mode" -> R.string.settings_core_label_swap_screen_mode
        "citra_large_screen_proportion" -> R.string.settings_core_label_large_screen_proportion
        "citra_render_3d" -> R.string.settings_core_label_render_3d
        "citra_factor_3d" -> R.string.settings_core_label_factor_3d
        "citra_use_virtual_sd" -> R.string.settings_core_label_use_virtual_sd
        "citra_use_libretro_save_path" -> R.string.settings_core_label_use_libretro_save_path
        "citra_analog_function" -> R.string.settings_core_label_analog_function
        "citra_analog_deadzone" -> R.string.settings_core_label_analog_deadzone
        "citra_enable_mouse_touchscreen" -> R.string.settings_core_label_enable_mouse_touchscreen
        "citra_enable_touch_touchscreen" -> R.string.settings_core_label_enable_touch_touchscreen
        "citra_enable_touch_pointer_timeout" -> R.string.settings_core_label_enable_touch_pointer_timeout
        "citra_enable_motion" -> R.string.settings_core_label_enable_motion
        "citra_motion_sensitivity" -> R.string.settings_core_label_motion_sensitivity
        else -> null
    }

    @StringRes
    private fun choiceRes(value: String): Int? = when (value) {
        "enabled" -> R.string.settings_core_choice_enabled
        "disabled" -> R.string.settings_core_choice_disabled
        "Auto" -> R.string.settings_core_choice_auto
        "auto" -> R.string.settings_core_choice_auto
        "none" -> R.string.settings_core_choice_none
        "Top" -> R.string.settings_core_choice_top_screen
        "Bottom" -> R.string.settings_core_choice_bottom_screen
        "Toggle" -> R.string.settings_core_choice_toggle
        "Hold" -> R.string.settings_core_choice_hold
        else -> null
    }

    /** Resolve all visible text while preserving the option's raw values. */
    fun resolve(context: Context, option: AzaharCoreOptions.Option): Text {
        val label = labelRes(option.key)?.let(context::getString) ?: option.label
        val choices = option.choices.map { choice ->
            val localized = choiceRes(choice.value)?.let(context::getString)
            if (localized == null) choice else choice.copy(label = localized)
        }
        return Text(label = label, choices = choices)
    }

    /** Lookup helpers for callers that only have a persisted option key/value. */
    fun optionText(context: Context, key: String): Text? =
        AzaharCoreOptions.option(key)?.let { resolve(context, it) }

    fun localizedLabel(context: Context, key: String): String? =
        optionText(context, key)?.label

    fun localizedChoiceLabel(context: Context, key: String, value: String): String? =
        optionText(context, key)?.choices?.firstOrNull { it.value == value }?.label
}
