package com.sbro.emucored.core.platform

/** The values passed to JNI, kept separate from translated presentation labels. */
data class CoreOptionDefinition(
    val key: String,
    val category: SettingsCategory,
    val defaultValue: String,
    val values: List<String>,
    val restartRequired: Boolean = false
) {
    fun accepts(value: String): Boolean = value in values
}

/** Only options implemented by EmuCoreD's adapters belong here. Azahar/melonDS use their legacy stores. */
object CoreOptionSchema {
    private fun option(key: String, category: SettingsCategory, default: String, vararg values: String,
                       restart: Boolean = false) = CoreOptionDefinition(key, category, default, values.toList(), restart)
    private fun toggle(key: String, category: SettingsCategory, default: Boolean = true, restart: Boolean = false) =
        option(key, category, default.toString(), "true", "false", restart = restart)
    private val audio = toggle("audio_enabled", SettingsCategory.Audio)
    private val filter = option("video_filter", SettingsCategory.Graphics, "nearest", "nearest", "linear")
    private val frame = listOf(filter, audio,
        option("aspect_ratio", SettingsCategory.Graphics, "auto", "auto", "pixel", "4:3", "16:9"))
    private val handheldAudio = listOf(
        option("highpass_filter", SettingsCategory.Audio, "accurate", "off", "accurate", "remove_dc"),
        CoreOptionDefinition("interference_volume", SettingsCategory.Audio, "0", (0..100).map(Int::toString))
    )
    // Matches the pinned Dolphin Config bindings. Boot-only entries are restarted by the host.
    private val dolphin = listOf(
        option("graphics_backend", SettingsCategory.Graphics, "vulkan", "vulkan", "opengl", restart = true),
        CoreOptionDefinition("resolution", SettingsCategory.Graphics, "1", (1..12).map(Int::toString)),
        option("aspect_ratio", SettingsCategory.Graphics, "auto", "auto", "4:3", "16:9", "stretch", "pixel"),
        toggle("widescreen_hack", SettingsCategory.Graphics, false),
        option("msaa", SettingsCategory.Graphics, "1", "1", "2", "4", "8"),
        option("anisotropy", SettingsCategory.Graphics, "default", "default", "1", "2", "4", "8", "16"),
        option("texture_filtering", SettingsCategory.Graphics, "default", "default", "nearest", "linear"),
        option("shader_compilation", SettingsCategory.Graphics, "specialized", "specialized", "hybrid", "exclusive", "skip", restart = true),
        toggle("shader_cache", SettingsCategory.Graphics, restart = true),
        toggle("wait_for_shaders", SettingsCategory.Graphics, false, restart = true),
        toggle("vsync", SettingsCategory.Graphics, false),
        toggle("backend_multithreading", SettingsCategory.Graphics, restart = true),
        toggle("skip_efb_access", SettingsCategory.Graphics),
        toggle("efb_texture_only", SettingsCategory.Graphics),
        toggle("xfb_texture_only", SettingsCategory.Graphics),
        toggle("defer_efb_copies", SettingsCategory.Graphics),
        toggle("immediate_xfb", SettingsCategory.Graphics, false),
        toggle("skip_duplicate_xfbs", SettingsCategory.Graphics),
        toggle("scaled_efb_copy", SettingsCategory.Graphics),
        toggle("ignore_format_changes", SettingsCategory.Graphics),
        option("texture_cache_accuracy", SettingsCategory.Graphics, "balanced", "safe", "balanced", "fast"),
        toggle("gpu_texture_decoding", SettingsCategory.Graphics, false),
        toggle("fast_depth", SettingsCategory.Graphics),
        toggle("disable_bbox", SettingsCategory.Graphics),
        toggle("vertex_rounding", SettingsCategory.Graphics, false),
        toggle("disable_fog", SettingsCategory.Graphics, false),
        toggle("disable_copy_filter", SettingsCategory.Graphics),
        toggle("force_true_color", SettingsCategory.Graphics),
        toggle("per_pixel_lighting", SettingsCategory.Graphics, false),
        toggle("hires_textures", SettingsCategory.Graphics, false),
        toggle("cache_hires_textures", SettingsCategory.Graphics, false),
        toggle("save_texture_cache", SettingsCategory.Graphics),
        option("cpu_backend", SettingsCategory.Emulation, "jit", "jit", "cached_interpreter", "interpreter", restart = true),
        toggle("dual_core", SettingsCategory.Emulation, restart = true),
        toggle("mmu", SettingsCategory.Emulation, false, restart = true),
        toggle("accurate_cpu_cache", SettingsCategory.Emulation, false, restart = true),
        toggle("fprf", SettingsCategory.Emulation, false, restart = true),
        toggle("accurate_nans", SettingsCategory.Emulation, false, restart = true),
        toggle("accurate_fmadds", SettingsCategory.Emulation, false, restart = true),
        toggle("fastmem", SettingsCategory.Emulation, restart = true),
        toggle("fast_disc", SettingsCategory.Emulation, false, restart = true),
        CoreOptionDefinition("cpu_clock", SettingsCategory.Emulation, "100", (10..400 step 10).map(Int::toString), restartRequired = true),
        toggle("dsp_hle", SettingsCategory.Audio, restart = true),
        toggle("dsp_jit", SettingsCategory.Audio, restart = true),
        toggle("dsp_thread", SettingsCategory.Audio, false, restart = true),
        option("audio_backend", SettingsCategory.Audio, "cubeb", "cubeb", "opensles", restart = true),
        audio,
        CoreOptionDefinition("volume", SettingsCategory.Audio, "100", (0..100).map(Int::toString)),
        CoreOptionDefinition("audio_buffer_size", SettingsCategory.Audio, "80", (20..200 step 10).map(Int::toString)),
        toggle("audio_preserve_pitch", SettingsCategory.Audio, false),
        toggle("audio_fill_gaps", SettingsCategory.Audio),
        CoreOptionDefinition("speed", SettingsCategory.Emulation, "1.0", (1..40).map { "${it / 10}.${it % 10}" })
    )
    fun options(platform: ConsolePlatform): List<CoreOptionDefinition> = when (platform) {
        ConsolePlatform.N3DS, ConsolePlatform.NDS -> emptyList()
        ConsolePlatform.N64 -> listOf(
            option("n64_resolution", SettingsCategory.Graphics, "640x480", "320x240", "640x480", "960x720", "1280x960", restart = true),
            option("aspect_ratio", SettingsCategory.Graphics, "auto", "auto", "4:3", "16:9"),
            toggle("n64_framebuffer", SettingsCategory.Graphics, restart = true),
            option("cpu_backend", SettingsCategory.Emulation, "jit", "jit", "cached_interpreter", "interpreter", restart = true),
            option("n64_rsp", SettingsCategory.Emulation, "hle", "hle", "parallel", "cxd4", restart = true),
            audio
        )
        ConsolePlatform.GAMECUBE -> dolphin + option("gc_language", SettingsCategory.Emulation, "english", "english", "german", "french", "spanish", "italian", "dutch", restart = true)
        ConsolePlatform.WII -> dolphin + listOf(
            option("wii_profile", SettingsCategory.Controls, "nunchuk", "remote", "nunchuk", "classic", restart = true),
            toggle("wii_speaker", SettingsCategory.Audio, restart = true)
        )
        ConsolePlatform.GBA -> frame + listOf(
            CoreOptionDefinition("frameskip", SettingsCategory.Emulation, "0", (0..10).map(Int::toString)),
            CoreOptionDefinition("volume", SettingsCategory.Audio, "100", (0..100).map(Int::toString)),
            option("idle_optimization", SettingsCategory.Emulation, "detect", "detect", "remove", "ignore"),
            toggle("allow_opposing_directions", SettingsCategory.Controls, default = false)
        )
        ConsolePlatform.GB -> frame + handheldAudio + option("palette", SettingsCategory.Graphics, "dmg", "dmg", "grey", "pocket", "light")
        ConsolePlatform.GBC -> frame + handheldAudio + toggle("color_correction", SettingsCategory.Graphics)
        ConsolePlatform.NES -> frame + toggle("unlimited_sprites", SettingsCategory.Graphics, default = false)
        ConsolePlatform.SNES -> frame + listOf(
            option("bsnes_ppu_fast", SettingsCategory.Emulation, "ON", "ON", "OFF"),
            option("bsnes_dsp_fast", SettingsCategory.Audio, "ON", "ON", "OFF"),
            option("bsnes_entropy", SettingsCategory.Emulation, "Low", "Low", "High", "None"),
            option("bsnes_blur_emulation", SettingsCategory.Graphics, "OFF", "ON", "OFF"),
            option("bsnes_ppu_no_sprite_limit", SettingsCategory.Graphics, "OFF", "ON", "OFF"),
            option("bsnes_ppu_show_overscan", SettingsCategory.Graphics, "OFF", "ON", "OFF"),
            option("bsnes_hotfixes", SettingsCategory.Emulation, "OFF", "ON", "OFF"),
            CoreOptionDefinition("bsnes_mode7_scale", SettingsCategory.Graphics, "1x", (1..8).map { "${it}x" }),
            CoreOptionDefinition("bsnes_cpu_overclock", SettingsCategory.Emulation, "100", (10..400 step 10).map(Int::toString)),
            CoreOptionDefinition("bsnes_sa1_overclock", SettingsCategory.Emulation, "100", (10..400 step 10).map(Int::toString)),
            CoreOptionDefinition("bsnes_sfx_overclock", SettingsCategory.Emulation, "100", (10..800 step 10).map(Int::toString)),
            option("bsnes_cpu_fastmath", SettingsCategory.Emulation, "OFF", "ON", "OFF"),
            option("bsnes_ppu_deinterlace", SettingsCategory.Graphics, "ON", "ON", "OFF"),
            option("bsnes_ppu_no_vram_blocking", SettingsCategory.Graphics, "OFF", "ON", "OFF"),
            option("bsnes_mode7_perspective", SettingsCategory.Graphics, "ON", "ON", "OFF"),
            option("bsnes_mode7_supersample", SettingsCategory.Graphics, "OFF", "ON", "OFF"),
            option("bsnes_mode7_mosaic", SettingsCategory.Graphics, "ON", "ON", "OFF"),
            option("bsnes_dsp_cubic", SettingsCategory.Audio, "OFF", "ON", "OFF"),
            option("bsnes_dsp_echo_shadow", SettingsCategory.Audio, "OFF", "ON", "OFF"),
            option("bsnes_coprocessor_delayed_sync", SettingsCategory.Emulation, "ON", "ON", "OFF"),
            option("bsnes_coprocessor_prefer_hle", SettingsCategory.Emulation, "ON", "ON", "OFF"),
            option("bsnes_run_ahead_frames", SettingsCategory.Emulation, "OFF", "OFF", "1", "2", "3", "4"),
            option("bsnes_video_filter", SettingsCategory.Graphics, "None", "None", "NTSC (RF)", "NTSC (Composite)", "NTSC (S-Video)", "NTSC (RGB)")
        )
    }

    fun normalize(platform: ConsolePlatform, values: Map<String, String>): Map<String, String> =
        options(platform).mapNotNull { option ->
            values[option.key]?.takeIf(option::accepts)?.let { option.key to it }
        }.toMap()

    /** Invalid overrides never discard a valid global setting. Unrecognized keys cannot cross core boundaries. */
    fun resolve(platform: ConsolePlatform, global: Map<String, String> = emptyMap(),
                game: Map<String, String> = emptyMap()): Map<String, String> =
        options(platform).associate { it.key to it.defaultValue } + normalize(platform, global) + normalize(platform, game)
}
