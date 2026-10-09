package com.sbro.emucored.core

/**
 * Renderer identifiers used by the app UI. Only the two hardware backends the
 * Android core build supports are offered; the Azahar Android build compiles
 * without the software rasterizer.
 */
object RendererDefaults {
    const val AUTO = -1
    const val OPENGL = 12
    const val VULKAN = 14
    const val DEFAULT = VULKAN

    // Stable C API renderer values. Keep this translation at the frontend
    // boundary: Android's 12/14 values come from the inherited UI model and
    // must never leak into the native core contract.
    const val CORE_VULKAN = 1
    const val CORE_OPENGL = 2

    /** Select a stable default while allowing either hardware renderer. */
    fun defaultForHardware(
        @Suppress("UNUSED_PARAMETER")
        isMediaTekHardware: Boolean = GpuHardwareProfiles.isMediaTekHardware()
    ): Int = VULKAN

    fun normalizeAndroidRenderer(
        value: Int,
        isMediaTekHardware: Boolean = GpuHardwareProfiles.isMediaTekHardware()
    ): Int {
        return when (value) {
            OPENGL, VULKAN -> value
            else -> defaultForHardware(isMediaTekHardware)
        }
    }

    fun toCoreRenderer(
        value: Int,
        isMediaTekHardware: Boolean = GpuHardwareProfiles.isMediaTekHardware()
    ): Int = when (normalizeAndroidRenderer(value, isMediaTekHardware)) {
        VULKAN -> CORE_VULKAN
        else -> CORE_OPENGL
    }

    fun coreRendererName(value: Int): String = when (value) {
        CORE_VULKAN -> "Vulkan"
        CORE_OPENGL -> "OpenGL"
        else -> "Unknown($value)"
    }
}
