package com.sbro.emucored.ui.emulation

data class EmulationPerformanceState(
    val fps: String = "0.0",
    val speedPercent: Float = 100f,
    val overlayText: String = ""
)
