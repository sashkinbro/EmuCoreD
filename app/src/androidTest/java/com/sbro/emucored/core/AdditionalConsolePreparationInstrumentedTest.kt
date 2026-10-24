package com.sbro.emucored.core

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.core.platform.ConsolePlatform
import com.sbro.emucored.core.platform.session.AdditionalConsoleSession
import com.sbro.emucored.core.platform.session.EngineLaunchRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdditionalConsolePreparationInstrumentedTest {
    @Test(timeout = 20_000) fun stopOwnsAndCancelsAPreparingImageBeforeAnyCoreCanBoot() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(app.cacheDir, "shared/preparation-${UUID.randomUUID()}").apply { mkdirs() }
        val image = File(folder, "pending.gba").apply { writeBytes(OriginalConsolePrograms.forPlatform(ConsolePlatform.GBA)) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", image)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = AtomicBoolean()
        testStorageContext().use { isolated ->
            val context = object : ContextWrapper(isolated) {
                override fun getApplicationContext(): Context = this
                override fun getContentResolver(): ContentResolver {
                    if (blocked.compareAndSet(false, true)) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    return super.getContentResolver()
                }
            }
            val opening = async(Dispatchers.IO) {
                AdditionalConsoleSession.start(EngineLaunchRequest(context, ConsolePlatform.GBA,
                    uri.toString(), uri.toString(), isolated.filesDir))
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertTrue("Stop cannot see ownership while the image is preparing", AdditionalConsoleSession.isActive)
                AdditionalConsoleSession.requestStop()
                release.countDown()
                try { opening.await(); fail("The stopped preparation booted a guest") } catch (_: CancellationException) { }
                AdditionalConsoleSession.stop()
                assertFalse(AdditionalConsoleSession.isActive)
                assertNull(AdditionalConsoleSession.session)
                assertArrayEquals(OriginalConsolePrograms.forPlatform(ConsolePlatform.GBA), image.readBytes())
            } finally {
                release.countDown()
                opening.cancelAndJoin()
                AdditionalConsoleSession.stop()
                folder.deleteRecursively()
            }
        }
    }
}
