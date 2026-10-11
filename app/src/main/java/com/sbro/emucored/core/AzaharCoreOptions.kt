// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
//
// Generated from the vendored Azahar core's own configuration:
//   core/src/common/setting_keys.h.in, core/src/android/app/src/main/jni/default_ini.h,
//   core/src/android/app/src/main/jni/config.cpp (ReadSetting table)
// Keys and values match what the core writes into config.ini, so the app
// settings and the core never disagree.
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
        /** True for plain on/off options whose only values are true/false. */
        val isBooleanToggle: Boolean
            get() = choices.map { it.value.lowercase() }.toSet() == setOf("true", "false")

        /** Reads a persisted value with the true/false convention. */
        fun isEnabled(current: String?): Boolean =
            current.equals("true", ignoreCase = true) ||
                (current == null && defaultValue.equals("true", ignoreCase = true))

        /** Value to persist for a toggle interaction. */
        fun valueForEnabled(enabled: Boolean): String = if (enabled) "true" else "false"
    }

    data class Category(val key: String, val label: String, val description: String)

    private val categoryList: List<Category> = listOf(
        Category("cpu", "CPU", "CPU emulation performance and accuracy."),
        Category("system", "System", "Nintendo 3DS system, region and language settings."),
        Category("audio", "Audio", "Audio output, stretching and microphone settings."),
        Category("graphics", "Graphics", "Rendering and visual enhancement settings."),
        Category("layout", "Layout", "Screen layout and positioning options."),
        Category("storage", "Storage", "Save data and virtual SD card settings."),
        Category("input", "Input", "Controller, touchscreen and motion input."),
    )

    private fun toggleChoices(): List<Choice> =
        listOf(Choice("true", "Enabled"), Choice("false", "Disabled"))

    private val optionList: List<Option> = listOf(
        // CPU
        Option(
            key = "use_cpu_jit",
            label = "CPU JIT",
            category = "cpu",
            choices = toggleChoices(),
            defaultValue = "true",
        ),
        Option(
            key = "cpu_clock_percentage",
            label = "CPU Clock Speed",
            category = "cpu",
            choices = listOf(
                Choice("25", "25%"),
                Choice("50", "50%"),
                Choice("75", "75%"),
                Choice("100", "100% (Default)"),
                Choice("125", "125%"),
                Choice("150", "150%"),
                Choice("200", "200%"),
                Choice("300", "300%"),
                Choice("400", "400%"),
            ),
            defaultValue = "100",
        ),

        // System
        Option(
            key = "is_new_3ds",
            label = "System Model",
            category = "system",
            choices = toggleChoices(),
            defaultValue = "true",
        ),
        Option(
            key = "region_value",
            label = "System Region",
            category = "system",
            choices = listOf(
                Choice("-1", "Auto"),
                Choice("0", "Japan"),
                Choice("1", "USA"),
                Choice("2", "Europe"),
                Choice("3", "Australia"),
                Choice("4", "China"),
                Choice("5", "Korea"),
                Choice("6", "Taiwan"),
            ),
            defaultValue = "-1",
        ),
        Option(
            key = "language",
            label = "System Language",
            category = "system",
            choices = listOf(
                Choice("0", "English"),
                Choice("1", "Japanese"),
                Choice("2", "French"),
                Choice("3", "Spanish"),
                Choice("4", "German"),
                Choice("5", "Italian"),
                Choice("6", "Dutch"),
                Choice("7", "Portuguese"),
                Choice("8", "Russian"),
                Choice("9", "Korean"),
                Choice("10", "Traditional Chinese"),
                Choice("11", "Simplified Chinese"),
            ),
            defaultValue = "0",
        ),
        Option(
            key = "apply_region_free_patch",
            label = "Region Free Patch",
            category = "system",
            choices = toggleChoices(),
            defaultValue = "false",
        ),

        // Audio
        Option(
            key = "audio_emulation",
            label = "Audio Emulation",
            category = "audio",
            choices = listOf(
                Choice("0", "HLE (Fast)"),
                Choice("1", "LLE (Accurate)"),
                Choice("2", "LLE Multithreaded"),
            ),
            defaultValue = "0",
        ),
        Option(
            key = "enable_audio_stretching",
            label = "Audio Stretching",
            category = "audio",
            choices = toggleChoices(),
            defaultValue = "true",
        ),
        Option(
            key = "enable_realtime_audio",
            label = "Realtime Audio",
            category = "audio",
            choices = toggleChoices(),
            defaultValue = "false",
        ),
        Option(
            key = "volume",
            label = "Output Volume",
            category = "audio",
            choices = (0..100 step 5).map { Choice(it.toString(), "$it%") },
            defaultValue = "100",
        ),
        Option(
            key = "input_type",
            label = "Microphone Input",
            category = "audio",
            choices = listOf(
                Choice("0", "Auto"),
                Choice("1", "None"),
                Choice("2", "Static Noise"),
            ),
            defaultValue = "0",
        ),

        // Graphics
        Option(
            key = "graphics_api",
            label = "Graphics API",
            category = "graphics",
            choices = listOf(
                Choice("1", "OpenGL"),
                Choice("2", "Vulkan"),
            ),
            defaultValue = "2",
        ),
        Option(
            key = "use_hw_shader",
            label = "Hardware Shaders",
            category = "graphics",
            choices = toggleChoices(),
            defaultValue = "true",
        ),
        Option(
            key = "use_shader_jit",
            label = "Shader JIT",
            category = "graphics",
            choices = toggleChoices(),
            defaultValue = "true",
        ),
        Option(
            key = "shaders_accurate_mul",
            label = "Accurate Multiplication",
            category = "graphics",
            choices = toggleChoices(),
            defaultValue = "false",
        ),
        Option(
            key = "use_disk_shader_cache",
            label = "Shader Cache",
            category = "graphics",
            choices = toggleChoices(),
            defaultValue = "true",
        ),
        Option(
            key = "use_vsync",
            label = "VSync",
            category = "graphics",
            choices = toggleChoices(),
            defaultValue = "true",
        ),
        Option(
            key = "use_skip_duplicate_frames",
            label = "Skip Duplicate Frames",
            category = "graphics",
            choices = toggleChoices(),
            defaultValue = "false",
        ),
        Option(
            key = "texture_filter",
            label = "Texture Filter",
            category = "graphics",
            choices = listOf(
                Choice("0", "None"),
                Choice("1", "Anime4K Ultrafast"),
                Choice("2", "Bicubic"),
                Choice("3", "ScaleForce"),
                Choice("4", "xBRZ"),
                Choice("5", "MMPX"),
            ),
            defaultValue = "0",
        ),
        Option(
            key = "texture_sampling",
            label = "Texture Sampling",
            category = "graphics",
            choices = listOf(
                Choice("0", "Game Controlled"),
                Choice("1", "Nearest Neighbor"),
                Choice("2", "Linear"),
            ),
            defaultValue = "0",
        ),

        // Layout
        Option(
            key = "layout_option",
            label = "Screen Layout",
            category = "layout",
            choices = listOf(
                Choice("0", "Default Top-Bottom"),
                Choice("1", "Single Screen Only"),
                Choice("2", "Large Screen, Small Screen"),
                Choice("3", "Side by Side"),
                Choice("4", "Side by Side (Full)"),
                Choice("5", "Hybrid (Custom)"),
                Choice("6", "Hybrid (Custom, Large)"),
            ),
            defaultValue = "0",
        ),
        Option(
            key = "swap_screen",
            label = "Prominent Screen",
            category = "layout",
            choices = toggleChoices(),
            defaultValue = "false",
        ),
        Option(
            key = "aspect_ratio",
            label = "Aspect Ratio",
            category = "layout",
            choices = listOf(
                Choice("0", "Stretch"),
                Choice("1", "Auto 4:3/3:2"),
                Choice("2", "4:3"),
                Choice("3", "16:9"),
                Choice("4", "10:7"),
            ),
            defaultValue = "1",
        ),
        Option(
            key = "resolution_factor",
            label = "Internal Resolution",
            category = "graphics",
            choices = (1..10).map { Choice(it.toString(), "${it}x") },
            defaultValue = "1",
        ),

        // Storage
        Option(
            key = "use_virtual_sd",
            label = "Virtual SD Card",
            category = "storage",
            choices = toggleChoices(),
            defaultValue = "true",
        ),

        // Input
        Option(
            key = "motion_device",
            label = "Motion Source",
            category = "input",
            choices = listOf(
                Choice("motion_emu", "Emulated"),
                Choice("cemuhookudp", "CemuHook UDP"),
            ),
            defaultValue = "motion_emu",
        ),
        Option(
            key = "touch_device",
            label = "Touch Source",
            category = "input",
            choices = listOf(
                Choice("emu_window", "Emulation Window"),
                Choice("cemuhookudp", "CemuHook UDP"),
            ),
            defaultValue = "emu_window",
        ),
        Option(
            key = "udp_pad_index",
            label = "CemuHook Pad",
            category = "input",
            choices = (0..3).map { Choice(it.toString(), "Pad ${it + 1}") },
            defaultValue = "0",
        ),
    )

    private val optionsByKey: Map<String, Option> by lazy { optionList.associateBy { it.key } }

    /** Keys driven by dedicated app-level controls (renderer and upscale). */
    private val managedKeys = setOf(
        "graphics_api",
        "resolution_factor",
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
        option("cpu_clock_percentage"),
        option("use_cpu_jit"),
        option("region_value"),
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
        option("texture_filter"),
        option("texture_sampling"),
        option("use_hw_shader"),
        option("use_shader_jit"),
        option("shaders_accurate_mul"),
        option("use_disk_shader_cache"),
        option("use_vsync"),
        option("layout_option"),
        option("swap_screen"),
        option("aspect_ratio"),
    )

    /** Controls section of the in-game menu. */
    fun gameMenuControlsOptions(): List<Option> = listOfNotNull(
        option("touch_device"),
        option("udp_pad_index"),
    )
}
