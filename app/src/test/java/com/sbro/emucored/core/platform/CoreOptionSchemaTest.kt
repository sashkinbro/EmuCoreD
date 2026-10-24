package com.sbro.emucored.core.platform

import org.junit.Assert.*
import org.junit.Test

class CoreOptionSchemaTest {
    @Test fun dolphinExposesRealGraphicsAudioAccuracyAndBootBoundaries() {
        val options = CoreOptionSchema.options(ConsolePlatform.GAMECUBE).associateBy { it.key }
        assertTrue(options.keys.containsAll(setOf("aspect_ratio", "widescreen_hack", "msaa", "anisotropy",
            "shader_compilation", "vsync", "skip_efb_access", "efb_texture_only", "xfb_texture_only",
            "texture_cache_accuracy", "texture_filtering", "gpu_texture_decoding", "hires_textures",
            "mmu", "accurate_cpu_cache", "cpu_clock", "dsp_jit", "audio_buffer_size", "audio_preserve_pitch")))
        assertTrue(options.getValue("cpu_clock").restartRequired)
        assertTrue(options.getValue("shader_compilation").restartRequired)
        assertTrue(options.getValue("dsp_jit").restartRequired)
        assertFalse(options.getValue("aspect_ratio").restartRequired)
        assertFalse(options.getValue("audio_preserve_pitch").restartRequired)
        assertEquals(listOf("1", "2", "4", "8"), options.getValue("msaa").values)
        assertFalse(options.getValue("msaa").accepts("3"))
        assertFalse(options.getValue("texture_cache_accuracy").accepts("unsafe"))
        assertTrue(CoreOptionSchema.options(ConsolePlatform.WII).any { it.key == "wii_speaker" })
        assertFalse(options.containsKey("wii_speaker"))
        assertTrue(options.containsKey("gc_language"))
        assertFalse(CoreOptionSchema.options(ConsolePlatform.WII).any { it.key == "gc_language" })
    }
    @Test fun everyPackagedAdapterHasValidatedDefaultsWithoutForeignOptions() {
        val platforms = ConsolePlatform.entries.filter { it !in setOf(ConsolePlatform.N3DS, ConsolePlatform.NDS) }
        platforms.forEach { platform ->
            val options = CoreOptionSchema.options(platform)
            assertTrue(platform.id, options.isNotEmpty())
            assertEquals(options.size, options.map { it.key }.distinct().size)
            options.forEach { assertTrue(it.key, it.accepts(it.defaultValue)) }
            assertFalse(platform.id, CoreOptionSchema.normalize(platform, mapOf("use_cpu_jit" to "true")).containsKey("use_cpu_jit"))
        }
    }

    @Test fun gameOverridesAreValidatedAndIsolatedFromOtherConsoles() {
        val options = CoreOptionSchema.resolve(ConsolePlatform.WII,
            mapOf("resolution" to "2", "volume" to "80", "wii_profile" to "classic"),
            mapOf("resolution" to "4", "volume" to "bad", "bsnes_ppu_fast" to "ON"))
        assertEquals("4", options["resolution"])
        assertEquals("80", options["volume"])
        assertEquals("classic", options["wii_profile"])
        assertFalse(options.containsKey("bsnes_ppu_fast"))
        assertFalse(CoreOptionSchema.options(ConsolePlatform.GAMECUBE).any { it.key == "wii_profile" })
        assertEquals("vulkan", CoreOptionSchema.resolve(ConsolePlatform.GAMECUBE)["graphics_backend"])
    }

    @Test fun binaryAndNumericValuesRespectNativeBoundaries() {
        val dolphin = CoreOptionSchema.options(ConsolePlatform.WII).associateBy { it.key }
        assertTrue(dolphin.getValue("volume").accepts("0"))
        assertTrue(dolphin.getValue("volume").accepts("100"))
        assertFalse(dolphin.getValue("volume").accepts("101"))
        assertFalse(dolphin.getValue("resolution").accepts("13"))
        assertFalse(dolphin.getValue("dual_core").accepts("yes"))
        assertTrue(CoreOptionSchema.options(ConsolePlatform.N3DS).isEmpty())
        assertTrue(CoreOptionSchema.options(ConsolePlatform.NDS).isEmpty())
    }

    @Test fun handheldEnginesExposeTheirActualVideoAudioAndCpuOptions() {
        val gba = CoreOptionSchema.options(ConsolePlatform.GBA).associateBy { it.key }
        assertTrue(gba.keys.containsAll(setOf("frameskip", "volume", "idle_optimization", "allow_opposing_directions")))
        assertFalse(gba.getValue("frameskip").accepts("11"))
        assertTrue(gba.getValue("idle_optimization").accepts("detect"))
        val gb = CoreOptionSchema.options(ConsolePlatform.GB).associateBy { it.key }
        assertTrue(gb.keys.containsAll(setOf("palette", "highpass_filter", "interference_volume")))
        assertFalse(gb.getValue("palette").accepts("missing"))
        assertTrue(CoreOptionSchema.options(ConsolePlatform.GBC).any { it.key == "highpass_filter" })
    }
}
