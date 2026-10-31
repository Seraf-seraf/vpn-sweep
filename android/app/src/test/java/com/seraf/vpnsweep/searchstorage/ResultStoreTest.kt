package com.seraf.vpnsweep.searchstorage

import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultStoreTest {
    private val firstRevision = ResultRevision("source-1", "selection-1")
    private val nextRevision = ResultRevision("source-2", "selection-2")

    @Test
    fun cancellationBeforePublicationKeepsPreviousGeneration(): Unit {
        val storage = FakeStorage()
        val store = ResultStore(storage)
        val previous = "previous".toByteArray()
        val token = store.begin(firstRevision)!!
        assertEquals(Publication.SUCCESS, store.publish(token, previous.inputStream(), digest(previous.inputStream())))
        val next = store.begin(nextRevision)!!
        storage.afterPrepare = { assertTrue(store.cancel(next)) }
        val replacement = "replacement".toByteArray()
        assertEquals(Publication.CANCELLED, store.publish(next, replacement.inputStream(), digest(replacement.inputStream())))
        assertEquals(firstRevision, store.resolve(firstRevision)?.revision)
        assertNull(store.resolve(nextRevision))
        assertEquals(setOf("0"), storage.generations.keys)
        assertEquals(listOf("1"), storage.discarded)
        assertFalse(store.cancel(token))
    }

    @Test
    fun recoveryRequiresBothRevisionsAndCompleteMatchingGeneration(): Unit {
        val storage = FakeStorage()
        val store = ResultStore(storage)
        val bytes = "private".toByteArray()
        val invalid = store.begin(firstRevision)!!
        assertEquals(Publication.INVALID, store.publish(invalid, bytes.inputStream(), digest("other".byteInputStream())))
        assertFalse(store.cancel(invalid))
        assertNull(store.resolve(firstRevision))
        assertEquals(Publication.SUCCESS, store.publish(store.begin(firstRevision)!!, bytes.inputStream(), digest(bytes.inputStream())))
        assertEquals(firstRevision, ResultStore(storage).resolve(firstRevision)?.revision)
        assertNull(ResultStore(storage).resolve(firstRevision.copy(selection = "changed")))
        assertNull(ResultStore(storage).resolve(firstRevision.copy(source = "changed")))
        val old = storage.generations[storage.manifest!!.generation]!!
        storage.generations[storage.manifest!!.generation] = FakeResult(old.revision, old.sha256, "truncated".toByteArray())
        assertNull(ResultStore(storage).resolve(firstRevision))
        storage.generations.clear()
        assertNull(ResultStore(storage).resolve(firstRevision))
    }

    @Test
    fun publicationErrorUsesReadbackAndFailsClosedWhenUnknown(): Unit {
        val storage = FakeStorage()
        val store = ResultStore(storage)
        val bytes = "private".toByteArray()
        storage.failure = 1
        assertEquals(Publication.FAILED, store.publish(store.begin(firstRevision)!!, bytes.inputStream(), digest(bytes.inputStream())))
        storage.failure = 2
        assertEquals(Publication.SUCCESS, store.publish(store.begin(firstRevision)!!, bytes.inputStream(), digest(bytes.inputStream())))
        assertEquals(firstRevision, store.resolve(firstRevision)?.revision)
        storage.failure = 3
        assertEquals(Publication.INDETERMINATE, store.publish(store.begin(nextRevision)!!, bytes.inputStream(), digest(bytes.inputStream())))
        assertNull(store.resolve(firstRevision))
        assertNull(store.begin(firstRevision))
        assertTrue(storage.generations.containsKey(storage.manifest!!.generation))
    }

    @Test
    fun revokedTokenDoesNotPrepareAndCommittedTokenCannotCancel(): Unit {
        val storage = FakeStorage()
        val store = ResultStore(storage)
        val bytes = "value".toByteArray()
        val cancelled = store.begin(firstRevision)!!
        assertTrue(store.cancel(cancelled))
        assertEquals(Publication.CANCELLED, store.publish(cancelled, bytes.inputStream(), digest(bytes.inputStream())))
        assertTrue(storage.generations.isEmpty())
        val committed = store.begin(firstRevision)!!
        assertEquals(Publication.SUCCESS, store.publish(committed, bytes.inputStream(), digest(bytes.inputStream())))
        assertFalse(store.cancel(committed))
        assertTrue(storage.discarded.isEmpty())
        assertEquals(firstRevision, store.resolve(firstRevision)?.revision)
    }

    @Test
    fun uncertainPrepareCleanupFailsClosed(): Unit {
        val storage = FakeStorage()
        val store = ResultStore(storage)
        val token = store.begin(firstRevision)!!
        storage.prepareFailure = PreparationCleanupUnknownException(IOException("synthetic"))
        assertEquals(Publication.INDETERMINATE, store.publish(token, "value".byteInputStream(), digest("value".byteInputStream())))
        assertNull(store.begin(firstRevision))
        assertNull(store.resolve(firstRevision))
    }

    @Test
    fun failedDiscardIsIndeterminateAndBlocksNewPublication(): Unit {
        val storage = FakeStorage()
        val store = ResultStore(storage)
        val token = store.begin(firstRevision)!!
        storage.afterPrepare = { assertTrue(store.cancel(token)) }
        storage.discardFailure = true
        assertEquals(Publication.INDETERMINATE, store.publish(token, "value".byteInputStream(), digest("value".byteInputStream())))
        assertNull(store.begin(firstRevision))
    }

    @Test
    fun cancellationDuringValidationOutranksReadErrorAndHashMismatch(): Unit {
        for (readFailure in listOf(true, false)) {
            val storage = FakeStorage()
            val store = ResultStore(storage)
            val token = store.begin(firstRevision)!!
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            storage.beforeRead = {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                if (readFailure) throw IOException("synthetic")
            }
            val bytes = "value".toByteArray()
            val expected = if (readFailure) digest(bytes.inputStream()) else digest("different".byteInputStream())
            var outcome: Publication? = null
            val worker = thread { outcome = store.publish(token, bytes.inputStream(), expected) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(store.cancel(token))
            release.countDown()
            worker.join(10_000)
            assertFalse(worker.isAlive)
            assertEquals(Publication.CANCELLED, outcome)
            assertTrue(storage.generations.isEmpty())
            assertEquals(listOf("0"), storage.discarded)
        }
    }

    private class FakeResult(val revision: ResultRevision, val sha256: String, val bytes: ByteArray)

    private class FakeStorage : ResultStorage {
        val generations = mutableMapOf<String, FakeResult>()
        var manifest: ResultManifest? = null
        var afterPrepare: () -> Unit = {}
        var beforeRead: () -> Unit = {}
        var failure = 0
        var prepareFailure: IOException? = null
        var unreadable = false
        val discarded = mutableListOf<String>()
        var discardFailure = false
        override fun discard(generation: String): Unit {
            if (discardFailure) throw IOException("synthetic")
            discarded.add(generation)
            generations.remove(generation)
        }
        override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String {
            prepareFailure?.let { throw it }
            val id = generations.size.toString()
            generations[id] = FakeResult(revision, sha256, input.readBytes())
            afterPrepare()
            return id
        }
        override fun readGeneration(generation: String): StoredResult? {
            beforeRead()
            return generations[generation]?.let { StoredResult(it.revision, it.sha256, ByteArrayInputStream(it.bytes)) }
        }
        override fun readManifest(): ResultManifest? {
            if (unreadable) throw IOException()
            return manifest
        }
        override fun publish(manifest: ResultManifest): Unit {
            if (failure == 1) throw IOException()
            this.manifest = manifest
            if (failure == 3) unreadable = true
            if (failure != 0) throw IOException()
        }
    }
}
