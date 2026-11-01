package com.seraf.vpnsweep.searchworker

import java.io.IOException
import java.util.concurrent.CountDownLatch

internal class LeaseCloseOnce(private val closeOwner: () -> Unit) {
    private val lock = Any()
    private val done = CountDownLatch(1)
    private var started = false
    @Volatile private var failure: Throwable? = null

    fun close(): Unit = close(waitUninterruptibly = false)

    fun closeUninterruptibly(): Unit = close(waitUninterruptibly = true)

    private fun close(waitUninterruptibly: Boolean): Unit {
        val closeHere = synchronized(lock) {
            if (started) false else {
                started = true
                true
            }
        }
        if (closeHere) {
            try {
                closeOwner()
            } catch (t: Throwable) {
                if (t is InterruptedException) Thread.currentThread().interrupt()
                failure = t
            } finally {
                done.countDown()
            }
        } else if (waitUninterruptibly) {
            var interrupted = false
            try {
                while (true) {
                    try { done.await(); break } catch (_: InterruptedException) { interrupted = true }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        } else {
            try {
                done.await()
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("lease_close_unknown", t)
            }
        }
        failure?.let { throw IOException("lease_close_unknown", it) }
    }
}
