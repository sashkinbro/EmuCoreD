package com.sbro.emucored.data

data class LibraryPlatformSelection(val available: List<GamePlatform>, val selected: GamePlatform?)

/** Resolve against the complete library, never against search results or the recent list. */
fun resolveLibraryPlatformSelection(
    games: List<GameItem>, requested: GamePlatform?, libraryLoaded: Boolean = true
): LibraryPlatformSelection {
    val present = games.mapTo(mutableSetOf()) { it.platform }
    val available = GamePlatform.entries.filter { it in present }
    return LibraryPlatformSelection(available, requested?.takeIf { !libraryLoaded || it in present })
}

fun List<GameItem>.filterLibraryPlatform(platform: GamePlatform?): List<GameItem> =
    if (platform == null) this else filter { it.platform == platform }
