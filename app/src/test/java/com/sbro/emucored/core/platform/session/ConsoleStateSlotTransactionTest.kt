package com.sbro.emucored.core.platform.session

import com.sbro.emucored.data.SaveStateFileIntegrity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConsoleStateSlotTransactionTest {
    private fun bytes(value: Int): ByteArray {
        val payload = ByteArray(128) { value.toByte() }
        var checksum = 1469598103934665603L
        payload.forEach { checksum = (checksum xor (it.toLong() and 255)) * 1099511628211L }
        return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putLong(0x3145544154534345)
            .putLong(1).putLong(checksum).putLong(payload.size.toLong()).array() + payload
    }
    private fun metadata() = JSONObject().put("platform", "GBA").put("gamePath", "owned-game").put("slot", 1)

    @Test fun cancelledQueuedSaveDoesNotInvokeTheNativeWriterOrReplaceTheCompletedSave() = runBlocking {
        val root = Files.createTempDirectory("state-cancelled-queue").toFile()
        try {
            val target = File(root, "slot.ecs")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = async {
                ConsoleStateSlotTransaction.save(target, {
                    it.writeBytes(bytes(1)); entered.complete(Unit); release.await(); true
                }, { metadata() })
            }
            entered.await()
            var secondWriterCalled = false
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                ConsoleStateSlotTransaction.save(target, {
                    secondWriterCalled = true; it.writeBytes(bytes(2)); true
                }, { metadata() })
            }
            second.cancel()
            release.complete(Unit)
            assertTrue(first.await())
            second.cancelAndJoin()
            assertFalse(secondWriterCalled)
            assertArrayEquals(bytes(1), target.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun importCannotReplaceAStateCreatedWhileItsNativeWriterWasRunning() = runBlocking {
        val root = Files.createTempDirectory("state-import-reservation").toFile()
        try {
            val target = File(root, "slot.ecs")
            val existingMetadata = metadata().put("sha256", "independent-save").toString()
            assertFalse(ConsoleStateSlotTransaction.saveNew(target, writeState = {
                it.writeBytes(bytes(2))
                target.writeBytes(bytes(1))
                File("${target.path}.json").writeText(existingMetadata)
                true
            }, metadata = { metadata() }))
            assertArrayEquals(bytes(1), target.readBytes())
            assertEquals(existingMetadata, File("${target.path}.json").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun incompleteRollbackSetDoesNotPartiallyOverwriteTheCurrentPair() {
        val root = Files.createTempDirectory("state-incomplete-rollback").toFile()
        try {
            val target = File(root, "slot.ecs").apply { writeBytes(bytes(2)) }
            val currentMetadata = metadata().put("sha256", SaveStateFileIntegrity.sha256(target)).toString()
            File("${target.path}.json").writeText(currentMetadata)
            File("${target.path}.previous").writeBytes(bytes(1))
            File("${target.path}.transaction.json").writeText(JSONObject().put("version", 1).put("phase", "prepared")
                .put("hadState", true).put("hadMetadata", true).put("hadPreview", false)
                .put("sha256", SaveStateFileIntegrity.sha256(target)).put("platform", "GBA").toString())
            assertFalse(ConsoleStateSlotTransaction.recover(target))
            assertArrayEquals(bytes(2), target.readBytes())
            assertEquals(currentMetadata, File("${target.path}.json").readText())
            assertTrue(File("${target.path}.transaction.json").isFile)
        } finally { root.deleteRecursively() }
    }

    @Test fun failedNativeWritesCorruptPayloadsAndMetadataFailuresPreserveThePreviousPair() = runBlocking {
        val root = Files.createTempDirectory("state-publication").toFile()
        try {
            val target = File(root, "slot.ecs")
            assertTrue(ConsoleStateSlotTransaction.save(target, { it.writeBytes(bytes(1)); true }, { metadata() }))
            val oldState = target.readBytes()
            val oldMetadata = File("${target.path}.json").readBytes()
            assertFalse(ConsoleStateSlotTransaction.save(target, { it.writeBytes(bytes(2)); false }, { metadata() }))
            assertArrayEquals(oldState, target.readBytes())
            assertFalse(ConsoleStateSlotTransaction.save(target, { it.writeBytes(byteArrayOf(1)); true }, { metadata() }))
            assertArrayEquals(oldState, target.readBytes())
            val blocked = File("${target.path}.transaction-metadata.pending").apply { assertTrue(mkdir()) }
            try {
                assertFalse(ConsoleStateSlotTransaction.save(target, { it.writeBytes(bytes(2)); true }, { metadata() }))
                assertArrayEquals(oldState, target.readBytes())
                assertArrayEquals(oldMetadata, File("${target.path}.json").readBytes())
            } finally { assertTrue(blocked.delete()) }
            assertTrue(ConsoleStateSlotTransaction.save(target, { it.writeBytes(bytes(3)); true }, { metadata() }))
            assertArrayEquals(bytes(3), target.readBytes())
            assertFalse(File("${target.path}.transaction.json").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun recoveryRollsBackAnInterruptedPairWithoutConsumingItsRollbackCopies() {
        val root = Files.createTempDirectory("state-recovery").toFile()
        try {
            val target = File(root, "slot.ecs").apply { writeBytes(bytes(2)) }
            val previous = File("${target.path}.previous").apply { writeBytes(bytes(1)) }
            val oldMetadata = metadata().put("sha256", SaveStateFileIntegrity.sha256(previous)).toString()
            File("${target.path}.previous.json").writeText(oldMetadata)
            File("${target.path}.json").writeText("interrupted metadata")
            File("${target.path}.transaction.json").writeText(JSONObject().put("version", 1).put("phase", "prepared")
                .put("hadState", true).put("hadMetadata", true).put("hadPreview", false)
                .put("sha256", SaveStateFileIntegrity.sha256(target)).put("platform", "GBA").toString())
            assertTrue(ConsoleStateSlotTransaction.recoverDirectory(root))
            assertArrayEquals(bytes(1), target.readBytes())
            assertEquals(oldMetadata, File("${target.path}.json").readText())
            assertTrue(ConsoleStateSlotTransaction.recover(target))
            assertFalse(File("${target.path}.transaction.json").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun loadsWaitForPublicationAndImportsCannotOverwriteAnOccupiedSlot() = runBlocking {
        val root = Files.createTempDirectory("state-concurrency").toFile()
        try {
            val target = File(root, "slot.ecs")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val save = async { ConsoleStateSlotTransaction.save(target, {
                it.writeBytes(bytes(2)); entered.complete(Unit); release.await(); true
            }, { metadata() }) }
            entered.await()
            val load = async { ConsoleStateSlotTransaction.load(target) { assertArrayEquals(bytes(2), it.readBytes()); true } }
            delay(20)
            assertFalse(load.isCompleted)
            assertFalse(ConsoleStateSlotTransaction.recover(target))
            release.complete(Unit)
            assertTrue(save.await())
            assertTrue(load.await())
            assertFalse(ConsoleStateSlotTransaction.saveNew(target, writeState = { it.writeBytes(bytes(3)); true }, metadata = { metadata() }))
            assertArrayEquals(bytes(2), target.readBytes())
        } finally { root.deleteRecursively() }
    }
}
