package com.sbro.emucored.ui.cheats

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.sbro.emucored.core.testStorageContext
import com.sbro.emucored.data.*
import com.sbro.emucored.data.cheats.TypedCheatCatalog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class CheatManagerSelectionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restoredSecondGameReloadsItsConfigAfterTheLibraryArrives() {
        testStorageContext().use { context ->
            val games = listOf("Alpha restored fixture", "Beta restored fixture").mapIndexed { index, title ->
                val file = File(context.cacheDir, "restored-$index.gba").apply { writeBytes(ByteArray(512)) }
                GameItem(title, file.path, file.name, file.length(), 0, platform = GamePlatform.GBA)
            }
            val paths = listOf(context.cacheDir.path)
            runBlocking { AppPreferences(context).setGamePaths(paths) }
            GameLibraryCacheRepository(context).save(GameLibraryCacheRepository.libraryKey(paths), games)
            val keys = games.map { TypedCheatCatalog.gameKey(it.platform, it.path) }
            keys.forEachIndexed { index, key ->
                CheatRepository(context).importCheatFile(key, TypedCheatCatalog.serialize(listOf(
                    CheatBlock("restored-$index", "Restored game's cheat $index", listOf("02000000:01"),
                        false, format = "gba-raw")
                )), enableAllByDefault = false)
            }
            val secondResolutions = AtomicInteger()
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                // The isolated storage context is not an ActivityResultRegistryOwner.
                val activityRegistryOwner = requireNotNull(LocalActivityResultRegistryOwner.current)
                CompositionLocalProvider(LocalContext provides context,
                    LocalActivityResultRegistryOwner provides activityRegistryOwner) {
                    MaterialTheme {
                        CheatManagerContent({}, identityResolver = { game ->
                            if (game.path == games[1].path) secondResolutions.incrementAndGet()
                            SelectedGameIdentity(null, null)
                        })
                    }
                }
            }
            fun requireConfig(key: String) {
                compose.waitUntil(10_000) {
                    runCatching { compose.onNodeWithTag("cheat-manager-list")
                        .performScrollToNode(hasTestTag("cheat-config-$key")) }.isSuccess
                }
                compose.onNodeWithTag("cheat-config-$key").assertIsDisplayed()
            }
            requireConfig(keys[0])
            compose.onNodeWithTag("cheat-manager-list").performScrollToNode(hasText(games[1].title))
            compose.onNodeWithText(games[1].title).performScrollTo().performClick()
            requireConfig(keys[1])
            val beforeRestore = secondResolutions.get()
            restoration.emulateSavedInstanceStateRestore()
            // A restored path exists before asynchronous library loading. Its arrival
            // must restart resolution even though the selected path itself stays equal.
            compose.waitUntil(10_000) { secondResolutions.get() > beforeRestore }
            requireConfig(keys[1])
            compose.onNodeWithTag("cheat-config-${keys[0]}").assertDoesNotExist()
        }
    }

    @Test fun selectingAnotherGameRemovesTheOldConfigWhileIdentityIsStillLoading() {
        testStorageContext().use { context ->
            val games = listOf("Alpha fixture", "Beta fixture").mapIndexed { index, title ->
                val file = File(context.cacheDir, "$index.gba").apply { writeBytes(ByteArray(512)) }
                GameItem(title, file.path, file.name, file.length(), 0, platform = GamePlatform.GBA)
            }
            val paths = listOf(context.cacheDir.path)
            runBlocking { AppPreferences(context).setGamePaths(paths) }
            GameLibraryCacheRepository(context).save(GameLibraryCacheRepository.libraryKey(paths), games)
            val firstKey = TypedCheatCatalog.gameKey(GamePlatform.GBA, games[0].path)
            CheatRepository(context).importCheatFile(firstKey, TypedCheatCatalog.serialize(listOf(
                CheatBlock("fixture", "Original game's cheat", listOf("02000000:01"), false, format = "gba-raw")
            )), enableAllByDefault = false)
            val secondStarted = AtomicBoolean()
            val releaseSecond = CompletableDeferred<Unit>()
            compose.setContent {
                val activityRegistryOwner = requireNotNull(LocalActivityResultRegistryOwner.current)
                CompositionLocalProvider(LocalContext provides context,
                    LocalActivityResultRegistryOwner provides activityRegistryOwner) {
                    MaterialTheme {
                        CheatManagerContent({}, identityResolver = { game ->
                            if (game.path == games[1].path) {
                                secondStarted.set(true)
                                releaseSecond.await()
                            }
                            SelectedGameIdentity(null, null)
                        })
                    }
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Alpha fixture").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(10_000) {
                runCatching {
                    compose.onNodeWithTag("cheat-manager-list")
                        .performScrollToNode(hasTestTag("cheat-config-$firstKey"))
                }.isSuccess
            }
            compose.onNodeWithTag("cheat-config-$firstKey").assertIsDisplayed()
            compose.onNodeWithTag("cheat-manager-list").performScrollToNode(hasText("Beta fixture"))
            compose.onNodeWithText("Beta fixture").performScrollTo().performClick()
            compose.waitUntil(10_000) { secondStarted.get() }
            compose.onNodeWithTag("cheat-config-$firstKey").assertDoesNotExist()
            releaseSecond.complete(Unit)
        }
    }
}
