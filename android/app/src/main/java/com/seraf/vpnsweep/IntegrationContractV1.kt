package com.seraf.vpnsweep

import com.seraf.vpnsweep.searchprotocol.SearchSnapshotV1
import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionSnapshotV1
import com.seraf.vpnsweep.vpn.VpnRun
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class AcceptedSubscriptionReceiptV1(
    val requestGeneration: Long,
    val sourceRevision: String,
    val selectionRevision: String,
) {
    companion object {
        fun fromCommittedSnapshot(
            generation: Long,
            snapshot: SubscriptionSnapshotV1,
            revision: ResultRevision,
        ): AcceptedSubscriptionReceiptV1? {
            if (generation <= 0 || snapshot.records.none { it.supported } ||
                !validRevision(revision.source) || !validRevision(revision.selection)
            ) return null
            return AcceptedSubscriptionReceiptV1(generation, revision.source, revision.selection)
        }
    }
}

internal data class SearchLeaseReceiptV1(
    val requestGeneration: Long,
    val sessionGeneration: Long,
    val sourceRevision: Long,
    val selectionRevision: Long,
    val leaseIdentity: String,
) {
    companion object {
        fun fromAcceptedSnapshot(
            accepted: AcceptedSubscriptionReceiptV1,
            snapshot: SearchSnapshotV1,
            leaseIdentity: String,
        ): SearchLeaseReceiptV1? {
            if (accepted.requestGeneration <= 0 || snapshot.sessionGeneration <= 0 ||
                snapshot.sourceRevision.toString() != accepted.sourceRevision ||
                snapshot.selectionRevision.toString() != accepted.selectionRevision ||
                !validIdentity(leaseIdentity)
            ) return null
            return SearchLeaseReceiptV1(
                accepted.requestGeneration,
                snapshot.sessionGeneration,
                snapshot.sourceRevision,
                snapshot.selectionRevision,
                leaseIdentity,
            )
        }
    }
}

internal data class VpnStartIdentityV1(
    val requestGeneration: Long,
    val sessionGeneration: Long,
    val sourceRevision: String,
    val selectionRevision: String,
    val searchLeaseIdentity: String,
    val vpnLeaseIdentity: String,
) {
    fun isValid(): Boolean = requestGeneration > 0 && sessionGeneration > 0 &&
        validRevision(sourceRevision) && validRevision(selectionRevision) &&
        validIdentity(searchLeaseIdentity) && validIdentity(vpnLeaseIdentity)

    companion object {
        fun fromCommittedSearch(
            accepted: AcceptedSubscriptionReceiptV1,
            searched: SearchLeaseReceiptV1,
            revision: ResultRevision,
            searchComplete: Boolean,
            resultCommitted: Boolean,
            vpnLeaseIdentity: String,
        ): VpnStartIdentityV1? {
            if (!searchComplete || !resultCommitted ||
                searched.requestGeneration != accepted.requestGeneration ||
                searched.sourceRevision.toString() != accepted.sourceRevision ||
                searched.selectionRevision.toString() != accepted.selectionRevision ||
                revision.source != accepted.sourceRevision || revision.selection != accepted.selectionRevision ||
                !validIdentity(vpnLeaseIdentity)
            ) return null
            return VpnStartIdentityV1(
                accepted.requestGeneration,
                searched.sessionGeneration,
                revision.source,
                revision.selection,
                searched.leaseIdentity,
                vpnLeaseIdentity,
            ).takeIf(VpnStartIdentityV1::isValid)
        }
    }
}

internal data class VpnStartTicketV1(val token: String, val identity: VpnStartIdentityV1)

internal class VpnStartGateV1 {
    private var pending: VpnStartTicketV1? = null

    @Synchronized
    fun issue(identity: VpnStartIdentityV1): VpnStartTicketV1? {
        if (!identity.isValid() || pending != null) return null
        return VpnStartTicketV1(UUID.randomUUID().toString(), identity).also { pending = it }
    }

    @Synchronized
    fun pendingIdentity(token: String?): VpnStartIdentityV1? =
        pending?.takeIf { token != null && it.token == token }?.identity

    @Synchronized
    fun consume(token: String?, expected: VpnStartIdentityV1): VpnStartTicketV1? {
        val current = pending ?: return null
        if (token != current.token) return null
        pending = null
        return current.takeIf { expected == it.identity }
    }

    @Synchronized
    fun cancel(token: String): Boolean {
        if (pending?.token != token) return false
        pending = null
        return true
    }
}

internal class VpnStartAdmissionV1 internal constructor(
    val identity: VpnStartIdentityV1,
    source: ByteArray,
    val run: VpnRun,
    private val leaseCurrent: () -> Boolean,
    private val identityCurrent: () -> Boolean,
    private val releaseLease: () -> Unit,
    private val quarantineLease: () -> Unit,
) : AutoCloseable {
    private val sourceSnapshot = source.copyOf()
    private val closed = AtomicBoolean()
    private val quarantined = AtomicBoolean()

    val source: ByteArray get() = sourceSnapshot.copyOf()

    fun isCurrent(): Boolean = !closed.get() && try {
        leaseCurrent() && identityCurrent()
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    override fun close(): Unit {
        if (!closed.compareAndSet(false, true)) return
        try {
            releaseLease()
        } catch (failure: Throwable) {
            quarantine()
            if (failure !is Exception) throw failure
            throw failure
        }
    }

    fun quarantine(): Unit {
        closed.set(true)
        if (!quarantined.compareAndSet(false, true)) return
        try { quarantineLease() } finally { run.quarantineCleanup() }
    }
}

internal fun validRevision(value: String): Boolean =
    value.toLongOrNull()?.let { it > 0 && it.toString() == value } == true

internal fun validIdentity(value: String): Boolean = try {
    UUID.fromString(value).toString() == value
} catch (_: IllegalArgumentException) {
    false
}
