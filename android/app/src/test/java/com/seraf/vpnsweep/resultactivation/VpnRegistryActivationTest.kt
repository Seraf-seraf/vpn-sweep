package com.seraf.vpnsweep.resultactivation

import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchResourceStateV2
import com.seraf.vpnsweep.searchprotocol.SearchResultFenceV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionObserverV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionPortV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionPortV4
import com.seraf.vpnsweep.searchprotocol.SearchSnapshotV1
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import com.seraf.vpnsweep.searchprotocol.SearchTerminalV3
import com.seraf.vpnsweep.searchstate.MarkerState
import com.seraf.vpnsweep.searchstate.SearchController
import com.seraf.vpnsweep.searchstate.SearchStartPreparation
import com.seraf.vpnsweep.searchstate.SessionMarker
import com.seraf.vpnsweep.searchstorage.CurrentRevisionStore
import com.seraf.vpnsweep.searchstorage.Publication
import com.seraf.vpnsweep.searchstorage.ResultManifest
import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.searchstorage.ResultStorage
import com.seraf.vpnsweep.searchstorage.ResultStore
import com.seraf.vpnsweep.searchstorage.RevisionStorage
import com.seraf.vpnsweep.searchstorage.StoredResult
import com.seraf.vpnsweep.searchstorage.digest
import com.seraf.vpnsweep.vpn.VpnRun
import com.seraf.vpnsweep.vpn.VpnRunFailure
import com.seraf.vpnsweep.vpn.VpnRunOperations
import com.seraf.vpnsweep.vpn.VpnRunRegistry
import com.seraf.vpnsweep.vpn.VpnServiceLifecycle
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRegistryActivationTest {
    @Test
    fun admissionsRemainClosedWhileVpnStopIsInProgress(): Unit = runBlocking {
        val fixture = Fixture()
        val admission = fixture.admit()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val operations = Operations(fixture.lifecycle, onStop = {
            entered.countDown()
            check(release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        })
        val job = admission.reservation.launch(CoroutineScope(coroutineContext + Dispatchers.IO), operations)
        try {
            assertTrue(entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals(listOf("start", "stop"), operations.events)
            assertTrue(admission.reservation.isActive())
            assertFalse(fixture.registry.available())
            assertEquals(MarkerState.CLEAR, fixture.marker.state)
            fixture.assertSearchBusy()
            fixture.assertPowerUnavailable()
            assertEquals(0, fixture.port.starts)
        } finally {
            release.countDown()
            withTimeout(TIMEOUT_MS) { job.join() }
        }
        assertNull(operations.failure)
        assertTrue(fixture.registry.available())
        val profiles = Input()
        val preparation = fixture.activation.reserveSearch(1, 2, profiles) as SearchStartPreparation.Reserved
        assertEquals(SearchStartV3.ACCEPTED, preparation.dispatch())
        assertTrue(profiles.closed)
        fixture.controller.cancel()
        fixture.port.finishCancellation()
        assertEquals(MarkerState.CLEAR, fixture.marker.state)
    }

    @Test
    fun searchWaitsForConfirmedVpnCleanupAndRegistryRelease(): Unit = runBlocking {
        val fixture = Fixture()
        val admission = fixture.admit()
        assertArrayEquals(fixture.result, admission.bytes)
        assertFalse(fixture.registry.available())
        fixture.assertSearchBusy()
        fixture.assertPowerUnavailable()

        val operations = Operations(fixture.lifecycle)
        fixture.finish(admission.reservation, operations, this)
        assertNull(operations.failure)
        assertEquals(listOf("start", "stop", "close", "foreground", "service", "finished"), operations.events)
        assertTrue(fixture.registry.available())
        assertFalse(admission.reservation.isActive())

        val profiles = Input()
        val preparation = fixture.activation.reserveSearch(1, 2, profiles) as SearchStartPreparation.Reserved
        assertEquals(SearchStartV3.ACCEPTED, preparation.dispatch())
        assertTrue(profiles.closed)
        assertEquals(1, fixture.port.starts)
        assertEquals(MarkerState.ACTIVE, fixture.marker.state)
        fixture.controller.cancel()
        fixture.port.finishCancellation()
        assertEquals(MarkerState.CLEAR, fixture.marker.state)
    }

    @Test
    fun failedVpnStopOrCloseQuarantinesBothAdmissions(): Unit = runBlocking {
        for (failedPhase in listOf("stop", "close")) {
            val fixture = Fixture()
            val admission = fixture.admit()
            val operations = Operations(fixture.lifecycle, failedPhase)
            fixture.finish(admission.reservation, operations, this)

            assertSame(if (failedPhase == "stop") VpnRunFailure.Stop else VpnRunFailure.Close, operations.failure)
            assertEquals(listOf("start", "stop", "close", "foreground", "service", "finished"), operations.events)
            assertFalse(admission.reservation.isActive())
            assertFalse(fixture.registry.available())
            assertEquals(MarkerState.CLEAR, fixture.marker.state)
            fixture.assertSearchBusy()
            fixture.assertPowerUnavailable()
            assertEquals(0, fixture.port.starts)
        }
    }

    private class Fixture {
        val result = "verified-result".toByteArray()
        val revision = ResultRevision("1", "2")
        val storage = Storage()
        val store = ResultStore(storage)
        val revisions = CurrentRevisionStore(Revisions())
        val marker = Marker()
        val port = Port()
        val controller = SearchController(port, store, marker)
        val registry = VpnRunRegistry()
        val lifecycle = VpnServiceLifecycle()
        val activation = ResultActivation(controller, store, storage, revisions, marker, registry::available)

        init {
            check(revisions.update(revision))
            val token = checkNotNull(store.begin(revision))
            check(store.publish(token, result.inputStream(), digest(result.inputStream())) == Publication.SUCCESS)
            lifecycle.started(1)
        }

        fun admit(): VpnAdmission.Admitted<VpnRun> {
            val epoch = checkNotNull(lifecycle.epoch())
            val outcome = activation.admitVpn { lifecycle.reserve(registry, epoch, stopping = {}) }
            check(outcome is VpnAdmission.Admitted)
            return outcome
        }

        fun assertSearchBusy(): Unit {
            val profiles = Input()
            val outcome = activation.reserveSearch(1, 2, profiles) as SearchStartPreparation.Denied
            assertEquals(SearchStartV3.BUSY, outcome.result)
            assertFalse(profiles.closed)
            profiles.close()
            assertTrue(profiles.closed)
        }

        fun assertPowerUnavailable(): Unit {
            var reserved = false
            val outcome = activation.admitVpn { reserved = true; Any() }
            assertTrue(outcome is VpnAdmission.Unavailable)
            assertFalse(reserved)
        }

        suspend fun finish(run: VpnRun, operations: Operations, scope: CoroutineScope): Unit {
            val job = run.launch(scope, operations)
            withTimeout(TIMEOUT_MS) { job.join() }
        }
    }

    private class Operations(
        private val lifecycle: VpnServiceLifecycle,
        private val failedPhase: String? = null,
        private val onStop: () -> Unit = {},
    ) : VpnRunOperations<String, String> {
        val events = mutableListOf<String>()
        var failure: VpnRunFailure? = null

        override fun prepare(run: VpnRun): String = "prepared"
        override fun setUnderlying(prepared: String): Unit = Unit
        override fun establish(prepared: String): String = "tunnel"
        override fun start(prepared: String, tunnel: String): Unit { events.add("start") }
        override suspend fun monitor(prepared: String, run: VpnRun): Unit = Unit

        override fun stop(): Unit {
            events.add("stop")
            onStop()
            if (failedPhase == "stop") throw IOException("synthetic")
        }

        override fun close(tunnel: String): Unit {
            events.add("close")
            if (failedPhase == "close") throw IOException("synthetic")
        }

        override suspend fun cleanupService(run: VpnRun, complete: (Exception?) -> Unit): Unit {
            lifecycle.cleanup(
                run = run,
                dispatcher = Dispatchers.Unconfined,
                stopForeground = { events.add("foreground") },
                stopSelf = { events.add("service"); true },
                complete = complete,
            )
        }

        override fun finished(failure: VpnRunFailure?): Unit {
            this.failure = failure
            events.add("finished")
        }
    }

    private class Revisions : RevisionStorage {
        var revision: ResultRevision? = null
        override fun read(): ResultRevision? = revision
        override fun write(revision: ResultRevision): Unit { this.revision = revision }
    }

    private class Storage : ResultStorage {
        private var stored: Stored? = null
        private var manifest: ResultManifest? = null

        override fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String {
            stored = Stored(revision, sha256, input.readBytes())
            return "generation"
        }

        override fun readGeneration(generation: String): StoredResult? {
            if (generation != "generation") return null
            val value = stored ?: return null
            return StoredResult(value.revision, value.sha256, value.bytes.inputStream())
        }

        override fun readManifest(): ResultManifest? = manifest
        override fun publish(manifest: ResultManifest): Unit { this.manifest = manifest }
        override fun discard(generation: String): Unit { stored = null }

        private class Stored(val revision: ResultRevision, val sha256: String, val bytes: ByteArray)
    }

    private class Marker : SessionMarker {
        var state = MarkerState.CLEAR
        override fun read(): MarkerState = state
        override fun markActive(): Boolean { state = MarkerState.ACTIVE; return true }
        override fun markClear(): Boolean { state = MarkerState.CLEAR; return true }
    }

    private class Input : PrivateInputV1 {
        var closed = false
        override fun open(): InputStream = "profiles".byteInputStream()
        override fun close(): Unit { closed = true }
    }

    private class Port : SearchSessionPortV4 {
        override fun acceptTerminal(terminal: SearchTerminalV3): Boolean = true
        var starts = 0
        private lateinit var observer: SearchSessionObserverV3
        private var generation = 0L
        private var phase = 0L

        override fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3 {
            starts++
            generation = snapshot.sessionGeneration
            phase = phaseEpoch
            this.observer = observer
            snapshot.profiles.close()
            return SearchStartV3.ACCEPTED
        }

        override fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit = Unit
        override fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3 = SearchStartV3.REJECTED
        override fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit = Unit

        fun finishCancellation(): Unit = observer.onTerminal(
            SearchTerminalV3.Cancelled(generation, phase, SearchResourceStateV2.RELEASED, SearchResultFenceV3.CONFIRMED),
        )
    }
}

private const val TIMEOUT_MS = 10_000L
