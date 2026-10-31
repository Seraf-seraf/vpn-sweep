package com.seraf.vpnsweep.searchworker

import com.seraf.vpnsweep.searchprotocol.SearchResourceStateV2
import com.seraf.vpnsweep.searchprotocol.SearchResultFenceV3
import com.seraf.vpnsweep.searchprotocol.SearchResultOfferV3
import com.seraf.vpnsweep.searchprotocol.SearchResultReceiptV3
import com.seraf.vpnsweep.searchprotocol.SearchSessionObserverV3

internal sealed interface HandoffResult {
    data class Delivered(val receipt: SearchResultReceiptV3) : HandoffResult
    data object Discarded : HandoffResult
    data object Rejected : HandoffResult
    data object Unknown : HandoffResult
}

// This gate covers only the single synchronous local export open. A producer
// must separately account for any other source that can deliver a result FD.
internal class ResultHandoffFence(
    private val sessionGeneration: Long,
    private val phaseEpoch: Long,
    private val observer: SearchSessionObserverV3,
) {
    private val lock = Any()
    private var reserved = false
    private var inFlight = false
    private var sealed = false
    private var cancelled = false
    private var unknown = false
    private var leaseState: SearchResourceStateV2? = null

    fun reserveLocalOpen(): LocalOpen? = synchronized(lock) {
        if (reserved || sealed || unknown) return@synchronized null
        reserved = true
        inFlight = true
        LocalOpen()
    }

    fun seal(): Unit = synchronized(lock) { sealed = true }

    fun cancel(): Unit = synchronized(lock) {
        sealed = true
        cancelled = true
    }

    fun quarantine(): Unit = synchronized(lock) { unknown() }

    fun workerState(state: SearchResourceStateV2): Unit = synchronized(lock) {
        if (state == SearchResourceStateV2.QUARANTINED ||
            leaseState == SearchResourceStateV2.RELEASED && state != SearchResourceStateV2.RELEASED
        ) {
            unknown()
        }
        leaseState = state
    }

    fun resultFence(): SearchResultFenceV3? = synchronized(lock) {
        if (!sealed || inFlight) return@synchronized null
        if (unknown) return@synchronized SearchResultFenceV3.UNKNOWN
        if (leaseState == SearchResourceStateV2.RELEASED) SearchResultFenceV3.CONFIRMED else null
    }

    private fun unknown(): Unit {
        unknown = true
        sealed = true
        cancelled = true
    }

    internal inner class LocalOpen internal constructor() {
        private var started = false

        // open must synchronously return one locally owned result or throw;
        // it must not schedule another resource-bearing delivery.
        fun run(open: () -> SearchResultOfferV3): HandoffResult {
            synchronized(lock) {
                if (started || !inFlight) return HandoffResult.Rejected
                if (cancelled) {
                    started = true
                    inFlight = false
                    return HandoffResult.Discarded
                }
                if (leaseState != SearchResourceStateV2.RELEASED) return HandoffResult.Rejected
                started = true
            }
            val offer = try {
                open()
            } catch (failure: Throwable) {
                finish(uncertain = true)
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                return HandoffResult.Unknown
            }
            val deliver = synchronized(lock) {
                !cancelled && offer.sessionGeneration == sessionGeneration && offer.phaseEpoch == phaseEpoch
            }
            return if (deliver) deliver(offer) else discard(offer)
        }

        private fun discard(offer: SearchResultOfferV3): HandoffResult {
            try {
                offer.result.close()
            } catch (failure: Throwable) {
                finish(uncertain = true)
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                return HandoffResult.Unknown
            }
            finish(uncertain = false)
            return HandoffResult.Discarded
        }

        private fun deliver(offer: SearchResultOfferV3): HandoffResult {
            val receipt: SearchResultReceiptV3? = try {
                observer.onResult(offer)
            } catch (failure: Throwable) {
                finish(uncertain = true)
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                return HandoffResult.Unknown
            }
            if (receipt == null || receipt == SearchResultReceiptV3.UNKNOWN) {
                finish(uncertain = true)
                return HandoffResult.Unknown
            }
            finish(uncertain = false)
            return HandoffResult.Delivered(receipt)
        }

        private fun finish(uncertain: Boolean): Unit = synchronized(lock) {
            if (uncertain) unknown()
            inFlight = false
        }
    }
}
