package com.sbro.emucored.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucored.data.GameItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeLibraryVirtualizationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tenThousandGamesOnlyComposeVisibleRows() = verifyVirtualization(false)
    @Test fun tenThousandListEntriesOnlyComposeVisibleRows() = verifyVirtualization(true)

    private fun verifyVirtualization(listView: Boolean) {
        val active = mutableSetOf<String>()
        val catalog = List(10000) { index -> GameItem("Game $index", "/game/$index", "$index.3ds", 1, 0) }
        compose.setContent {
            LazyVerticalGrid(GridCells.Fixed(1), Modifier.testTag("library")) {
                homeLibraryItems(catalog, 6, listView) { games, _ ->
                    Row(Modifier.fillMaxWidth().height(120.dp)) {
                        games.forEach { game ->
                            DisposableEffect(game.path) {
                                active.add(game.path)
                                onDispose { active.remove(game.path) }
                            }
                            Box(Modifier.weight(1f).height(120.dp))
                        }
                    }
                }
            }
        }
        compose.runOnIdle { assertTrue("Composed ${active.size} covers at once", active.size in 1..100) }
        compose.onNodeWithTag("library").performScrollToIndex(900)
        compose.runOnIdle {
            assertTrue("Offscreen rows retained: ${active.size}", active.size in 1..100)
            assertTrue("Scroll did not reach later games", active.any { it.substringAfterLast('/').toInt() >= 900 })
        }
    }
}
