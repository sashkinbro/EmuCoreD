package com.sbro.emucored.ui.emulation

import java.nio.file.Files
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveStateUpdateTest {
    @Test fun occupiedUnchangedSlotDoesNotReportSaveSuccessAfterTimeout() = runBlocking {
        val file = Files.createTempFile("occupied-state", ".cst").toFile()
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertFalse(awaitSaveStateFileUpdate(file.lastModified(), 2, 1) { file })
        } finally { file.delete() }
    }

    @Test fun replacedSlotCompletesButEmptyOutputDoesNot() = runBlocking {
        val file = Files.createTempFile("new-state", ".cst").toFile()
        try {
            assertFalse(awaitSaveStateFileUpdate(0L, 1, 1) { file })
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(awaitSaveStateFileUpdate(0L, 1, 1) { file })
        } finally { file.delete() }
    }

    @Test fun shutdownCanCancelAnOutstandingSaveWait() = runBlocking {
        val waiter = launch { awaitSaveStateFileUpdate(0L, 40, 250) { null } }
        delay(5)
        waiter.cancelAndJoin()
        assertTrue(waiter.isCancelled)
    }
}
