package com.sbro.emucored.data.textures

import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.data.GamePlatform
import java.util.Locale

/** Accept only parser results; library serials and filenames are never identity evidence. */
internal object TexturePackIdentity {
    fun supports(platform: GamePlatform): Boolean = platform in setOf(
        GamePlatform.N3DS, GamePlatform.GAMECUBE, GamePlatform.WII)

    fun fromNativeTitleId(id: Long): TexturePackTarget? = if (id == 0L) null else
        TexturePackTarget(ConsolePlatform.N3DS, String.format(Locale.US, "%016X", id))

    fun fromDolphin(selected: GamePlatform, actual: ConsolePlatform, gameId: String): TexturePackTarget? {
        if (selected !in setOf(GamePlatform.GAMECUBE, GamePlatform.WII) || selected.console != actual) return null
        return runCatching { TexturePackTarget(actual, gameId) }.getOrNull()
    }
}
