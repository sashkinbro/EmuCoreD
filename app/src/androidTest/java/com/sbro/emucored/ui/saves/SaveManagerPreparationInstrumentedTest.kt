package com.sbro.emucored.ui.saves

import androidx.activity.compose.LocalActivityResultRegistryOwner
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.session.ConsoleSessionStorage
import com.sbro.emucored.core.testStorageContext
import com.sbro.emucored.data.SaveStateRepository
import com.sbro.emucored.ui.common.cachedImageAspectRatio
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule

class SaveManagerPreparationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun preparedNavigationRendersTheActualSlotWithoutALoadingCard() = runBlocking {
        testStorageContext().use { context ->
            val game = fixture(context, "Ready first frame", 1)
            prepareSaveManager(context, game.path, "Ready first frame", null)
            val state = ConsoleSessionStorage.stateFile(context.filesDir, ConsolePlatform.GBA, game.path, 0)
            compose.setContent {
                val activityRegistryOwner = requireNotNull(LocalActivityResultRegistryOwner.current)
                CompositionLocalProvider(LocalContext provides context,
                    LocalActivityResultRegistryOwner provides activityRegistryOwner) {
                    MaterialTheme { SaveManagerScreen(game.path, "Ready first frame", null, { _, _ -> }, {}) }
                }
            }
            compose.onNodeWithTag("save-entry-${state.absolutePath}").assertIsDisplayed()
            compose.onNodeWithTag("save-manager-loading").assertDoesNotExist()
        }
    }

    @Test fun coldPreparationPublishesFinalMetadataAndWarmsOnlyFourVisiblePreviews() = runBlocking {
        testStorageContext().use { context ->
            val game = fixture(context, "Original A", 6)
            prepareSaveManager(context, game.path, "Original A", null)
            val snapshot = requireNotNull(takePreparedSaveManager(context, game.path, "Original A", null))
            assertEquals(6, snapshot.entries.size)
            assertEquals(6, snapshot.previews.size)
            assertTrue(snapshot.entries.all { it.gameTitle == "Original A" && it.gamePath == game.path })
            snapshot.entries.take(4).forEach { entry ->
                assertEquals(2f, cachedImageAspectRatio(snapshot.previews.getValue(entry.absolutePath).path))
            }
            snapshot.entries.drop(4).forEach { entry ->
                val preview = snapshot.previews.getValue(entry.absolutePath)
                assertEquals(2f, preview.aspectRatio)
                assertNull("Offscreen previews were decoded eagerly", cachedImageAspectRatio(preview.path))
            }
            assertNull(takePreparedSaveManager(context, game.path, "Original A", null))
        }
    }

    @Test fun warmReopenRescansDeletedSlotsAndNeverReturnsAnotherGamesSnapshot() = runBlocking {
        testStorageContext().use { context ->
            val a = fixture(context, "Original A", 2)
            val b = fixture(context, "Original B", 1)
            prepareSaveManager(context, a.path, "Original A", null)
            assertNull(takePreparedSaveManager(context, b.path, "Original B", null))
            val first = requireNotNull(takePreparedSaveManager(context, a.path, "Original A", null))
            assertTrue(SaveStateRepository(context).deleteEntry(first.entries.first()))
            prepareSaveManager(context, a.path, "Original A", null)
            val fresh = requireNotNull(takePreparedSaveManager(context, a.path, "Original A", null))
            assertEquals(1, fresh.entries.size)
            assertNotEquals(first.entries.first().absolutePath, fresh.entries.single().absolutePath)
            val changedPreview = File(fresh.previews.getValue(fresh.entries.single().absolutePath).path)
            val replacement = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888)
            changedPreview.outputStream().use { replacement.compress(Bitmap.CompressFormat.PNG, 100, it) }
            replacement.recycle()
            changedPreview.setLastModified(4_000)
            prepareSaveManager(context, a.path, "Original A", null)
            val replaced = requireNotNull(takePreparedSaveManager(context, a.path, "Original A", null))
            assertEquals(0.5f, replaced.previews.values.single().aspectRatio)
            assertEquals(0.5f, cachedImageAspectRatio(changedPreview.path))
            prepareSaveManager(context, b.path, "Original B", null)
            assertTrue(requireNotNull(takePreparedSaveManager(context, b.path, "Original B", null))
                .entries.all { it.gamePath == b.path && it.gameTitle == "Original B" })
        }
    }

    private fun fixture(context: Context, title: String, slots: Int): File {
        val game = File(context.cacheDir, "$title.gba").apply { writeBytes(ByteArray(512)) }
        repeat(slots) { slot ->
            val state = ConsoleSessionStorage.stateFile(context.filesDir, ConsolePlatform.GBA, game.path, slot)
            state.parentFile!!.mkdirs()
            val payload = byteArrayOf(1, 2, 3, slot.toByte())
            var checksum = 1469598103934665603L
            payload.forEach { checksum = (checksum xor (it.toLong() and 255)) * 1099511628211L }
            state.writeBytes(ByteBuffer.allocate(32 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(0x3145544154534345L).putLong(1).putLong(checksum).putLong(payload.size.toLong()).put(payload).array())
            File("${state.path}.json").writeText(JSONObject().put("platform", "GBA")
                .put("gamePath", game.path).put("slot", slot).put("title", title).toString())
            val preview = File("${state.path}.png")
            val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
            preview.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            state.setLastModified(1_000L + slot)
            preview.setLastModified(2_000L + slot)
        }
        return game
    }
}
