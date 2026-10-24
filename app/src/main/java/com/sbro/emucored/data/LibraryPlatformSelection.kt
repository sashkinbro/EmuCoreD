package com.sbro.emucored.data

data class LibraryPlatformSelection(val available: List<GamePlatform>, val selected: GamePlatform?)
data class LibraryPlatformMultiSelection(val available: List<GamePlatform>, val selected: Set<GamePlatform>)

/** An empty set is the All chip. Selecting another chip adds it to the union. */
fun toggleLibraryPlatform(selected: Set<GamePlatform>, clicked: GamePlatform?): Set<GamePlatform> = when {
    clicked == null -> emptySet()
    clicked in selected -> selected - clicked
    else -> selected + clicked
}

fun resolveLibraryPlatforms(
    games: List<GameItem>, requested: Set<GamePlatform>, libraryLoaded: Boolean = true
): LibraryPlatformMultiSelection {
    val present = games.mapTo(mutableSetOf()) { it.platform }
    return LibraryPlatformMultiSelection(GamePlatform.entries.filter { it in present },
        if (libraryLoaded) requested.intersect(present) else requested)
}

fun List<GameItem>.filterLibraryPlatforms(platforms: Set<GamePlatform>): List<GameItem> =
    if (platforms.isEmpty()) this else filter { it.platform in platforms }

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
