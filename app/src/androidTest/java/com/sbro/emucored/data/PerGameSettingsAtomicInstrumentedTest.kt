package com.sbro.emucored.data

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucored.core.EmulatorStorage
import com.sbro.emucored.core.testStorageContext
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerGameSettingsAtomicInstrumentedTest {
    @Test fun unrelatedOrMalformedJsonCannotEraseExistingProfiles() {
        testStorageContext().use { isolated ->
            val context = object : ContextWrapper(isolated) {
                override fun getExternalFilesDir(type: String?) = File(isolated.filesDir, "external").apply { mkdirs() }
            }
            check(EmulatorStorage.appStateDir(context).canonicalPath.startsWith(isolated.filesDir.canonicalPath + File.separator))
            val repository = PerGameSettingsRepository(context)
            repository.save(PerGameSettings("original", "Original game", gamePlatform = GamePlatform.NES))
            val expected = repository.exportJson().toString()
            val invalid = listOf(JSONObject().put("themeMode", 1),
                JSONObject().put("profiles", JSONArray().put("wrong record")),
                JSONObject().put("profiles", JSONArray().put(JSONObject().put("gameTitle", "Missing identity"))))
            invalid.forEach { json ->
                assertThrows(IllegalArgumentException::class.java) { repository.importJson(json) }
                assertEquals(expected, repository.exportJson().toString())
            }
        }
    }

    @Test fun independentRepositoryWritersNeverDropAnotherGamesProfile() {
        testStorageContext().use { isolated ->
            val context = object : ContextWrapper(isolated) {
                override fun getExternalFilesDir(type: String?) = File(isolated.filesDir, "external").apply { mkdirs() }
            }
            check(EmulatorStorage.appStateDir(context).canonicalPath.startsWith(isolated.filesDir.canonicalPath + File.separator))
            val workers = Executors.newFixedThreadPool(8)
            val start = CyclicBarrier(8)
            try {
                val jobs = (0 until 8).map { worker -> workers.submit {
                    val repository = PerGameSettingsRepository(context)
                    start.await(10, TimeUnit.SECONDS)
                    repeat(12) { number ->
                        val id = worker * 12 + number
                        repository.save(PerGameSettings(gameKey = "game-$id", gameTitle = "Game $id",
                            gamePlatform = GamePlatform.entries[id % GamePlatform.entries.size], showFps = id % 2 == 0))
                    }
                } }
                jobs.forEach { it.get(30, TimeUnit.SECONDS) }
                val saved = PerGameSettingsRepository(context).getAll()
                assertEquals(96, saved.size)
                assertEquals((0 until 96).map { "game-$it" }.toSet(), saved.map { it.gameKey }.toSet())
            } finally { workers.shutdownNow(); workers.awaitTermination(5, TimeUnit.SECONDS) }
        }
    }

    @Test fun interruptedWriteRecoversBackupBeforeReadMutationAndExport() {
        testStorageContext().use { isolated ->
            val context = object : ContextWrapper(isolated) {
                override fun getExternalFilesDir(type: String?) = File(isolated.filesDir, "external").apply { mkdirs() }
            }
            check(EmulatorStorage.appStateDir(context).canonicalPath.startsWith(isolated.filesDir.canonicalPath + File.separator))
            val repository = PerGameSettingsRepository(context)
            repository.save(PerGameSettings("original", "Original game", gamePlatform = GamePlatform.GBA))
            val file = File(EmulatorStorage.appStateDir(context), "per-game-settings.json")
            val good = file.readBytes()
            File(file.path + ".bak").writeBytes(good)
            file.writeText("{interrupted")
            assertNotNull(PerGameSettingsRepository(context).get("original"))
            repository.save(PerGameSettings("second", "Second game", gamePlatform = GamePlatform.NES))
            assertEquals(setOf("original", "second"), repository.getAll().map { it.gameKey }.toSet())
            val backup = repository.exportJson()
            repository.delete("original")
            repository.importJson(backup)
            assertEquals(2, repository.getAll().size)
            repository.deleteAll()
            assertTrue(repository.getAll().isEmpty())
            assertFalse(File(file.path + ".bak").exists())
        }
    }
}
