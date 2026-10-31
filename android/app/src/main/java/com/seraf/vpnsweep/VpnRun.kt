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

    fun reserve(stopping: () -> Unit): VpnRun? = guarded {
        if (current != null) return@guarded null
        VpnRun(this, stopping).also { current = it }
    }

    fun <T> guarded(action: () -> T): T = synchronized(lock, action)

    fun claim(run: VpnRun): Boolean = guarded {
        if (current !== run) return@guarded false
        run.finishLocked()
        current = null
        true
    }

    fun release(run: VpnRun, publishTerminal: () -> Unit): Unit = guarded {
        if (current === run) {
            run.finishLocked()
            current = null
            publishTerminal()
        }
    }
}

internal sealed interface VpnRunFailure {
    class Operation(val exception: Exception) : VpnRunFailure
    data object Foreground : VpnRunFailure
    data object Status : VpnRunFailure
    data object Stop : VpnRunFailure
    data object Close : VpnRunFailure
    data object ServiceCleanup : VpnRunFailure
}

internal interface VpnRunOperations<P : Any, T : Any> {
    fun prepare(run: VpnRun): P
    fun setUnderlying(prepared: P): Unit
    fun establish(prepared: P): T
    fun start(prepared: P, tunnel: T): Unit
    suspend fun monitor(prepared: P, run: VpnRun): Unit
    fun stop(): Unit
    fun close(tunnel: T): Unit
    suspend fun cleanupService(run: VpnRun, complete: (Exception?) -> Unit): Unit
    fun finished(failure: VpnRunFailure?): Unit
}

internal class VpnRun(
    private val registry: VpnRunRegistry,
    private val stopping: () -> Unit,
) {
    private var stopped = false
    private var finished = false
    private var statusFailed = false
    private var job: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <P : Any, T : Any> launch(
        scope: CoroutineScope,
        operations: VpnRunOperations<P, T>,
        promote: () -> Unit = {},
    ): Job {
        val initialFailure = try {
            promote()
            null
        } catch (_: Exception) {
            VpnRunFailure.Foreground
        }
        val job = scope.launch(start = CoroutineStart.ATOMIC) { execute(operations, initialFailure) }
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
                markStoppingLocked()
                pending = job
                true
            }
        }
        pending?.cancel()
        return wasActive
    }

    private fun markStoppingLocked(): Unit {
        if (!stopped) {
            stopped = true
            try {
                stopping()
            } catch (_: Exception) {
                statusFailed = true
            }
        }
    }

    fun isActive(): Boolean = registry.guarded { !finished }

    fun publishIfActive(publish: () -> Unit): Unit = registry.guarded {
        if (!stopped && !finished) publish()
    }

    fun finishLocked(): Unit {
        finished = true
        job = null
    }

    fun claimRelease(): Boolean = registry.claim(this)

    fun checkActive(): Unit = registry.guarded {
        if (stopped || finished) throw CancellationException("vpn_stopped")
    }

    private suspend fun admit(): Unit {
        coroutineContext.ensureActive()
        // An admitted synchronous operation may finish after a later STOP.
        checkActive()
    }

    private suspend fun <P : Any, T : Any> execute(
        operations: VpnRunOperations<P, T>,
        initialFailure: VpnRunFailure?,
    ): Unit {
        var tunnel: T? = null
        var failure = initialFailure
        try {
            if (failure == null) {
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
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            failure = VpnRunFailure.Operation(exception)
        } finally {
            withContext(NonCancellable) {
                try {
                    val failed = registry.guarded {
                        markStoppingLocked()
                        statusFailed
                    }
                    if (failed && failure == null) failure = VpnRunFailure.Status
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
                    }
                } finally {
                    var complete = false
                    val finish: (Exception?) -> Unit = { exception ->
                        if (!complete) {
                            complete = true
                            if (exception != null && (failure == null || failure is VpnRunFailure.Operation)) {
                                failure = VpnRunFailure.ServiceCleanup
                            }
                            operations.finished(failure)
                        }
                    }
                    val fallback: (Exception) -> Unit = { _ ->
                        if (!complete) {
                            complete = true
                            if (failure == null || failure is VpnRunFailure.Operation) {
                                failure = VpnRunFailure.ServiceCleanup
                            }
                            registry.release(this@VpnRun) { operations.finished(failure) }
                        }
                    }
                    try {
                        operations.cleanupService(this@VpnRun, finish)
                    } catch (exception: Exception) {
                        fallback(exception)
                    } finally {
                        if (!complete) fallback(IllegalStateException("vpn_cleanup_incomplete"))
                    }
                }
            }
        }
    }
}
