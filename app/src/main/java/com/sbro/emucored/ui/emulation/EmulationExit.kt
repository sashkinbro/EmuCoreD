package com.sbro.emucored.ui.emulation

internal fun completeEmulationExit(
    activePlayTimeMs: Long,
    restoreHostUi: () -> Unit,
    navigateFromEmulation: (Long) -> Unit
) {
    restoreHostUi()
    navigateFromEmulation(activePlayTimeMs)
}
