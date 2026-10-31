package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchprotocol.WorkerIdentityV1

// All supplied checks run without an admission lock. Their results are
// rechecked after the potentially blocking Android network verification.
internal class OwnerCurrentCheck(
    private val expectedUid: Int,
    private val coordinatorPid: Int,
    private val identity: WorkerIdentityV1,
    private val network: SearchNetworkV1,
    private val workerPid: () -> Int,
    private val admitted: () -> Boolean,
    private val anchorHeld: () -> Boolean,
    private val verifyNetwork: (SearchNetworkV1) -> Boolean,
) {
    fun permits(uid: Int, pid: Int, requested: WorkerIdentityV1, selected: SearchNetworkV1): Boolean {
        if (uid != expectedUid || pid <= 0 || pid == coordinatorPid || requested != identity || selected != network) return false
        return try {
            workerPid() == pid && admitted() && anchorHeld() && verifyNetwork(network) &&
                anchorHeld() && admitted() && workerPid() == pid
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
    }
}

internal fun rejectCurrentIfActive(
    lock: Any,
    isStillAuthenticated: () -> Boolean,
    markStopped: () -> Unit,
    quarantine: () -> Unit,
): Unit {
    val reject = synchronized(lock) {
        if (!isStillAuthenticated()) false else {
            markStopped()
            true
        }
    }
    if (reject) quarantine()
}
