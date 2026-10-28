package com.sbro.emucored.core

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sbro.emucored.data.AppPreferencesDataStoreOwner
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.CoreOptionPreferences
import com.sbro.emucored.core.platform.session.ConsoleSessionStorage
import com.sbro.emucored.data.SaveStateFileIntegrity
import com.sbro.emucored.data.drive.DriveBackupArchive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConsoleDriveBackupInstrumentedTest {
    @Test fun cloudSnapshotRestoresCoreOptionsAndUserSavesWithoutFirmware() = runBlocking {
        testStorageContext().use { isolated ->
            val storeJob = SupervisorJob()
            val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob),
                produceFile = { File(isolated.filesDir, "isolated-drive.preferences_pb") })
            val context = object : ContextWrapper(isolated), AppPreferencesDataStoreOwner {
                override val appPreferencesDataStore = dataStore
                override fun getApplicationContext(): Context = this
                override fun getNoBackupFilesDir(): File = File(filesDir, "no-backup").apply { mkdirs() }
                override fun getExternalFilesDir(type: String?): File = File(filesDir, "external/${type.orEmpty()}").apply { mkdirs() }
                override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(getExternalFilesDir(type))
            }
            try {
            val options = CoreOptionPreferences(context)
            assertTrue(options.setGlobal(ConsolePlatform.GBA, "frameskip", "3"))
            val state = ConsoleSessionStorage.stateFile(context.filesDir, ConsolePlatform.GBA, "owned-game", 1)
            state.parentFile!!.mkdirs()
            val payload = ByteArray(128) { it.toByte() }
            var checksum = 1469598103934665603L
            payload.forEach { checksum = (checksum xor (it.toLong() and 255)) * 1099511628211L }
            val original = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putLong(0x3145544154534345)
                .putLong(42).putLong(checksum).putLong(payload.size.toLong()).array() + payload
            state.writeBytes(original)
            File("${state.path}.json").writeText(JSONObject().put("platform", "GBA").put("gamePath", "owned-game")
                .put("slot", 1).put("sha256", SaveStateFileIntegrity.sha256(state)).toString())
            val battery = File(context.filesDir, "consoles/gba/${ConsoleSessionStorage.identityKey("owned-game")}/battery.sav")
                .apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(12, 34, 56)) }
            val n64State = ConsoleSessionStorage.stateFile(context.filesDir, ConsolePlatform.N64, "owned-n64", 1)
                .apply { parentFile!!.mkdirs(); writeBytes(original) }
            File("${n64State.path}.json").writeText(JSONObject().put("platform", "N64").put("gamePath", "owned-n64")
                .put("slot", 1).put("sha256", SaveStateFileIntegrity.sha256(n64State)).toString())
            val n64Battery = File(context.filesDir, "consoles/n64/${ConsoleSessionStorage.identityKey("owned-n64")}/battery.srm")
                .apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(65, 66, 67)) }
            File(context.filesDir, "consoles/firmware/disksys.rom").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(8192)) }
            val archive = DriveBackupArchive(context) { "isolated-test-device" }
            val snapshot = archive.create(includeTextures = false)
            ZipFile(snapshot.file).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertTrue(names.contains("core-options.json"))
                assertTrue(names.any { it.startsWith("console-states/gba/") && it.endsWith(".ecs") })
                assertTrue(names.any { it.startsWith("console-data/gba/") && it.endsWith("battery.sav") })
                assertTrue(names.any { it.startsWith("console-states/n64/") && it.endsWith(".ecs") })
                assertTrue(names.any { it.startsWith("console-data/n64/") && it.endsWith("battery.srm") })
                assertFalse(names.any { it.contains("firmware") || it.endsWith(".rom") })
            }
            options.setGlobal(ConsolePlatform.GBA, "frameskip", "0")
            state.writeBytes(byteArrayOf(0))
            battery.writeBytes(byteArrayOf(99))
            n64State.writeBytes(byteArrayOf(0))
            n64Battery.writeBytes(byteArrayOf(0))
            val manifest = archive.extract(snapshot.file)
            archive.restore(manifest, setOf("settings", "save-states", "memory-cards"))
            assertEquals("3", options.effectiveOptions(ConsolePlatform.GBA)["frameskip"])
            assertArrayEquals(original, state.readBytes())
            assertArrayEquals(byteArrayOf(12, 34, 56), battery.readBytes())
            assertArrayEquals(original, n64State.readBytes())
            assertArrayEquals(byteArrayOf(65, 66, 67), n64Battery.readBytes())
            } finally { storeJob.cancelAndJoin() }
        }
    }
}
