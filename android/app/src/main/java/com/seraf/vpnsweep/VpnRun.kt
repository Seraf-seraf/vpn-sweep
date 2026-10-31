package com.seraf.vpnsweep

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal class VpnRunRegistry {
    private val lock = Any()
    private var current: VpnRun? = null

    fun reserve(): VpnRun? = guarded {
        if (current != null) return@guarded null
        VpnRun(this).also { current = it }
    }

    fun <T> guarded(action: () -> T): T = synchronized(lock, action)

    fun release(run: VpnRun): Unit = guarded {
        if (current === run) current = null
        run.finishLocked()
    }
}

internal sealed interface VpnRunFailure {
    class Operation(val exception: Exception) : VpnRunFailure
    data object Stop : VpnRunFailure
    data object Close : VpnRunFailure
}

internal interface VpnRunOperations<P : Any, T : Any> {
    fun prepare(run: VpnRun): P
    fun setUnderlying(prepared: P): Unit
    fun establish(prepared: P): T
    fun start(prepared: P, tunnel: T): Unit
    suspend fun monitor(prepared: P, run: VpnRun): Unit
    fun stopping(): Unit
    fun stop(): Unit
    fun close(tunnel: T): Unit
    fun finished(failure: VpnRunFailure?): Unit
}

internal class VpnRun(private val registry: VpnRunRegistry) {
    private var stopped = false
    private var finished = false
    private var job: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <P : Any, T : Any> launch(scope: CoroutineScope, operations: VpnRunOperations<P, T>): Job {
        val job = scope.launch(start = CoroutineStart.ATOMIC) { execute(operations) }
        attach(job)
        return job
    }

    private fun attach(job: Job): Unit {
        val cancel = registry.guarded {
            if (stopped || finished) true else {
                this.job = job
                false
            }
        }
        if (cancel) job.cancel()
    }

    fun stop(): Boolean {
        var pending: Job? = null
        val wasActive = registry.guarded {
            if (finished) false else {
                stopped = true
                pending = job
                true
            }
        }
        pending?.cancel()
        return wasActive
    }

    fun isActive(): Boolean = registry.guarded { !finished }

    fun publishIfActive(publish: () -> Unit): Unit = registry.guarded {
        if (!stopped && !finished) publish()
    }

    fun finishLocked(): Unit {
        finished = true
        job = null
    }

    fun checkActive(): Unit = registry.guarded {
        if (stopped || finished) throw CancellationException("vpn_stopped")
    }

    private suspend fun admit(): Unit {
        coroutineContext.ensureActive()
        // An admitted synchronous operation may finish after a later STOP.
        checkActive()
    }

    private suspend fun <P : Any, T : Any> execute(operations: VpnRunOperations<P, T>): Unit {
        var tunnel: T? = null
        var failure: VpnRunFailure? = null
        try {
            coroutineContext.ensureActive()
            checkActive()
            val prepared = operations.prepare(this)
            admit()
            operations.setUnderlying(prepared)
            admit()
            tunnel = operations.establish(prepared)
            admit()
            operations.start(prepared, tunnel)
            admit()
            operations.monitor(prepared, this)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            failure = VpnRunFailure.Operation(exception)
        } finally {
            withContext(NonCancellable) {
                try {
                    try {
                        operations.stopping()
                    } finally {
                        try {
                            operations.stop()
                        } catch (_: Exception) {
                            failure = VpnRunFailure.Stop
                        } finally {
                            try {
                                tunnel?.let { operations.close(it) }
                            } catch (_: Exception) {
                                failure = VpnRunFailure.Close
                            }
                            operations.finished(failure)
                        }
                    }
                } finally {
                    registry.release(this@VpnRun)
                }
            }
        }
    }
}
