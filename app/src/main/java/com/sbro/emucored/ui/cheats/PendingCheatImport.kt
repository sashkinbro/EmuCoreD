package com.sbro.emucored.ui.cheats

import androidx.compose.runtime.saveable.listSaver
import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.SelectedGameIdentity

/** Immutable original target survives the external document picker's activity recreation. */
internal data class PendingCheatImport(
    val path: String,
    val platform: GamePlatform,
    val serial: String?,
    val identitySerial: String?,
    val identityCrc: String?,
    val request: CheatSelectionGuard.Request
) {
    // Import/config lookup needs identity fields only, never the currently selected game.
    val game get() = GameItem("", path, "", 0, 0, serial = serial, platform = platform)
    val identity get() = SelectedGameIdentity(identitySerial, identityCrc)

    companion object {
        fun capture(game: GameItem, identity: SelectedGameIdentity?, request: CheatSelectionGuard.Request) =
            PendingCheatImport(game.path, game.platform, game.serial, identity?.serial, identity?.crc, request)

        val NullableSaver = androidx.compose.runtime.saveable.Saver<PendingCheatImport?, Any>(
            save = { pending -> pending?.let { with(Saver) { save(it) } } },
            restore = { Saver.restore(it) }
        )

        val Saver = listSaver<PendingCheatImport, Any?>(
            save = { listOf(it.path, it.platform.name, it.serial, it.identitySerial, it.identityCrc, it.request.epoch) },
            restore = { values ->
                val path = values[0] as String
                PendingCheatImport(path, GamePlatform.valueOf(values[1] as String), values[2] as String?,
                    values[3] as String?, values[4] as String?, CheatSelectionGuard.Request(path, values[5] as Long))
            }
        )
    }
}
