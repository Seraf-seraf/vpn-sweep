package com.seraf.vpnsweep.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal class VpnServiceLifecycle {
    private val lock = Any()
    private var connection: VpnRun? = null
    private var destroyed = false
    private var revokeEpoch = 0L
    private var revocations = 0
    private var revokeFinished = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var latestStartId = 0

    fun started(startId: Int): Unit = synchronized(lock) {
        latestStartId = startId
    }

    fun epoch(): Long? = synchronized(lock) { if (destroyed || revocations != 0) null else revokeEpoch }

    fun reserve(
        registry: VpnRunRegistry,
        epoch: Long,
        deferDelivery: (() -> Unit) -> Unit = { it() },
        onReserved: () -> Unit = {},
        stopping: () -> Unit,
    ): VpnRun? = synchronized(lock) {
        if (destroyed || revocations != 0 || revokeEpoch != epoch) return@synchronized null
        registry.reserve(deferDelivery, stopping)?.also {
            connection = it
            onReserved()
        }
    }

    fun current(): VpnRun? = synchronized(lock) { connection }

    fun hasActiveConnection(): Boolean = synchronized(lock) { connection?.isActive() == true }

    fun beginRevoke(): VpnRun? = synchronized(lock) {
        revokeEpoch++
        if (revocations == 0) revokeFinished = CompletableDeferred()
        revocations++
        connection
    }

    fun endRevoke(): Unit {
        val signal = synchronized(lock) {
            revocations--
            if (revocations == 0) revokeFinished else null
        }
        signal?.complete(Unit)
    }

    private suspend fun awaitRevocations(): Unit {
        while (true) {
            val pending = synchronized(lock) { if (revocations == 0) null else revokeFinished }
            if (pending == null) return
            pending.await()
        }
    }

    private fun hasRevocations(): Boolean = synchronized(lock) { revocations != 0 }

    private fun claimRelease(run: VpnRun): Boolean = synchronized(lock) {
        if (revocations != 0) return@synchronized false
        check(run.claimRelease()) { "vpn_session_not_current" }
        true
    }

    fun destroy(): VpnRun? = synchronized(lock) {
        destroyed = true
        connection
    }

    suspend fun cleanup(
        run: VpnRun,
        dispatcher: CoroutineDispatcher,
        stopForeground: () -> Unit,
        stopSelf: (Int) -> Boolean,
        complete: (Exception?) -> Unit,
    ): Unit {
        var failure: Exception? = null
        while (true) {
            awaitRevocations()
            val finished = withContext(dispatcher) {
                if (hasRevocations()) return@withContext false
                try {
                    cleanupOnce(run, stopForeground, stopSelf)
                } catch (exception: Exception) {
                    failure = exception
                }
                if (!claimRelease(run)) return@withContext false
                complete(failure)
                true
            }
            if (finished) return
        }
    }

    private fun cleanupOnce(run: VpnRun, stopForeground: () -> Unit, stopSelf: (Int) -> Boolean): Unit {
        if (synchronized(lock) { destroyed || connection !== run }) return
        var failure: Exception? = null
        try {
            stopForeground()
        } catch (exception: Exception) {
            failure = exception
        }
        val startId = synchronized(lock) { latestStartId }
        try {
            stopSelf(startId)
        } catch (exception: Exception) {
            failure = exception
        }
        failure?.let { throw it }
    }
}
