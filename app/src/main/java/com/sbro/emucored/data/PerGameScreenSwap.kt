package com.sbro.emucored.data

fun PerGameSettings.resolveScreenSwap(fallback: Boolean): Boolean =
    swapScreens ?: coreOptions["swap_screen"]?.toBooleanStrictOrNull() ?: fallback

fun PerGameSettings.withScreenSwap(swapped: Boolean): PerGameSettings = copy(
    swapScreens = swapped,
    coreOptions = coreOptions - "swap_screen",
    providedKeys = providedKeys?.plus("swapScreens")
)
