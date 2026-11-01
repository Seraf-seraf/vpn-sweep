package com.seraf.vpnsweep.searchstorage

import java.io.IOException
import java.io.InputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CurrentRevisionStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun absentRevisionIsDistinctFromInvalidOrUnreadableStorage(): Unit {
        val parent = temporary.newFolder()
        val root = File(parent, "revisions")
        val storage = PrivateRevisionStorage(root) { }
        assertEquals(CurrentRevision.Absent, CurrentRevisionStore(storage).readCurrent())
        assertTrue(root.mkdir())
        assertEquals(CurrentRevision.Absent, CurrentRevisionStore(storage).readCurrent())
        assertTrue(File(root, "current").mkdir())
        assertEquals(CurrentRevision.Unknown, CurrentRevisionStore(storage).readCurrent())

        val unreadable = PrivateRevisionStorage(File(parent, "missing")) { throw IOException("synthetic") }
        assertEquals(CurrentRevision.Unknown, CurrentRevisionStore(unreadable).readCurrent())
    }

    @Test
    fun selectionChangeSurvivesRestartAndDisablesPreviousResult(): Unit {
        val revisionStorage = FakeRevisions()
        val results = FakeResults()
        val revisions = CurrentRevisionStore(revisionStorage)
        val first = ResultRevision("source", "selection-1")
        val next = ResultRevision("source", "selection-2")
        val resultStore = ResultStore(results)
        assertTrue(revisions.update(first))
        val bytes = "private".toByteArray()
        assertEquals(Publication.SUCCESS, resultStore.publish(resultStore.begin(first)!!, bytes.inputStream(), digest(bytes.inputStream())))
        assertTrue(revisions.update(next))
        val recovered = CurrentRevisionStore(revisionStorage).load()!!
        assertEquals(next, recovered)
        assertNull(ResultStore(results).resolve(recovered))
        assertNotNull(ResultStore(results).resolve(first))
    }

    @Test
    fun uncertainWriteAndReadFailureFailClosed(): Unit {
        val backend = FakeRevisions()
        val store = CurrentRevisionStore(backend)
        backend.fail = true
        assertFalse(store.update(ResultRevision("source", "selection")))
        assertNull(store.load())
        assertNull(CurrentRevisionStore(backend).load())
    }

    private class FakeRevisions : RevisionStorage {
        var current: ResultRevision? = null
        var fail = false
        override fun read(): ResultRevision? {
            if (fail) throw IOException()
            return current
        }
        override fun write(revision: ResultRevision): Unit {
            if (fail) throw IOException()
            current = revision
        }
    }

    private class FakeResults : ResultStorage {
        var bytes = ByteArray(0)
        var manifest: ResultManifest? = null
        override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String {
            bytes = input.readBytes()
            manifestOrPrepared = ResultManifest("generation", revision, sha256)
            return "generation"
        }
        override fun readGeneration(generation: String): StoredResult? = manifestOrPrepared?.let {
            StoredResult(it.revision, it.sha256, bytes.inputStream())
        }
        private var manifestOrPrepared: ResultManifest? = null
        override fun readManifest(): ResultManifest? = manifest
        override fun publish(manifest: ResultManifest): Unit { this.manifest = manifest }
        override fun discard(generation: String): Unit = Unit
    }
}
