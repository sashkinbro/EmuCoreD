package com.sbro.emucored.data

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucored.core.testStorageContext
import com.sbro.emucored.data.n3ds.N3dsCatalogRepository
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NintendoCatalogMatchInstrumentedTest {
    @Test fun legacyExtractedCatalogCannotHideTheNewNineConsoleAsset() {
        testStorageContext().use { isolated ->
            val context = object : ContextWrapper(isolated) {
                override fun getNoBackupFilesDir() = File(isolated.filesDir, "no-backup").apply { mkdirs() }
            }
            val legacy = File(context.noBackupFilesDir, "n3ds_games.db")
            SQLiteDatabase.openOrCreateDatabase(legacy.path, null).use { db ->
                db.execSQL("CREATE TABLE games(igdb_id INTEGER PRIMARY KEY, platform TEXT)")
                db.execSQL("INSERT INTO games VALUES (1,'3DS')")
            }
            val original = legacy.readBytes()
            val repository = N3dsCatalogRepository(context)
            try {
                assertTrue(repository.ensureDatabaseReady())
                val extracted = File(context.noBackupFilesDir, N3dsCatalogRepository.DB_NAME)
                assertNotEquals(legacy.canonicalPath, extracted.canonicalPath)
                SQLiteDatabase.openDatabase(extracted.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val codes = listOf("3DS", "DS", "N64", "GAMECUBE", "WII", "GBA", "GB", "GBC", "NES", "SNES")
                    for (code in codes) {
                        db.rawQuery("SELECT COUNT(*) FROM games WHERE platform=?", arrayOf(code)).use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            assertTrue("Missing actual catalog records for $code", cursor.getInt(0) > 0)
                        }
                    }
                    db.rawQuery("SELECT COUNT(*) FROM games", null).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertTrue(cursor.getInt(0) >= 20_000)
                    }
                }
                assertArrayEquals(original, legacy.readBytes())
            } finally { repository.close() }
        }
    }

    @Test fun sameTitleAndSerialNeverCrossConsoleForExactOrFuzzyMatch() {
        testStorageContext().use { isolated ->
            val context = object : ContextWrapper(isolated) {
                override fun getNoBackupFilesDir() = File(isolated.filesDir, "no-backup").apply { mkdirs() }
            }
            val database = File(context.noBackupFilesDir, N3dsCatalogRepository.DB_NAME)
            val codes = mapOf(GamePlatform.N3DS to "3DS", GamePlatform.NDS to "DSI")
            SQLiteDatabase.openOrCreateDatabase(database.path, null).use { db ->
                db.execSQL("CREATE TABLE games(igdb_id INTEGER PRIMARY KEY, normalized_name TEXT, platform TEXT)")
                db.execSQL("CREATE TABLE game_serials(igdb_id INTEGER, serial TEXT)")
                db.execSQL("CREATE TABLE n64_games(igdb_id INTEGER PRIMARY KEY)")
                GamePlatform.entries.forEachIndexed { index, platform ->
                    db.execSQL("INSERT INTO games VALUES (?,?,?)", arrayOf<Any>(100L + index, "shared adventure returns", codes[platform] ?: platform.name))
                    db.execSQL("INSERT INTO game_serials VALUES (?,?)", arrayOf<Any>(100L + index, "SHARED-CODE"))
                }
                db.execSQL("INSERT INTO game_serials VALUES (100,'THREE-DS-ONLY')")
            }
            val repository = N3dsCatalogRepository(context)
            try {
                GamePlatform.entries.forEachIndexed { index, platform ->
                    val expected = 100L + index
                    assertEquals(platform.name, expected, repository.findBestMatchId(null, "Shared Adventure Returns", platform))
                    assertEquals(platform.name, expected, repository.findBestMatchId("SHARED-CODE", null, platform))
                    assertEquals(platform.name, expected, repository.findBestMatchId(null, "Shared Adventur Returns", platform))
                }
                assertNull(repository.findBestMatchId("THREE-DS-ONLY", null, GamePlatform.NDS))
                assertEquals(100L, repository.findBestMatchId("THREE-DS-ONLY", null))
            } finally { repository.close() }
        }
    }

    @Test fun nintendo64CatalogIncludesExclusiveAndSharedGames() {
        testStorageContext().use { isolated ->
            val context = object : ContextWrapper(isolated) {
                override fun getNoBackupFilesDir() = File(isolated.filesDir, "no-backup").apply { mkdirs() }
            }
            val repository = N3dsCatalogRepository(context)
            try {
                assertTrue(repository.getAvailablePlatforms().contains("N64"))
                val exclusive = repository.search("Donkey Kong 64", null, null, null, platform = "N64")
                assertTrue(exclusive.any { it.name == "Donkey Kong 64" && it.platform == "N64" })
                val shared = repository.search("Super Mario 64", null, null, null, platform = "N64")
                assertTrue(shared.any { it.igdbId == 1074L && it.platform == "N64" })
                assertTrue(repository.search("Super Mario 64", null, null, null, platform = "WII").any { it.igdbId == 1074L })
                assertEquals(1074L, repository.findBestMatchId(null, "Super Mario 64", GamePlatform.N64))
            } finally { repository.close() }
        }
    }
}
