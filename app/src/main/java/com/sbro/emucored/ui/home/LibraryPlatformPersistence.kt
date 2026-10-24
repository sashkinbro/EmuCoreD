package com.sbro.emucored.ui.home

import androidx.lifecycle.SavedStateHandle
import com.sbro.emucored.data.GamePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal const val LIBRARY_PLATFORM_STATE_KEY = "library_platform"
internal const val LIBRARY_PLATFORMS_STATE_KEY = "library_platforms"

internal fun restoreLibraryPlatforms(savedState: SavedStateHandle): Set<GamePlatform> {
    val names = savedState.get<ArrayList<String>>(LIBRARY_PLATFORMS_STATE_KEY)
        ?: savedState.get<String>(LIBRARY_PLATFORM_STATE_KEY)?.let { arrayListOf(it) }
        ?: return emptySet()
    return names.mapNotNull { name -> GamePlatform.entries.firstOrNull { it.name == name } }.toSet()
}

internal fun CoroutineScope.persistLibraryPlatforms(
    platforms: Flow<Set<GamePlatform>>, savedState: SavedStateHandle
): Job = launch {
    platforms.distinctUntilChanged().collect { selected ->
        savedState[LIBRARY_PLATFORMS_STATE_KEY] = ArrayList(GamePlatform.entries.filter { it in selected }.map { it.name })
        savedState.remove<String>(LIBRARY_PLATFORM_STATE_KEY)
    }
}

/** Use the ViewModel's main scope, including when a background rescan normalizes selection. */
internal fun CoroutineScope.persistLibraryPlatformFilter(
    platforms: Flow<GamePlatform?>, savedState: SavedStateHandle
): Job = launch {
    platforms.distinctUntilChanged().collect { savedState[LIBRARY_PLATFORM_STATE_KEY] = it?.name }
}
