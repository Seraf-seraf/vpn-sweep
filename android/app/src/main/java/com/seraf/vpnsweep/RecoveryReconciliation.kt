package com.seraf.vpnsweep

import com.seraf.vpnsweep.resultactivation.ResultActivationComposition
import com.seraf.vpnsweep.searchstate.SubscriptionController
import com.seraf.vpnsweep.subscriptionruntime.SubscriptionStorage

internal class SearchReceiptState {
    var accepted: AcceptedSubscriptionReceiptV1? = null
    var active: SearchLeaseReceiptV1? = null
    var completed: SearchLeaseReceiptV1? = null
    var restoredCompleted: SearchLeaseReceiptV1? = null

    fun installRecovered(receipt: AcceptedSubscriptionReceiptV1): Unit {
        accepted = receipt
        active = null
        completed = null
        restoredCompleted = null
    }
}

internal fun reconcileRecoveredSubscription(
    activation: ResultActivationComposition,
    storage: SubscriptionStorage,
    subscriptions: SubscriptionController,
    receiptLock: Any,
    receipts: SearchReceiptState,
): Boolean {
    val revision = activation.reissueForUpgrade(storage::reissueCurrent) ?: return false
    val recovered = storage.recover(revision) ?: return false
    val receipt = AcceptedSubscriptionReceiptV1.fromCommittedSnapshot(
        recovered.requestGeneration, recovered.snapshot, recovered.revision,
    ) ?: run { recovered.snapshot.close(); return false }
    if (!subscriptions.restore(recovered.snapshot, recovered.selectedIds, revision, recovered.requestGeneration)) return false
    synchronized(receiptLock) { receipts.installRecovered(receipt) }
    return true
}
