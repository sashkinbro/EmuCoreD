package com.sbro.emucored.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sbro.emucored.R
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreOptionDefinition
import com.sbro.emucored.core.platform.CoreOptionSchema
import com.sbro.emucored.core.platform.SettingsCategory
import kotlin.math.roundToInt

/** Shared rows for global settings, game profiles, and the running game's menu. */
@Composable
fun ConsoleCoreOptionsPanel(
    platform: ConsolePlatform,
    category: SettingsCategory,
    values: Map<String, String>,
    onChange: (key: String, value: String) -> Unit,
    onReset: ((key: String) -> Unit)? = null,
    card: Boolean = true,
    afterOption: (@Composable (String) -> Unit)? = null
) {
    val options = CoreOptionSchema.options(platform).filter { it.category == category }
    if (options.isEmpty()) return
    SettingsSection(title = consolePlatformLabel(platform), card = card) {
        options.forEach { option ->
            val value = values[option.key]?.takeIf(option::accepts) ?: option.defaultValue
            val reset = { onReset?.invoke(option.key) ?: onChange(option.key, option.defaultValue) }
            val help = coreOptionHelpText(platform, option)
            when {
                option.values.toSet() == setOf("true", "false") || option.values.toSet() == setOf("ON", "OFF") ->
                    ToggleItem(
                        icon = Icons.Rounded.Tune, title = coreOptionTitle(option), subtitle = coreOptionRestartLabel(option),
                        checked = value == "true" || value == "ON",
                        onCheckedChange = { onChange(option.key, if (option.values.contains("true")) it.toString() else if (it) "ON" else "OFF") },
                        helpText = help, onResetToDefault = reset
                    )
                option.values.size > 20 -> SliderItem(
                    icon = Icons.Rounded.Tune, title = coreOptionTitle(option), subtitle = coreOptionRestartLabel(option),
                    value = option.values.indexOf(value).coerceAtLeast(0).toFloat(),
                    range = 0f..option.values.lastIndex.toFloat(), steps = option.values.size - 2,
                    onValueChange = { onChange(option.key, option.values[it.roundToInt().coerceIn(option.values.indices)]) },
                    valueLabel = { coreOptionNumericValue(option.key, option.values[it.roundToInt().coerceIn(option.values.indices)]) },
                    helpText = help, onResetToDefault = reset
                )
                else -> CoreStringChoiceSection(
                    title = coreOptionTitle(option), options = option.values.map { it to coreOptionValue(it, option.key) },
                    selectedValue = value, onSelect = { onChange(option.key, it) },
                    helpText = help, onResetToDefault = reset
                )
            }
            afterOption?.invoke(option.key)
        }
    }
}

@Composable
internal fun coreOptionRestartLabel(option: CoreOptionDefinition): String =
    if (option.restartRequired) stringResource(R.string.core_option_restart_required) else ""

@Composable
internal fun coreOptionHelpText(platform: ConsolePlatform, option: CoreOptionDefinition): String {
    val defaultLabel = if (option.values.size > 20) {
        coreOptionNumericValue(option.key, option.defaultValue)
    } else {
        coreOptionValue(option.defaultValue, option.key)
    }
    return listOf(
        stringResource(requireNotNull(coreOptionHelpResource(platform, option.key))),
        stringResource(R.string.core_option_help_recommended_default, defaultLabel),
        coreOptionRestartLabel(option)
    ).filter(String::isNotBlank).joinToString("\n\n")
}

internal fun coreOptionNumericValue(key: String, value: String): String = when {
    key == "volume" || key == "interference_volume" || key == "cpu_clock" || key.endsWith("overclock") -> "$value%"
    key == "speed" -> "${value}×"
    else -> value
}

@Composable
internal fun coreOptionTitle(option: CoreOptionDefinition): String = stringResource(when (option.key) {
    "n64_resolution" -> R.string.settings_upscale
    "n64_framebuffer" -> R.string.core_option_n64_framebuffer
    "n64_rsp" -> R.string.core_option_n64_rsp
    "widescreen_hack" -> R.string.dolphin_option_widescreen_hack
    "msaa" -> R.string.dolphin_option_msaa
    "anisotropy" -> R.string.dolphin_option_anisotropy
    "texture_filtering" -> R.string.dolphin_option_texture_filtering
    "shader_compilation" -> R.string.dolphin_option_shader_compilation
    "shader_cache" -> R.string.dolphin_option_shader_cache
    "wait_for_shaders" -> R.string.dolphin_option_wait_for_shaders
    "vsync" -> R.string.dolphin_option_vsync
    "backend_multithreading" -> R.string.dolphin_option_backend_multithreading
    "skip_efb_access" -> R.string.dolphin_option_skip_efb_access
    "efb_texture_only" -> R.string.dolphin_option_efb_texture_only
    "xfb_texture_only" -> R.string.dolphin_option_xfb_texture_only
    "defer_efb_copies" -> R.string.dolphin_option_defer_efb_copies
    "immediate_xfb" -> R.string.dolphin_option_immediate_xfb
    "skip_duplicate_xfbs" -> R.string.dolphin_option_skip_duplicate_xfbs
    "scaled_efb_copy" -> R.string.dolphin_option_scaled_efb_copy
    "ignore_format_changes" -> R.string.dolphin_option_ignore_format_changes
    "texture_cache_accuracy" -> R.string.dolphin_option_texture_cache_accuracy
    "gpu_texture_decoding" -> R.string.dolphin_option_gpu_texture_decoding
    "fast_depth" -> R.string.dolphin_option_fast_depth
    "disable_bbox" -> R.string.dolphin_option_disable_bbox
    "vertex_rounding" -> R.string.dolphin_option_vertex_rounding
    "disable_fog" -> R.string.dolphin_option_disable_fog
    "disable_copy_filter" -> R.string.dolphin_option_disable_copy_filter
    "force_true_color" -> R.string.dolphin_option_force_true_color
    "per_pixel_lighting" -> R.string.dolphin_option_per_pixel_lighting
    "hires_textures" -> R.string.dolphin_option_hires_textures
    "cache_hires_textures" -> R.string.dolphin_option_cache_hires_textures
    "save_texture_cache" -> R.string.dolphin_option_save_texture_cache
    "mmu" -> R.string.dolphin_option_mmu
    "accurate_cpu_cache" -> R.string.dolphin_option_accurate_cpu_cache
    "fprf" -> R.string.dolphin_option_fprf
    "accurate_nans" -> R.string.dolphin_option_accurate_nans
    "accurate_fmadds" -> R.string.dolphin_option_accurate_fmadds
    "fastmem" -> R.string.dolphin_option_fastmem
    "fast_disc" -> R.string.dolphin_option_fast_disc
    "cpu_clock" -> R.string.dolphin_option_cpu_clock
    "dsp_jit" -> R.string.dolphin_option_dsp_jit
    "dsp_thread" -> R.string.dolphin_option_dsp_thread
    "audio_backend" -> R.string.dolphin_option_audio_backend
    "audio_buffer_size" -> R.string.dolphin_option_audio_buffer_size
    "audio_preserve_pitch" -> R.string.dolphin_option_audio_preserve_pitch
    "audio_fill_gaps" -> R.string.dolphin_option_audio_fill_gaps
    "gc_language" -> R.string.dolphin_option_gc_language
    "wii_speaker" -> R.string.dolphin_option_wii_speaker
    "graphics_backend" -> R.string.settings_renderer
    "resolution" -> R.string.settings_upscale
    "audio_enabled" -> R.string.settings_audio_tab
    "volume" -> R.string.settings_audio_volume
    "aspect_ratio" -> R.string.settings_aspect_ratio
    "bsnes_cpu_overclock" -> R.string.settings_core_label_cpu_clock_percentage
    "cpu_backend" -> R.string.core_option_cpu_backend
    "dual_core" -> R.string.core_option_dual_core
    "dsp_hle" -> R.string.core_option_dsp_hle
    "speed" -> R.string.core_option_speed
    "wii_profile" -> R.string.core_option_wii_profile
    "video_filter" -> R.string.core_option_video_filter
    "color_correction" -> R.string.core_option_color_correction
    "unlimited_sprites", "bsnes_ppu_no_sprite_limit" -> R.string.core_option_unlimited_sprites
    "bsnes_ppu_fast" -> R.string.core_option_ppu_fast
    "bsnes_dsp_fast" -> R.string.core_option_dsp_fast
    "bsnes_entropy" -> R.string.core_option_entropy
    "bsnes_blur_emulation" -> R.string.core_option_blur
    "bsnes_ppu_show_overscan" -> R.string.core_option_overscan
    "bsnes_hotfixes" -> R.string.core_option_hotfixes
    "bsnes_mode7_scale" -> R.string.core_option_mode7_scale
    "bsnes_sa1_overclock" -> R.string.core_option_sa1_clock
    "bsnes_sfx_overclock" -> R.string.core_option_sfx_clock
    "bsnes_cpu_fastmath" -> R.string.core_option_fast_math
    "bsnes_ppu_deinterlace" -> R.string.core_option_deinterlace
    "bsnes_ppu_no_vram_blocking" -> R.string.core_option_no_vram_blocking
    "bsnes_mode7_perspective" -> R.string.core_option_mode7_perspective
    "bsnes_mode7_supersample" -> R.string.core_option_mode7_supersample
    "bsnes_mode7_mosaic" -> R.string.core_option_mode7_mosaic
    "bsnes_dsp_cubic" -> R.string.core_option_dsp_cubic
    "bsnes_dsp_echo_shadow" -> R.string.core_option_dsp_echo_shadow
    "bsnes_coprocessor_delayed_sync" -> R.string.core_option_delayed_sync
    "bsnes_coprocessor_prefer_hle" -> R.string.core_option_prefer_hle
    "bsnes_run_ahead_frames" -> R.string.core_option_run_ahead
    "bsnes_video_filter" -> R.string.core_option_video_signal
    "frameskip" -> R.string.core_option_frameskip
    "idle_optimization" -> R.string.core_option_idle_optimization
    "allow_opposing_directions" -> R.string.core_option_allow_opposing_directions
    "palette" -> R.string.core_option_palette
    "highpass_filter" -> R.string.core_option_highpass_filter
    "interference_volume" -> R.string.core_option_interference_volume
    else -> error("Missing core option title: ${option.key}")
})

@Composable
internal fun coreOptionValue(value: String, key: String): String = when {
    key == "resolution" -> "${value}×"
    key == "n64_resolution" -> n64ResolutionValue(value)
    key == "msaa" && value != "1" -> "${value}× MSAA"
    key == "msaa" -> stringResource(R.string.settings_core_choice_disabled)
    key == "anisotropy" && value != "default" -> "${value}×"
    key == "audio_buffer_size" -> "$value ms"
    else -> coreOptionChoiceValue(value)
}

/**
 * The N64 core stores fixed render sizes, but they are exact 1x..4x steps of the
 * console's 320x240 output. Present them the same way as every other console's
 * image-quality picker instead of leaking raw resolutions into the UI.
 */
private val N64ResolutionMultipliers = mapOf(
    "320x240" to 1,
    "640x480" to 2,
    "960x720" to 3,
    "1280x960" to 4
)

/** Exact 1x..4x step for a raw mupen64plus screen size, or null when unknown. */
internal fun n64ResolutionMultiplier(value: String): Int? = N64ResolutionMultipliers[value]

@Composable
private fun n64ResolutionValue(value: String): String {
    val multiplier = n64ResolutionMultiplier(value) ?: return value
    return if (multiplier == 1) stringResource(R.string.settings_upscale_native) else "${multiplier}×"
}

@Composable
private fun coreOptionChoiceValue(value: String): String = when (value) {
    "true", "ON" -> stringResource(R.string.settings_core_choice_enabled)
    "false", "OFF" -> stringResource(R.string.settings_core_choice_disabled)
    "default" -> stringResource(R.string.dolphin_choice_default)
    "stretch" -> stringResource(R.string.dolphin_choice_stretch)
    "specialized" -> stringResource(R.string.dolphin_choice_specialized)
    "hybrid" -> stringResource(R.string.dolphin_choice_hybrid)
    "exclusive" -> stringResource(R.string.dolphin_choice_exclusive)
    "skip" -> stringResource(R.string.dolphin_choice_skip)
    "safe" -> stringResource(R.string.dolphin_choice_safe)
    "balanced" -> stringResource(R.string.dolphin_choice_balanced)
    "fast" -> stringResource(R.string.dolphin_choice_fast)
    "cached_interpreter" -> stringResource(R.string.dolphin_choice_cached_interpreter)
    "english" -> stringResource(R.string.dolphin_choice_english)
    "german" -> stringResource(R.string.dolphin_choice_german)
    "french" -> stringResource(R.string.dolphin_choice_french)
    "spanish" -> stringResource(R.string.dolphin_choice_spanish)
    "italian" -> stringResource(R.string.dolphin_choice_italian)
    "dutch" -> stringResource(R.string.dolphin_choice_dutch)
    "vulkan" -> stringResource(R.string.settings_renderer_vulkan)
    "auto" -> stringResource(R.string.settings_aspect_ratio_auto)
    "pixel" -> "1:1"
    "opengl" -> stringResource(R.string.settings_renderer_opengl)
    "jit" -> "JIT"
    "hle" -> "HLE"
    "parallel" -> "ParaLLEl RSP"
    "cxd4" -> "CXD4"
    "interpreter" -> stringResource(R.string.core_option_interpreter)
    "nearest" -> stringResource(R.string.core_option_nearest)
    "linear" -> stringResource(R.string.core_option_linear)
    "remote" -> "Wii Remote"
    "nunchuk" -> "Wii Remote + Nunchuk"
    "classic" -> "Wii Classic Controller"
    "Low" -> stringResource(R.string.core_option_low)
    "High" -> stringResource(R.string.core_option_high)
    "off" -> stringResource(R.string.settings_core_choice_disabled)
    "accurate" -> stringResource(R.string.core_option_hardware)
    "remove_dc" -> "DC = 0"
    "dmg" -> "Game Boy"
    "pocket" -> "Game Boy Pocket"
    "light" -> "Game Boy Light"
    "grey" -> stringResource(R.string.core_option_greyscale)
    "detect" -> stringResource(R.string.core_option_detect)
    "remove" -> stringResource(R.string.core_option_remove)
    "ignore" -> stringResource(R.string.core_option_ignore)
    "None", "OFF" -> stringResource(R.string.settings_core_choice_disabled)
    else -> value
}
