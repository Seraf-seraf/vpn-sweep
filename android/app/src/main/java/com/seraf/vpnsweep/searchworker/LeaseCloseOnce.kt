package com.seraf.vpnsweep.searchworker

import java.io.IOException
import java.util.concurrent.CountDownLatch

internal class LeaseCloseOnce(private val closeOwner: () -> Unit) {
    private val lock = Any()
    private val done = CountDownLatch(1)
    private var started = false
    @Volatile private var failure: Throwable? = null

    fun close(): Unit {
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
