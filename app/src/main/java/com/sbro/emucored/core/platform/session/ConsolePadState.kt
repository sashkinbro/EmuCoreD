// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core.platform.session

/** Desired input survives boot; only input accepted by a ready guest becomes applied. */
internal class ConsolePadState {
    private val desired = IntArray(4) { 0xFFFF }
    private val applied = IntArray(4) { 0xFFFF }

    @Synchronized fun reset() { desired.fill(0xFFFF); applied.fill(0xFFFF) }
    @Synchronized fun update(player: Int, buttons: Int, ready: Boolean, emit: (Int, EngineButton, Boolean) -> Unit) {
        if (player !in desired.indices) return
        desired[player] = buttons and 0xFFFF
        if (ready) flush(player, emit)
    }
    @Synchronized fun replay(emit: (Int, EngineButton, Boolean) -> Unit) {
        for (player in desired.indices) flush(player, emit)
    }
    private fun flush(player: Int, emit: (Int, EngineButton, Boolean) -> Unit) {
        val changed = applied[player] xor desired[player]
        for ((bit, button) in bits) if (changed and (1 shl bit) != 0)
            emit(player, button, desired[player] and (1 shl bit) == 0)
        applied[player] = desired[player]
    }
    private val bits = mapOf(0 to EngineButton.SELECT, 1 to EngineButton.L2, 2 to EngineButton.R2,
        3 to EngineButton.START, 4 to EngineButton.UP, 5 to EngineButton.RIGHT,
        6 to EngineButton.DOWN, 7 to EngineButton.LEFT, 8 to EngineButton.HOME,
        10 to EngineButton.L, 11 to EngineButton.R, 12 to EngineButton.X,
        13 to EngineButton.A, 14 to EngineButton.B, 15 to EngineButton.Y)
}
