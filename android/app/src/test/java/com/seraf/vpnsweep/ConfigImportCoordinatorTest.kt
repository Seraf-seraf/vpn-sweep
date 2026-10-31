package com.seraf.vpnsweep

import com.seraf.vpnsweep.importer.ConfigImportCoordinator
import com.seraf.vpnsweep.importer.ImportUiOwner
import com.seraf.vpnsweep.importer.MAX_CONFIG_BYTES
import com.seraf.vpnsweep.importer.readImportedConfig
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigImportCoordinatorTest {
    @Test
    fun olderReadCannotOverwriteACompletedNewerImport(): Unit = runBlocking {
        val coordinator = ConfigImportCoordinator()
        val store = FakeImportStore()
        val firstToken = coordinator.begin()
        val reading = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val first = async(Dispatchers.IO) {
            coordinator.complete(firstToken, {
                reading.countDown()
                await(releaseRead)
                "old"
            }, {}, store::write)
        }
        await(reading)
        val secondToken = coordinator.begin()
        assertTrue(coordinator.complete(secondToken, { "new" }, {}, store::write))
        releaseRead.countDown()
        assertFalse(first.await())
        assertEquals("new", store.saved)
        assertEquals(listOf("new"), store.commits)
    }

    @Test
    fun cancelledOldScopeCannotCommitAfterRecreation(): Unit = runBlocking {
        val coordinator = ConfigImportCoordinator()
        val store = FakeImportStore()
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reading = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val first = oldScope.launch {
            val token = coordinator.begin()
            coordinator.complete(token, {
                reading.countDown()
                await(releaseRead)
                "old"
            }, {}, store::write)
        }
        await(reading)
        oldScope.cancel()
        val newToken = coordinator.begin()
        assertTrue(coordinator.complete(newToken, { "new" }, {}, store::write))
        releaseRead.countDown()
        first.join()
        assertTrue(first.isCancelled)
        assertEquals("new", store.saved)
        assertEquals(listOf("new"), store.commits)
    }

    @Test
    fun cancellationDuringWriteRollsBackBeforeNextImport(): Unit = runBlocking {
        val coordinator = ConfigImportCoordinator()
        val store = FakeImportStore(saved = "previous")
        val writing = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        store.beforeCommit = {
            writing.countDown()
            await(releaseWrite)
        }
        val firstToken = coordinator.begin()
        val first = launch(Dispatchers.IO) {
            coordinator.complete(firstToken, { "old" }, {}, store::write)
        }
        await(writing)
        val secondToken = async(start = CoroutineStart.UNDISPATCHED) { coordinator.begin() }
        assertFalse(secondToken.isCompleted)
        first.cancel()
        releaseWrite.countDown()
        first.join()
        val token = secondToken.await()
        store.beforeCommit = {}
        assertTrue(coordinator.complete(token, { "new" }, {}, store::write))
        assertEquals("new", store.saved)
        assertEquals(listOf("new"), store.commits)
        assertEquals(1, store.rollbacks)
        assertNull(store.pending)
    }

    @Test
    fun nextImportWaitsForAnAlreadyStartedWrite(): Unit = runBlocking {
        val coordinator = ConfigImportCoordinator()
        val store = FakeImportStore(saved = "previous")
        val writing = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        store.beforeCommit = {
            writing.countDown()
            await(releaseWrite)
        }
        val firstToken = coordinator.begin()
        val first = async(Dispatchers.IO) {
            coordinator.complete(firstToken, { "old" }, {}, store::write)
        }
        await(writing)
        val secondToken = async(start = CoroutineStart.UNDISPATCHED) { coordinator.begin() }
        assertFalse(secondToken.isCompleted)
        releaseWrite.countDown()
        assertTrue(first.await())
        val token = secondToken.await()
        store.beforeCommit = {}
        assertTrue(coordinator.complete(token, { "new" }, {}, store::write))
        assertEquals(listOf("old", "new"), store.commits)
        assertEquals("new", store.saved)
    }

    @Test
    fun cancellationAfterCommitDoesNotUndoTheCommittedFile(): Unit = runBlocking {
        val coordinator = ConfigImportCoordinator()
        val store = FakeImportStore(saved = "previous")
        val committed = CompletableDeferred<Unit>()
        val delivery = CompletableDeferred<Unit>()
        var delivered = false
        val token = coordinator.begin()
        val job = launch(Dispatchers.IO) {
            coordinator.complete(token, { "new" }, {}, store::write)
            committed.complete(Unit)
            delivery.await()
            delivered = true
        }
        committed.await()
        job.cancel()
        job.join()
        assertEquals("new", store.saved)
        assertFalse(delivered)
        assertNull(store.pending)
    }

    @Test
    fun inputIsClosedAtSizeBoundaryAndOnFailure(): Unit {
        val empty = RecordingInputStream(ByteArray(0))
        assertEquals(0, readImportedConfig(empty).size)
        assertTrue(empty.closed)
        val exact = RecordingInputStream(ByteArray(MAX_CONFIG_BYTES))
        assertEquals(MAX_CONFIG_BYTES, readImportedConfig(exact).size)
        assertTrue(exact.closed)
        val oversized = RecordingInputStream(ByteArray(MAX_CONFIG_BYTES + 1))
        assertThrows(IllegalStateException::class.java) { readImportedConfig(oversized) }
        assertTrue(oversized.closed)
        var failureClosed = false
        val failure = object : InputStream() {
            override fun read(): Int = throw IOException("read")
            override fun close(): Unit { failureClosed = true }
        }
        assertThrows(IOException::class.java) { readImportedConfig(failure) }
        assertTrue(failureClosed)
        assertThrows(IllegalStateException::class.java) { readImportedConfig(null) }
    }

    @Test
    fun failedReadValidationAndWritePreservePreviousValue(): Unit = runBlocking {
        val coordinator = ConfigImportCoordinator()
        val store = FakeImportStore(saved = "previous")
        val failedRead = coordinator.begin()
        expectIOException { coordinator.complete(failedRead, { throw IOException("read") }, {}, store::write) }
        val failedValidation = coordinator.begin()
        expectIOException { coordinator.complete(failedValidation, { "bad" }, { throw IOException("validation") }, store::write) }
        store.beforeCommit = { throw IOException("write") }
        val failedWrite = coordinator.begin()
        expectIOException { coordinator.complete(failedWrite, { "bad" }, {}, store::write) }
        assertEquals("previous", store.saved)
        assertEquals(1, store.rollbacks)
        assertNull(store.pending)
    }

    @Test
    fun staleUiOwnerCannotFinishOrOverwriteNewImport(): Unit {
        val owners = ImportUiOwner()
        val first = owners.begin()
        val second = owners.begin()
        assertFalse(owners.isCurrent(first))
        assertFalse(owners.finish(first))
        assertTrue(owners.isCurrent(second))
        assertTrue(owners.finish(second))
        assertFalse(owners.isCurrent(second))
    }

    private suspend fun expectIOException(block: suspend () -> Unit): Unit {
        try {
            block()
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
        }
    }

    private fun await(latch: CountDownLatch): Unit {
        assertTrue("Timed out waiting for test barrier", latch.await(10, TimeUnit.SECONDS))
    }

    private class FakeImportStore(var saved: String = "") {
        var pending: String? = null
        var rollbacks: Int = 0
        val commits = mutableListOf<String>()
        var beforeCommit: () -> Unit = {}

        fun write(value: String, checkBeforeCommit: () -> Unit): Unit {
            pending = value
            try {
                beforeCommit()
                checkBeforeCommit()
                saved = value
                commits.add(value)
                pending = null
            } catch (failure: Exception) {
                pending = null
                rollbacks++
                throw failure
            }
        }
    }

    private class RecordingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed: Boolean = false

        override fun close(): Unit {
            closed = true
            super.close()
        }
    }
}
