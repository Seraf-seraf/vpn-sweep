package com.seraf.vpnsweep.searchworker

internal class SupervisedCoordinatorStop(
    private val admission: CoordinatorAdmission,
    private val requestUnbind: ((Boolean) -> Unit) -> Boolean,
    private val scheduleClose: (() -> Unit) -> Boolean,
    private val closeDuplicate: () -> Boolean,
    private val unbound: (Boolean) -> Unit,
) {
    private val lock = Any()
    private var started = false
    private var unbindDelivered = false
    private var closeStarted = false

    fun begin(attempt: Any): Boolean {
        synchronized(lock) {
            if (started) return false
            started = true
        }
        if (!admission.beginSupervisorStop(attempt)) return false
        val requested = try { requestUnbind(::unbindCompleted) } catch (_: Exception) { false } catch (_: LinkageError) { false }
        if (!requested) unbindCompleted(false)
        val scheduled = try { scheduleClose(::closeOnce) } catch (_: Exception) { false } catch (_: LinkageError) { false }
        if (!scheduled) admission.supervisedDuplicateClosed(false)
        return true
    }

    private fun unbindCompleted(success: Boolean): Unit {
        synchronized(lock) {
            if (unbindDelivered) return
            unbindDelivered = true
        }
        admission.supervisedUnbindCompleted(success)
        try { unbound(success) } catch (_: Exception) { } catch (_: LinkageError) { }
    }

    private fun closeOnce(): Unit {
        synchronized(lock) {
            if (closeStarted) return
            closeStarted = true
        }
        val closed = try { closeDuplicate() } catch (_: Exception) { false } catch (_: LinkageError) { false }
        admission.supervisedDuplicateClosed(closed)
    }
}
