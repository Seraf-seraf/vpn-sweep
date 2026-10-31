package com.seraf.vpnsweep.searchstate

import com.seraf.vpnsweep.importer.MAX_CONFIG_BYTES
import com.seraf.vpnsweep.searchprotocol.*
import com.seraf.vpnsweep.searchstorage.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultSizeTest {
    @Test
    fun emptyResultKeepsPreviousGoodAndClosesInputs(): Unit = assertRejected(0)

    @Test
    fun oversizedResultKeepsPreviousGoodAndClosesInputs(): Unit = assertRejected(MAX_CONFIG_BYTES + 1)

    @Test
    fun validBoundaryResultsCanReplacePreviousGood(): Unit {
        for (size in listOf(1, MAX_CONFIG_BYTES - 1, MAX_CONFIG_BYTES)) {
            val fixture = Fixture()
            val input = Input(ByteArray(size) { 2 })
            assertEquals(SearchResultReceiptV3.COMMITTED_CLOSED, fixture.offer(input))
            assertNotNull(fixture.store.resolve(ResultRevision("1", "2")))
            assertTrue(input.streamClosed)
            assertTrue(input.closed)
        }
    }

    @Test
    fun inputReadFailureBeforePublicationKeepsPreviousGood(): Unit {
        val fixture = Fixture()
        val input = Input(byteArrayOf(1), failRead = true)
        assertEquals(SearchResultReceiptV3.FAILED_CLOSED, fixture.offer(input))
        assertEquals(fixture.previous, fixture.store.resolve(fixture.previous.revision))
        assertTrue(input.streamClosed)
        assertTrue(input.closed)
    }

    private fun assertRejected(size: Int): Unit {
        val fixture = Fixture()
        val input = Input(ByteArray(size) { 1 })
        assertEquals(SearchResultReceiptV3.FAILED_CLOSED, fixture.offer(input))
        assertEquals(fixture.previous, fixture.store.resolve(fixture.previous.revision))
        assertTrue(input.streamClosed)
        assertTrue(input.closed)
    }

    private class Fixture {
        private val storage = Storage()
        val store = ResultStore(storage)
        val previous: ResultManifest
        private val port = Port()
        private val controller: SearchController

        init {
            val old = byteArrayOf(9)
            val token = checkNotNull(store.begin(ResultRevision("old-source", "old-selection")))
            check(store.publish(token, old.inputStream(), digest(old.inputStream())) == Publication.SUCCESS)
            previous = checkNotNull(store.resolve(token.revision))
            controller = SearchController(port, store, Marker())
            check(controller.start(1, 2, Input(byteArrayOf(7)), true) == SearchStartV3.ACCEPTED)
        }

        fun offer(input: Input): SearchResultReceiptV3 = port.observer.onResult(
            SearchResultOfferV3(port.generation, port.phase, 1, 2, digest(input.bytes.inputStream()), input),
        )
    }

    private class Input(val bytes: ByteArray, private val failRead: Boolean = false) : PrivateInputV1 {
        var closed = false
        var streamClosed = false

        override fun open(): InputStream = object : ByteArrayInputStream(bytes) {
            override fun read(): Int {
                if (failRead) throw IOException("synthetic_read_failure")
                return super.read()
            }

            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (failRead) throw IOException("synthetic_read_failure")
                return super.read(target, offset, length)
            }

            override fun close(): Unit { streamClosed = true; super.close() }
        }

        override fun close(): Unit { closed = true }
    }

    private class Marker : SessionMarker {
        private var state = MarkerState.CLEAR
        override fun read(): MarkerState = state
        override fun markActive(): Boolean { state = MarkerState.ACTIVE; return true }
        override fun markClear(): Boolean { state = MarkerState.CLEAR; return true }
    }

    private class Port : SearchSessionPortV4 {
        override fun acceptTerminal(terminal: SearchTerminalV3): Boolean = true
        lateinit var observer: SearchSessionObserverV3
        var generation = 0L
        var phase = 0L
        override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 {
            generation = snapshot.sessionGeneration
            phase = phaseEpoch
            this.observer = observer
            return SearchStartV3.ACCEPTED
        }
        override fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit = Unit
        override fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3 = SearchStartV3.REJECTED
        override fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit = Unit
    }

    private class Storage : ResultStorage {
        private val values = mutableMapOf<String, Pair<ResultRevision, ByteArray>>()
        private var next = 0
        private var manifest: ResultManifest? = null
        override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String {
            val generation = (++next).toString()
            values[generation] = revision to input.readBytes()
            return generation
        }
        override fun readGeneration(generation: String): StoredResult? {
            val (revision, bytes) = values[generation] ?: return null
            return StoredResult(revision, digest(bytes.inputStream()), bytes.inputStream())
        }
        override fun readManifest(): ResultManifest? = manifest
        override fun publish(manifest: ResultManifest): Unit { this.manifest = manifest }
        override fun discard(generation: String): Unit { values.remove(generation) }
    }
}
