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
        "use_cpu_jit" -> R.string.settings_core_label_use_cpu_jit
        "cpu_clock_percentage" -> R.string.settings_core_label_cpu_clock_percentage
        "is_new_3ds" -> R.string.settings_core_label_is_new_3ds
        "region_value" -> R.string.settings_core_label_region_value
        "language" -> R.string.settings_core_label_language_value
        "apply_region_free_patch" -> R.string.settings_core_label_region_free_patch
        "audio_emulation" -> R.string.settings_core_label_audio_emulation
        "enable_audio_stretching" -> R.string.settings_core_label_audio_stretching
        "enable_realtime_audio" -> R.string.settings_core_label_realtime_audio
        "volume" -> R.string.settings_core_label_volume
        "input_type" -> R.string.settings_core_label_input_type
        "graphics_api" -> R.string.settings_core_label_graphics_api
        "use_hw_shader" -> R.string.settings_core_label_use_hw_shader
        "use_shader_jit" -> R.string.settings_core_label_use_shader_jit
        "shaders_accurate_mul" -> R.string.settings_core_label_shaders_accurate_mul
        "use_disk_shader_cache" -> R.string.settings_core_label_use_disk_shader_cache
        "use_vsync" -> R.string.settings_core_label_use_vsync
        "use_skip_duplicate_frames" -> R.string.settings_core_label_skip_duplicate_frames
        "resolution_factor" -> R.string.settings_core_label_resolution_factor
        "texture_filter" -> R.string.settings_core_label_texture_filter
        "texture_sampling" -> R.string.settings_core_label_texture_sampling
        "custom_textures" -> R.string.settings_core_label_custom_textures
        "dump_textures" -> R.string.settings_core_label_dump_textures
        "layout_option" -> R.string.settings_core_label_layout_option
        "swap_screen" -> R.string.settings_core_label_swap_screen
        "aspect_ratio" -> R.string.settings_core_label_aspect_ratio
        "use_virtual_sd" -> R.string.settings_core_label_use_virtual_sd
        "motion_device" -> R.string.settings_core_label_motion_device
        "touch_device" -> R.string.settings_core_label_touch_device
        "udp_pad_index" -> R.string.settings_core_label_udp_pad_index
        else -> null
    }

    @StringRes
    private fun choiceRes(value: String): Int? = when (value) {
        "true" -> R.string.settings_core_choice_enabled
        "false" -> R.string.settings_core_choice_disabled
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
