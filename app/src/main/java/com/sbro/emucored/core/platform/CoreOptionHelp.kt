package com.sbro.emucored.core.platform

/** Explicit presentation metadata; intentionally independent of Android resources.
 * Text follows the native adapter bindings, not similarly named frontend options.
 * DS and 3DS retain their existing legacy help registries.
 */
object CoreOptionHelp {
    fun entries(platform: ConsolePlatform): Map<String, String> = CoreOptionSchema.options(platform)
        .mapNotNull { option -> resourceName(platform, option.key)?.let { option.key to it } }.toMap()

    private fun keys(value: String) = value.trim().split(Regex("\\s+")).toSet()
    private val frame = keys("video_filter audio_enabled aspect_ratio")
    private val handheld = keys("highpass_filter interference_volume")
    private val gba = keys("frameskip volume idle_optimization allow_opposing_directions")
    private val dolphin = keys("""
        graphics_backend resolution aspect_ratio widescreen_hack msaa anisotropy texture_filtering
        shader_compilation shader_cache wait_for_shaders vsync backend_multithreading skip_efb_access
        efb_texture_only xfb_texture_only defer_efb_copies immediate_xfb skip_duplicate_xfbs scaled_efb_copy
        ignore_format_changes texture_cache_accuracy gpu_texture_decoding fast_depth disable_bbox
        vertex_rounding disable_fog disable_copy_filter force_true_color per_pixel_lighting hires_textures
        cache_hires_textures save_texture_cache cpu_backend dual_core mmu accurate_cpu_cache fprf
        accurate_nans accurate_fmadds fastmem fast_disc cpu_clock dsp_hle dsp_jit dsp_thread
        audio_backend audio_enabled volume audio_buffer_size audio_preserve_pitch audio_fill_gaps speed
    """)
    private val snes = keys("""
        bsnes_ppu_fast bsnes_dsp_fast bsnes_entropy bsnes_blur_emulation bsnes_ppu_no_sprite_limit
        bsnes_ppu_show_overscan bsnes_hotfixes bsnes_mode7_scale bsnes_cpu_overclock bsnes_sa1_overclock
        bsnes_sfx_overclock bsnes_cpu_fastmath bsnes_ppu_deinterlace bsnes_ppu_no_vram_blocking
        bsnes_mode7_perspective bsnes_mode7_supersample bsnes_mode7_mosaic bsnes_dsp_cubic
        bsnes_dsp_echo_shadow bsnes_coprocessor_delayed_sync bsnes_coprocessor_prefer_hle
        bsnes_run_ahead_frames bsnes_video_filter
    """)

    fun resourceName(platform: ConsolePlatform, key: String): String? {
        val available = when (platform) {
            ConsolePlatform.NDS, ConsolePlatform.N3DS -> emptySet()
            ConsolePlatform.GAMECUBE -> dolphin + "gc_language"
            ConsolePlatform.WII -> dolphin + setOf("wii_profile", "wii_speaker")
            ConsolePlatform.GBA -> frame + gba
            ConsolePlatform.GB -> frame + handheld + "palette"
            ConsolePlatform.GBC -> frame + handheld + "color_correction"
            ConsolePlatform.NES -> frame + "unlimited_sprites"
            ConsolePlatform.SNES -> frame + snes
        }
        if (key !in available) return null
        val variant = if (key == "aspect_ratio") {
            if (platform == ConsolePlatform.GAMECUBE || platform == ConsolePlatform.WII) "aspect_ratio_dolphin"
            else "aspect_ratio_frame"
        } else key
        return "core_option_help_$variant"
    }
}
