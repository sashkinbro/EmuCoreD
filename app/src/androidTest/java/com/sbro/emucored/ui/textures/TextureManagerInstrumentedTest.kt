package com.sbro.emucored.ui.textures

import android.app.Application
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.R
import com.sbro.emucored.core.BackupSessionGate
import com.sbro.emucored.core.testStorageContext
import com.sbro.emucored.data.AppPreferences
import com.sbro.emucored.data.GameItem
import com.sbro.emucored.data.GameLibraryCacheRepository
import com.sbro.emucored.data.GamePlatform
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextureManagerInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualManagerShowsVerifiedIdAndImportedFilesAndRequiresDeleteConfirmation() = exercise { model, game ->
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText("GTSE01").performScrollTo().assertIsDisplayed()
        assertEquals(listOf(GamePlatform.GAMECUBE), model.state.value.games.map { it.platform })
        val selected = requireNotNull(model.state.value.selection)
        val archive = File.createTempFile("texture-ui", ".zip", File(game.path).parentFile)
        try {
            val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("GTSE01/original.png")); zip.write(png); zip.closeEntry()
            }
            compose.runOnIdle { model.importZip(Uri.fromFile(archive), selected) }
            compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.installed?.stats?.textureCount == 1 }
            compose.onNodeWithText("original.png").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.delete)).performScrollTo().performClick()
            compose.onNodeWithText(app.getString(R.string.texture_manager_delete_body)).assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.cancel)).performClick()
            assertEquals(1, model.state.value.installed?.stats?.textureCount)
            compose.onNodeWithText(app.getString(R.string.delete)).performScrollTo().performClick()
            compose.onAllNodesWithText(app.getString(R.string.delete)).onLast().performClick()
            compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.installed?.stats?.textureCount == 0 }
            compose.onNodeWithText(app.getString(R.string.texture_manager_empty)).performScrollTo().assertIsDisplayed()
        } finally { archive.delete() }
    }

    @Test fun gameBusyStateDisablesImportAndExplainsWhy() = exercise { model, _ ->
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            runBlocking { BackupSessionGate.start(active = { true }) { true } }
            compose.waitUntil(5000) { model.state.value.gameBusy }
            compose.onNodeWithText(app.getString(R.string.texture_manager_stop)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.texture_manager_import)).performScrollTo().assertIsNotEnabled()
        } finally { BackupSessionGate.stopped() }
    }

    @Test fun restoredImportForADifferentGameCannotRetainTheCurrentGamesInstalledPackOnFailure() =
        exercise(twoGames = true) { model, game ->
            val capturedA = requireNotNull(model.state.value.selection)
            val second = model.state.value.games.first { it.path != game.path }
            compose.runOnIdle { model.select(second.path) }
            compose.waitUntil(10_000) { !model.state.value.loading && model.state.value.selection?.game?.path == second.path }
            val selectedB = requireNotNull(model.state.value.selection)
            val archive = File.createTempFile("texture-retarget", ".zip", File(game.path).parentFile)
            val invalid = File.createTempFile("texture-invalid", ".zip", File(game.path).parentFile)
            try {
                val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==")
                ZipOutputStream(archive.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("${selectedB.target.id}/only-b.png")); zip.write(png); zip.closeEntry()
                }
                compose.runOnIdle { model.importZip(Uri.fromFile(archive), selectedB) }
                compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.installed?.stats?.textureCount == 1 }
                assertEquals(listOf("only-b.png"), model.state.value.installed?.files)
                invalid.writeText("Not a ZIP archive")
                // Deliver the system picker result captured for A after B became current.
                compose.runOnIdle { model.importZip(Uri.fromFile(invalid), capturedA) }
                compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.selectedPath == game.path }
                assertEquals(capturedA, model.state.value.selection)
                assertNull("Game B's installed data remained attached to A", model.state.value.installed)
                assertTrue(model.state.value.online.isEmpty())
                assertFalse(model.state.value.onlineLoaded)
                assertFalse(model.state.value.identityFailed)
            } finally { archive.delete(); invalid.delete() }
        }

    private fun exercise(twoGames: Boolean = false, check: (TextureManagerViewModel, GameItem) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        testStorageContext().use { context ->
            val store = ViewModelStore()
            val file = File(context.cacheDir, "original.iso")
            val image = ByteArray(64 * 1024)
            "GTSE01".toByteArray().copyInto(image)
            ByteBuffer.wrap(image).order(ByteOrder.BIG_ENDIAN).putInt(0x1c, 0xc2339f3d.toInt())
            file.writeBytes(image)
            val game = GameItem("Original test game", file.path, file.name, file.length(), 0, platform = GamePlatform.GAMECUBE)
            runBlocking { AppPreferences(context).setGamePaths(listOf(context.cacheDir.path)) }
            val games = mutableListOf(game, game.copy(title = "Unsupported DS", path = "unsupported.nds", platform = GamePlatform.NDS))
            if (twoGames) {
                val secondFile = File(context.cacheDir, "second.iso")
                val secondImage = image.copyOf()
                "GSBE01".toByteArray().copyInto(secondImage)
                secondFile.writeBytes(secondImage)
                games += game.copy(title = "Second test game", path = secondFile.path, fileName = secondFile.name)
            }
            GameLibraryCacheRepository(context).save(context.cacheDir.path, games)
            lateinit var model: TextureManagerViewModel
            try {
                compose.runOnUiThread {
                    model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = TextureManagerViewModel(app, context) as T
                    })[TextureManagerViewModel::class.java]
                }
                compose.setContent { MaterialTheme { TextureManagerContent(model, {}, game.path) } }
                compose.waitUntil(10_000) { model.state.value.selection != null && !model.state.value.loading }
                check(model, game)
            } finally {
                BackupSessionGate.stopped()
                compose.runOnUiThread { store.clear() }
            }
        }
    }
}
