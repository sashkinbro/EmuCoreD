package com.sbro.emucored.core

import com.sbro.emucored.core.platform.ConsolePlatform
import java.util.Locale

/** File discovery and the format help screen use the same native-adapter registry. */
object ConsoleGameFormats {
    data class Profile(val images: Set<String>, val executables: Set<String> = emptySet()) {
        val extensions: Set<String> get() = images + executables
    }

    fun profile(platform: ConsolePlatform): Profile = when (platform) {
        ConsolePlatform.N3DS -> Profile(
            N3dsGameFormats.extensions - setOf("3dsx", "z3dsx", "elf", "axf"),
            setOf("3dsx", "z3dsx", "elf", "axf")
        )
        ConsolePlatform.NDS -> Profile(NdsGameFormats.extensions)
        ConsolePlatform.N64 -> Profile(setOf("n64", "z64", "v64"))
        ConsolePlatform.GAMECUBE -> Profile(setOf("gcm", "iso", "rvz", "wia", "gcz", "ciso", "tgc"), setOf("dol", "elf"))
        ConsolePlatform.WII -> Profile(setOf("iso", "rvz", "wia", "gcz", "ciso", "wbfs", "wad"), setOf("dol", "elf"))
        ConsolePlatform.GBA -> Profile(setOf("gba", "agb"))
        ConsolePlatform.GB -> Profile(setOf("gb", "sgb"))
        ConsolePlatform.GBC -> Profile(setOf("gbc", "cgb"))
        ConsolePlatform.NES -> Profile(setOf("nes", "unf", "unif", "fds"))
        ConsolePlatform.SNES -> Profile(setOf("sfc", "smc", "swc", "fig"))
    }

    private val byExtension: Map<String, Set<ConsolePlatform>> by lazy {
        buildMap<String, MutableSet<ConsolePlatform>> {
            for (platform in ConsolePlatform.entries) for (extension in profile(platform).extensions)
                getOrPut(extension) { linkedSetOf() }.add(platform)
        }
    }

    fun candidatesForFileName(name: String): Set<ConsolePlatform> {
        val fileName = name.substringAfterLast('/').substringAfterLast('\\')
        if (ConsoleImageParts.isSecondary(fileName)) return emptySet()
        return byExtension[fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)].orEmpty()
    }

    fun isSupportedName(name: String): Boolean = candidatesForFileName(name).isNotEmpty()
}
