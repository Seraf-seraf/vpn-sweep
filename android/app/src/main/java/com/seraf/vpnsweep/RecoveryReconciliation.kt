package com.seraf.vpnsweep

import com.seraf.vpnsweep.resultactivation.ResultActivationComposition
import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchResourceStateV2
import com.seraf.vpnsweep.searchprotocol.SearchResultFenceV3
import com.seraf.vpnsweep.searchprotocol.SearchTerminalV3
import com.seraf.vpnsweep.searchstate.SubscriptionController
import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionProfilesTooLargeException
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionSnapshotV1
import com.seraf.vpnsweep.subscriptionruntime.SubscriptionStorage

internal sealed interface CompletionDisposition {
    data class Current(val accepted: AcceptedSubscriptionReceiptV1) : CompletionDisposition
    data object Superseded : CompletionDisposition
    data object Unknown : CompletionDisposition
}

internal class SearchReceiptState {
    var accepted: AcceptedSubscriptionReceiptV1? = null
    var active: SearchLeaseReceiptV1? = null
    var completed: SearchLeaseReceiptV1? = null
    var restoredCompleted: SearchLeaseReceiptV1? = null

    fun invalidateForSelection(): Unit {
        accepted = null
        completed = null
        restoredCompleted = null
    }

    fun acceptVerified(receipt: AcceptedSubscriptionReceiptV1, current: ResultRevision?, installedGeneration: Long): Boolean {
        if (current != ResultRevision(receipt.sourceRevision, receipt.selectionRevision) ||
            installedGeneration != receipt.requestGeneration
        ) return false
        accepted = receipt
        return true
    }

    fun vpnIdentity(revision: ResultRevision, leaseIdentity: String, complete: (SearchLeaseReceiptV1) -> Boolean): VpnStartIdentityV1? {
        val imported = accepted ?: return null
        val searched = completed ?: return null
        val verified = complete(searched)
        return VpnStartIdentityV1.fromCommittedSearch(imported, searched, revision, verified, verified, leaseIdentity)
    }

    fun completionDisposition(receipt: SearchLeaseReceiptV1, revision: ResultRevision): CompletionDisposition {
        if (active != receipt) return CompletionDisposition.Unknown
        if (revision != ResultRevision(receipt.sourceRevision.toString(), receipt.selectionRevision.toString())) {
            return CompletionDisposition.Superseded
        }
        val imported = accepted ?: return CompletionDisposition.Unknown
        return if (imported.requestGeneration == receipt.requestGeneration &&
            imported.sourceRevision == revision.source && imported.selectionRevision == revision.selection
        ) CompletionDisposition.Current(imported) else CompletionDisposition.Unknown
    }

    fun installRecovered(receipt: AcceptedSubscriptionReceiptV1): Unit {
        accepted = receipt
        active = null
        completed = null
        restoredCompleted = null
    }
}

internal fun acceptSearchTerminal(
    terminal: SearchTerminalV3,
    identityLock: Any,
    identities: MutableMap<Long, SearchLeaseReceiptV1>,
    receiptLock: Any,
    receipts: SearchReceiptState,
    acceptProducer: (SearchTerminalV3) -> Boolean,
    activation: ResultActivationComposition,
): Boolean {
    val receipt = synchronized(identityLock) { identities[terminal.sessionGeneration] } ?: return false
    if (!acceptProducer(terminal)) return false
    var superseded = false
    if (terminal is SearchTerminalV3.Complete) {
        if (terminal.resources != SearchResourceStateV2.RELEASED ||
            terminal.resultFence != SearchResultFenceV3.CONFIRMED
        ) return false
        val revision = activation.currentRevision() ?: return false
        when (val disposition = synchronized(receiptLock) { receipts.completionDisposition(receipt, revision) }) {
            is CompletionDisposition.Current -> if (!activation.prepareCleanComplete(disposition.accepted, receipt)) return false
            CompletionDisposition.Superseded -> superseded = true
            CompletionDisposition.Unknown -> return false
        }
    }
    synchronized(identityLock) {
        if (identities[terminal.sessionGeneration] != receipt) return false
        synchronized(receiptLock) {
            if (receipts.active != receipt) return false
            if (terminal !is SearchTerminalV3.Complete || superseded) {
                identities.remove(terminal.sessionGeneration)
                receipts.active = null
                receipts.completed = null
                receipts.restoredCompleted = null
            }
        }
    }
    return true
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
    if (!subscriptions.restoreReissued(recovered.snapshot, recovered.selectedIds, revision, recovered.requestGeneration)) return false
    synchronized(receiptLock) { receipts.installRecovered(receipt) }
    return true
}

internal fun subscriptionSnapshotEnvironmentSafe(getenv: (String) -> String?): Boolean = try {
    getenv("xray.browser.dialer").isNullOrEmpty() && getenv("XRAY_BROWSER_DIALER").isNullOrEmpty()
} catch (_: Exception) { false } catch (_: LinkageError) { false }

internal class BlockedSubscriptionRestore(val accepted: AcceptedSubscriptionReceiptV1?)

internal fun restoreBlockedSubscription(
    revision: ResultRevision,
    storage: SubscriptionStorage,
    subscriptions: SubscriptionController,
    bootstrapSafe: () -> Boolean,
): BlockedSubscriptionRestore? {
    val display = storage.recoverDisplay(revision)
    if (display != null) {
        if (!subscriptions.restoreDisplay(display.records, display.selectedIds, revision, display.requestGeneration)) return null
        return BlockedSubscriptionRestore(null)
    }
    if (!storage.legacyDisplayAbsent(revision) || !bootstrapSafe()) return null
    val recovered = storage.recover(revision) ?: return null
    val receipt = AcceptedSubscriptionReceiptV1.fromCommittedSnapshot(recovered.requestGeneration, recovered.snapshot, revision)
        ?: run { recovered.snapshot.close(); return null }
    if (!subscriptions.restore(recovered.snapshot, recovered.selectedIds, revision, recovered.requestGeneration)) return null
    storage.cacheDisplay(recovered)
    return BlockedSubscriptionRestore(receipt)
}

internal fun prepareVerifiedSelection(
    revision: ResultRevision,
    ids: Set<String>,
    storage: SubscriptionStorage,
    subscriptions: SubscriptionController,
    mayLoad: () -> Boolean,
    closeOwner: () -> Unit,
    quarantineOwner: () -> Unit,
    accept: (AcceptedSubscriptionReceiptV1) -> Boolean,
): PrivateInputV1? {
    var closeAttempted = false
    var snapshot: SubscriptionSnapshotV1? = null
    var input: PrivateInputV1? = null
    try {
        if (!mayLoad()) { quarantineOwner(); return null }
        val recovered = storage.recover(revision) ?: return null
        snapshot = recovered.snapshot
        if (recovered.selectedIds != ids) return null
        val accepted = AcceptedSubscriptionReceiptV1.fromCommittedSnapshot(recovered.requestGeneration, recovered.snapshot, revision)
            ?: return null
        input = recovered.snapshot.selectedProfiles(ids) ?: return null
        if (!subscriptions.replaceValidated(recovered.snapshot, ids, revision, recovered.requestGeneration)) return null
        snapshot = null
        closeAttempted = true
        closeOwner()
        if (!accept(accepted)) return null
        val result = input
        input = null
        return result
    } catch (failure: SubscriptionProfilesTooLargeException) {
        throw failure
    } catch (_: Exception) {
        return null
    } catch (_: LinkageError) {
        return null
    } finally {
        try { input?.close() } catch (_: Exception) { try { quarantineOwner() } catch (_: Exception) { } }
        try { snapshot?.close() } catch (_: Exception) { try { quarantineOwner() } catch (_: Exception) { } }
        if (!closeAttempted) try { closeOwner() } catch (_: Exception) { }
    }
}
