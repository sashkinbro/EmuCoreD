// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
//
// Generated from the vendored Azahar libretro core:
//   core/src/citra_libretro/core_settings.cpp (option_definitions)
// Keep this table in sync with that file; keys and persisted values must
// never change, only labels are allowed to be localized elsewhere.
package com.sbro.emucored.core

@Suppress("MagicNumber")
object AzaharCoreOptions {
    data class Choice(val value: String, val label: String)

    data class Option(
        val key: String,
        val label: String,
        val category: String,
        val choices: List<Choice>,
        val defaultValue: String,
    ) {
        /** The option's short key without the `citra_` prefix. */
        val shortKey: String get() = key.removePrefix("citra_")

        /** True for plain on/off options whose only values are enabled/disabled. */
        val isBooleanToggle: Boolean
            get() = choices.map { it.value.lowercase() }.toSet() == setOf("enabled", "disabled")

        private val enabledValue: String?
            get() = choices.firstOrNull { it.value.equals("enabled", ignoreCase = true) }?.value

        private val disabledValue: String?
            get() = choices.firstOrNull { it.value.equals("disabled", ignoreCase = true) }?.value

        /** Reads a persisted value with the enabled/disabled and true/false conventions. */
        fun isEnabled(current: String?): Boolean = current != null &&
            (current.equals(enabledValue, ignoreCase = true) || current.equals("true", ignoreCase = true))

        /** Value to persist for a toggle interaction. */
        fun valueForEnabled(enabled: Boolean): String = if (enabled) {
            enabledValue ?: "true"
        } else {
            disabledValue ?: "false"
        }
    }

    data class Category(val key: String, val label: String, val description: String)

    private val categoryList: List<Category> = listOf(
        Category("cpu", "CPU", "Settings related to CPU emulation performance and accuracy."),
        Category("system", "System", "Nintendo 3DS system configuration and region settings."),
        Category("audio", "Audio", "Audio emulation and microphone settings."),
        Category("graphics", "Graphics", "Graphics API, rendering, and visual enhancement settings."),
        Category("layout", "Layout", "Screen layout and display positioning options."),
        Category("storage", "Storage", "Save data and virtual SD card settings."),
        Category("input", "Input", "Controller and touchscreen input configuration."),
    )

    private val optionList: List<Option> = listOf(
        // CPU
        Option(
            key = "citra_use_cpu_jit",
            label = "CPU JIT",
            category = "cpu",
            choices = listOf(
                Choice("enabled", "Enabled"),
                Choice("disabled", "Disabled"),
            ),
            defaultValue = "enabled",
        ),
        Option(
            key = "citra_cpu_clock_percentage",
            label = "CPU Clock Speed",
            category = "cpu",
            choices = listOf(
                Choice("25", "25%"),
                Choice("50", "50%"),
                Choice("75", "75%"),
                Choice("100", "100% (Default)"),
                Choice("125", "125%"),
                Choice("150", "150%"),
                Choice("175", "175%"),
                Choice("200", "200%"),
                Choice("225", "225%"),
                Choice("250", "250%"),
                Choice("275", "275%"),
                Choice("300", "300%"),
                Choice("325", "325%"),
                Choice("350", "350%"),
                Choice("375", "375%"),
                Choice("400", "400%"),
            ),
            defaultValue = "100",
        ),

        // System
        Option(
            key = "citra_is_new_3ds",
            label = "System Model",
            category = "system",
            choices = listOf(
                Choice("New 3DS", "New 3DS"),
                Choice("Old 3DS", "Original 3DS"),
            ),
            defaultValue = "New 3DS",
        ),
        Option(
            key = "citra_region_value",
            label = "System Region",
            category = "system",
            choices = listOf(
                Choice("Auto", "Auto"),
                Choice("Japan", "Japan"),
                Choice("USA", "USA"),
                Choice("Europe", "Europe"),
                Choice("Australia", "Australia"),
                Choice("China", "China"),
                Choice("Korea", "Korea"),
                Choice("Taiwan", "Taiwan"),
            ),
            defaultValue = "Auto",
        ),
        Option(
            key = "citra_language_value",
            label = "System Language",
            category = "system",
            choices = listOf(
                Choice("English", "English"),
                Choice("Japanese", "Japanese"),
                Choice("French", "French"),
                Choice("Spanish", "Spanish"),
                Choice("German", "German"),
                Choice("Italian", "Italian"),
                Choice("Dutch", "Dutch"),
                Choice("Portuguese", "Portuguese"),
                Choice("Russian", "Russian"),
                Choice("Korean", "Korean"),
                Choice("Traditional Chinese", "Traditional Chinese"),
                Choice("Simplified Chinese", "Simplified Chinese"),
            ),
            defaultValue = "English",
        ),

        // Audio
        Option(
            key = "citra_audio_emulation",
            label = "Audio Emulation",
            category = "audio",
            choices = listOf(
                Choice("hle", "HLE (Fast)"),
                Choice("lle", "LLE (Accurate)"),
                Choice("lle_multithread", "LLE Multithreaded"),
            ),
            defaultValue = "hle",
        ),
        Option(
            key = "citra_input_type",
            label = "Microphone Input",
            category = "audio",
            choices = listOf(
                Choice("auto", "Auto"),
                Choice("none", "None"),
                Choice("static_noise", "Static Noise"),
                Choice("frontend", "Frontend"),
            ),
            defaultValue = "auto",
        ),

        // Graphics
        Option(
            key = "citra_graphics_api",
            label = "Graphics API",
            category = "graphics",
            choices = listOf(
                Choice("Auto", "Auto"),
                Choice("Vulkan", "Vulkan"),
                Choice("OpenGL", "OpenGL"),
                Choice("Software", "Software"),
            ),
            defaultValue = "auto",
        ),
        Option(
            key = "citra_use_hw_shader",
            label = "Hardware Shaders",
            category = "graphics",
            choices = listOf(
                Choice("enabled", "Enabled"),
                Choice("disabled", "Disabled"),
            ),
            defaultValue = "enabled",
        ),
        Option(
            key = "citra_use_shader_jit",
            label = "Shader JIT",
            category = "graphics",
            choices = listOf(
                Choice("enabled", "Enabled"),
                Choice("disabled", "Disabled"),
            ),
            defaultValue = "enabled",
        ),
        Option(
            key = "citra_shaders_accurate_mul",
            label = "Accurate Multiplication",
            category = "graphics",
            choices = listOf(
                Choice("enabled", "Enabled"),
                Choice("disabled", "Disabled"),
            ),
            defaultValue = "enabled",
        ),
        Option(
            key = "citra_use_disk_shader_cache",
            label = "Shader Cache",
            category = "graphics",
            choices = listOf(
                Choice("enabled", "Enabled"),
                Choice("disabled", "Disabled"),
            ),
            defaultValue = "enabled",
        ),
        Option(
            key = "citra_resolution_factor",
            label = "Internal Resolution",
            category = "graphics",
            choices = listOf(
                Choice("1", "1x (Native 400x240)"),
                Choice("2", "2x (800x480)"),
                Choice("3", "3x (1200x720)"),
                Choice("4", "4x (1600x960)"),
                Choice("5", "5x (2000x1200)"),
                Choice("6", "6x (2400x1440)"),
                Choice("7", "7x (2800x1680)"),
                Choice("8", "8x (3200x1920)"),
                Choice("9", "9x (3600x2160)"),
                Choice("10", "10x (4000x2400)"),
            ),
            defaultValue = "1",
        ),
        Option(
            key = "citra_texture_filter",
            label = "Texture Filter",
            category = "graphics",
            choices = listOf(
                Choice("none", "None"),
                Choice("Anime4K Ultrafast", "Anime4K Ultrafast"),
                Choice("Bicubic", "Bicubic"),
                Choice("ScaleForce", "ScaleForce"),
                Choice("xBRZ", "xBRZ"),
                Choice("MMPX", "MMPX"),
            ),
            defaultValue = "none",
        ),
        Option(
            key = "citra_texture_sampling",
            label = "Texture Sampling",
            category = "graphics",
            choices = listOf(
                Choice("GameControlled", "Game Controlled"),
                Choice("NearestNeighbor", "Nearest Neighbor"),
                Choice("Linear", "Linear"),
            ),
            defaultValue = "GameControlled",
        ),
        Option(
            key = "citra_custom_textures",
            label = "Custom Textures",
            category = "graphics",
            choices = listOf(
                Choice("enabled", "Enabled"),
                Choice("disabled", "Disabled"),
            ),
            defaultValue = "disabled",
        ),
        Option(
            key = "citra_dump_textures",
            label = "Dump Textures",
            category = "graphics",
            choices = listOf(
                Choice("enabled", "Enabled"),
                Choice("disabled", "Disabled"),
            ),
            defaultValue = "disabled",
        ),

        // Layout
        Option(
            key = "citra_layout_option",
            label = "Screen Layout",
            category = "layout",
            choices = listOf(
                Choice("default", "Default Top-Bottom"),
                Choice("single_screen", "Single Screen Only"),
                Choice("large_screen", "Large Screen, Small Screen"),
                Choice("side_by_side", "Side by Side"),
            ),
            defaultValue = "default",
        ),
        Option(
            key = "citra_swap_screen",
            label = "Prominent Screen",
            category = "layout",
            choices = listOf(
                Choice("Top", "Top Screen"),
                Choice("Bottom", "Bottom Screen"),
            ),
            defaultValue = "Top",
        ),
        Option(
            key = "citra_swap_screen_mode",
            label = "Swap Mode",
            category = "layout",
            choices = listOf(
                Choice("Toggle", "Toggle"),
                Choice("Hold", "Hold"),
            ),
            defaultValue = "Toggle",
        ),
        Option(
            key = "citra_large_screen_proportion",
            label = "Large Screen Proportion",
            category = "layout",
            choices = listOf(
                Choice("1.00", "1.00x (Equal Size)"),
                Choice("1.25", "1.25x"),
                Choice("1.50", "1.50x"),
                Choice("1.75", "1.75x"),
                Choice("2.00", "2.00x"),
                Choice("2.25", "2.25x"),
                Choice("2.50", "2.50x"),
                Choice("2.75", "2.75x"),
                Choice("3.00", "3.00x"),
                Choice("3.25", "3.25x"),
                Choice("3.50", "3.50x"),
                Choice("3.75", "3.75x"),
                Choice("4.00", "4.00x (Default)"),
                Choice("4.25", "4.25x"),
                Choice("4.50", "4.50x"),
                Choice("4.75", "4.75x"),
                Choice("5.00", "5.00x"),
                Choice("5.25", "5.25x"),
                Choice("5.50", "5.50x"),
                Choice("5.75", "5.75x"),
                Choice("6.00", "6.00x"),
            ),
            defaultValue = "4.00",
        ),
        Option(
            key = "citra_render_3d",
            label = "Stereo 3D Mode",
            category = "layout",
            choices = listOf(
                Choice("off", "Off (2D)"),
                Choice("side-by-side", "Side by Side"),
                Choice("side-by-side-full", "Side by Side (Full)"),
                Choice("anaglyph", "Anaglyph (Red/Cyan)"),
                Choice("interlaced", "Interlaced"),
                Choice("reverse-interlaced", "Reverse Interlaced"),
                Choice("cardboard", "Cardboard VR"),
            ),
            defaultValue = "off",
        ),
        Option(
            key = "citra_factor_3d",
            label = "Stereo 3D Depth",
            category = "layout",
            choices = listOf(
                Choice("0", "0%"),
                Choice("10", "10%"),
                Choice("20", "20%"),
                Choice("30", "30%"),
                Choice("40", "40%"),
                Choice("50", "50%"),
                Choice("60", "60%"),
                Choice("70", "70%"),
                Choice("80", "80%"),
                Choice("90", "90%"),
                Choice("100", "100%"),
            ),
            defaultValue = "0",
        ),

        // Storage
        Option(
            key = "citra_use_virtual_sd",
            label = "Virtual SD Card",
            category = "storage",
            choices = listOf(
                Choice("enabled", "Enabled"),
                Choice("disabled", "Disabled"),
            ),
            defaultValue = "enabled",
        ),
        Option(
            key = "citra_use_libretro_save_path",
            label = "Save Location",
            category = "storage",
            choices = listOf(
                Choice("LibRetro Default", "LibRetro Default"),
                Choice("Azahar Default", "Azahar Default"),
            ),
            defaultValue = "LibRetro Default",
        ),

        // Input
        Option(
            key = "citra_analog_function",
            label = "Right Analog Function",
            category = "input",
            choices = listOf(
                Choice("c_stick_and_touchscreen", "C-Stick and Touchscreen Pointer"),
                Choice("touchscreen_pointer", "Touchscreen Pointer"),
                Choice("c_stick", "C-Stick"),
            ),
            defaultValue = "c_stick_and_touchscreen",
        ),
        Option(
            key = "citra_analog_deadzone",
            label = "Analog Deadzone",
            category = "input",
            choices = listOf(
                Choice("0", "0%"),
                Choice("5", "5%"),
                Choice("10", "10%"),
                Choice("15", "15%"),
                Choice("20", "20%"),
                Choice("25", "25%"),
                Choice("30", "30%"),
                Choice("35", "35%"),
            ),
            defaultValue = "15",
        ),
        Option(
            key = "citra_enable_mouse_touchscreen",
            label = "Mouse Touchscreen",
            category = "input",
            choices = listOf(
                Choice("enabled", "Enabled"),
                Choice("disabled", "Disabled"),
            ),
            defaultValue = "enabled",
        ),
        Option(
            key = "citra_enable_touch_touchscreen",
            label = "Touch Support",
            category = "input",
            choices = listOf(
                Choice("enabled", "Enabled"),
                Choice("disabled", "Disabled"),
            ),
            defaultValue = "enabled",
        ),
        Option(
            key = "citra_enable_touch_pointer_timeout",
            label = "Touch Pointer Timeout",
            category = "input",
            choices = listOf(
                Choice("enabled", "Enabled"),
                Choice("disabled", "Disabled"),
            ),
            defaultValue = "enabled",
        ),
        Option(
            key = "citra_enable_motion",
            label = "Motion Support",
            category = "input",
            choices = listOf(
                Choice("enabled", "Enabled"),
                Choice("disabled", "Disabled"),
            ),
            defaultValue = "enabled",
        ),
        Option(
            key = "citra_motion_sensitivity",
            label = "Motion Sensitivity",
            category = "input",
            choices = listOf(
                Choice("0.1", "10%"),
                Choice("0.25", "25%"),
                Choice("0.5", "50%"),
                Choice("0.75", "75%"),
                Choice("1.0", "100%"),
                Choice("1.25", "125%"),
                Choice("1.5", "150%"),
                Choice("2.0", "200%"),
            ),
            defaultValue = "1.0",
        ),
    )

    private val optionsByKey: Map<String, Option> by lazy { optionList.associateBy { it.key } }

    /** Keys driven by dedicated app-level controls (renderer and upscale). */
    private val managedKeys = setOf(
        "citra_graphics_api",
        "citra_resolution_factor",
    )

    fun all(): List<Option> = optionList

    fun categories(): List<Category> = categoryList

    fun forCategory(categoryKey: String): List<Option> = optionList.filter { it.category == categoryKey }

    fun option(key: String): Option? = optionsByKey[key]

    fun isManagedKey(key: String): Boolean = key in managedKeys

    fun categoryLabel(categoryKey: String): String =
        categoryList.firstOrNull { it.key == categoryKey }?.label ?: categoryKey

    fun categoryDescription(categoryKey: String): String =
        categoryList.firstOrNull { it.key == categoryKey }?.description.orEmpty()

    /** Video tab: rendering and display options. */
    fun graphicsOptions(): List<Option> =
        (forCategory("graphics") + forCategory("layout"))
            .filterNot { isManagedKey(it.key) }

    /** System & performance tab: CPU, system and storage options. */
    fun emulationOptions(): List<Option> =
        (forCategory("cpu") + forCategory("system") + forCategory("storage"))
            .filterNot { isManagedKey(it.key) }

    /** Small set of system switches useful during play; detailed tuning stays in settings. */
    fun gameMenuEmulationOptions(): List<Option> = listOfNotNull(
        option("citra_cpu_clock_percentage"),
        option("citra_use_cpu_jit"),
    )

    /** Azahar has no network emulation options. */
    fun networkOptions(): List<Option> = emptyList()

    /** Controls tab: input options. */
    fun controlsOptions(): List<Option> =
        forCategory("input").filterNot { isManagedKey(it.key) }

    /** Audio tab: every option that targets audio output. */
    fun audioOptions(): List<Option> =
        forCategory("audio").filterNot { isManagedKey(it.key) }

    /** Graphics section of the in-game menu. */
    fun gameMenuGraphicsOptions(): List<Option> = listOfNotNull(
        option("citra_resolution_factor"),
        option("citra_texture_filter"),
        option("citra_texture_sampling"),
        option("citra_use_hw_shader"),
        option("citra_use_shader_jit"),
        option("citra_shaders_accurate_mul"),
        option("citra_use_disk_shader_cache"),
        option("citra_custom_textures"),
        option("citra_layout_option"),
        option("citra_swap_screen"),
        option("citra_swap_screen_mode"),
        option("citra_large_screen_proportion"),
        option("citra_render_3d"),
        option("citra_factor_3d"),
    )

    /** Controls section of the in-game menu. */
    fun gameMenuControlsOptions(): List<Option> = listOfNotNull(
        option("citra_analog_function"),
        option("citra_analog_deadzone"),
        option("citra_enable_touch_touchscreen"),
        option("citra_enable_motion"),
        option("citra_motion_sensitivity"),
    )
}
