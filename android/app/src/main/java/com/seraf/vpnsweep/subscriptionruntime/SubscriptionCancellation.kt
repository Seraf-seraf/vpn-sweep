package com.seraf.vpnsweep.subscriptionruntime

internal class SubscriptionCancellation {
    private val lock = Any()
    private var cancelled = false
    private var revoked = false
    private var requestCancel: (() -> Unit)? = null
    private var routeCancel: (() -> Unit)? = null

    fun installRequest(cancel: () -> Unit): Unit {
        val cancelNow = synchronized(lock) {
            requestCancel = cancel
            cancelled || revoked
        }
        if (cancelNow) safely(cancel)
    }

    fun installRoute(cancel: () -> Unit): Unit {
        val cancelNow = synchronized(lock) {
            routeCancel = cancel
            cancelled || revoked
        }
        if (cancelNow) safely(cancel)
    }

    fun cancel(): Unit {
        val callbacks = synchronized(lock) {
            cancelled = true
            requestCancel to routeCancel
        }
        callbacks.first?.let(::safely)
        callbacks.second?.let(::safely)
    }

    fun revoke(): Unit {
        val cancelRequest = synchronized(lock) {
            revoked = true
            requestCancel
        }
        cancelRequest?.let(::safely)
    }

    fun cancelled(): Boolean = synchronized(lock) { cancelled }
    fun revoked(): Boolean = synchronized(lock) { revoked }

    fun clear(): Unit {
        synchronized(lock) {
            requestCancel = null
            routeCancel = null
        }
    }

    private fun safely(callback: () -> Unit): Unit {
        try {
            callback()
        } catch (_: Exception) {
        }
    }
}
