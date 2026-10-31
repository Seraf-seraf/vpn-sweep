package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchLeaseDuplicateV1
import java.util.concurrent.CountDownLatch

// This object is the sole owner of the sender duplicate. A close racing an
// in-flight Binder transfer waits outside the state lock for that call to end.
internal class PreparedWorkerLeaseDuplicate(
    private val owner: SearchLeaseDuplicateV1,
    private val releaseUnstarted: () -> Unit,
    private val quarantine: () -> Unit,
) : SearchLeaseDuplicateV1 {
    private val lock = Any()
    private var binding = false
    private var closing = false
    private var transferStarted = false
    private var transferDone: CountDownLatch? = null
    private val closeOnce = LeaseCloseOnce {
        val (unstarted, pending) = synchronized(lock) {
            closing = true
            (!binding) to transferDone
        }
        try {
            pending?.await()
            owner.close()
            if (unstarted) releaseUnstarted()
        } catch (t: Throwable) {
            try { quarantine() } catch (_: Throwable) { }
            throw t
        }
    }

    fun beginBind(): Boolean = synchronized(lock) {
        if (binding || closing) false else {
            binding = true
            true
        }
    }

    fun beginTransfer(): SearchLeaseDuplicateV1? = synchronized(lock) {
        if (!binding || closing || transferStarted) null else {
            transferStarted = true
            transferDone = CountDownLatch(1)
            owner
        }
    }

    fun endTransfer(): Unit {
        val completed = synchronized(lock) { transferDone.also { transferDone = null } }
        completed?.countDown()
    }

    override fun close(): Unit = closeOnce.close()
}
