package com.sbro.emucored.ui.home

import androidx.lifecycle.SavedStateHandle
import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.GamePlatform
import com.sbro.emucored.data.resolveLibraryPlatformSelection
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class LibraryPlatformPersistenceTest {
    @Test fun rescanNormalizationCannotResurrectAnOldFilterAfterProcessRecreation() = runBlocking {
        val state = SavedStateHandle(mapOf(LIBRARY_PLATFORM_STATE_KEY to GamePlatform.NDS.name))
        val selected = MutableStateFlow<GamePlatform?>(GamePlatform.NDS)
        val job = persistLibraryPlatformFilter(selected, state)
        try {
            yield()
            val remaining = listOf(GameItem("3DS game", "game.3ds", "game.3ds", 1, 0))
            selected.value = resolveLibraryPlatformSelection(remaining, selected.value).selected
            yield()
            assertNull(state.get<String>(LIBRARY_PLATFORM_STATE_KEY))
            val restored = state.get<String>(LIBRARY_PLATFORM_STATE_KEY)?.let(GamePlatform::valueOf)
            val dsReturns = remaining + GameItem("DS game", "game.nds", "game.nds", 1, 0, platform = GamePlatform.NDS)
            assertNull(resolveLibraryPlatformSelection(dsReturns, restored).selected)
        } finally { job.cancelAndJoin() }
    }
}
