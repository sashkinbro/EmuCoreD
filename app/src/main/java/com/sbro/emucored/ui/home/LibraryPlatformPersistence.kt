package com.sbro.emucored.ui.home

import androidx.lifecycle.SavedStateHandle
import com.sbro.emucored.data.GamePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal const val LIBRARY_PLATFORM_STATE_KEY = "library_platform"

/** Use the ViewModel's main scope, including when a background rescan normalizes selection. */
internal fun CoroutineScope.persistLibraryPlatformFilter(
    platforms: Flow<GamePlatform?>, savedState: SavedStateHandle
): Job = launch {
    platforms.distinctUntilChanged().collect { savedState[LIBRARY_PLATFORM_STATE_KEY] = it?.name }
}
