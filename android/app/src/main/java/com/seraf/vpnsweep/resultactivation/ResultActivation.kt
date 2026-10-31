package com.seraf.vpnsweep.resultactivation

import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchprotocol.SearchStartV3
import com.seraf.vpnsweep.searchstate.MarkerState
import com.seraf.vpnsweep.searchstate.SearchController
import com.seraf.vpnsweep.searchstate.SearchStartPreparation
import com.seraf.vpnsweep.searchstate.SessionMarker
import com.seraf.vpnsweep.searchstorage.CurrentRevisionStore
import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.searchstorage.ResultStorage

internal sealed interface VpnAdmission<out T : Any> {
    data object Unavailable : VpnAdmission<Nothing>
    class Admitted<T : Any>(val reservation: T, val bytes: ByteArray) : VpnAdmission<T>
    // A reservation returned here is only for cleanup, never for starting VPN.
    class Unknown<T : Any>(val cleanupReservation: T?) : VpnAdmission<T>
}

internal class ResultActivation(
    private val controller: SearchController,
    private val storage: ResultStorage,
    private val revisions: CurrentRevisionStore,
    private val marker: SessionMarker,
    private val vpnDisconnected: () -> Boolean,
    private val completionReceipts: SearchCompletionReceiptStorage = EmptySearchCompletionReceiptStorage,
) {
    private var uncertain = false
    private var deciding = false

    fun reserveSearch(
        sourceRevision: Long,
        selectionRevision: Long,
        profiles: PrivateInputV1,
    ): SearchStartPreparation = synchronized(controller) {
        if (uncertain || deciding) return@synchronized SearchStartPreparation.Denied(SearchStartV3.BUSY)
        deciding = true
        try {
            if (!markerClear()) return@synchronized SearchStartPreparation.Denied(SearchStartV3.BUSY)
            val expected = ResultRevision(sourceRevision.toString(), selectionRevision.toString())
            val current = try { revisions.load() } catch (_: Exception) { null }
            if (current != expected) return@synchronized SearchStartPreparation.Denied(SearchStartV3.REJECTED)
            if (!disconnected()) return@synchronized SearchStartPreparation.Denied(SearchStartV3.BUSY)
            try {
                controller.reserveStart(sourceRevision, selectionRevision, profiles, vpnDisconnected = true)
            } catch (failure: Throwable) {
                uncertain = true
                if (failure !is Exception) throw failure
                SearchStartPreparation.Denied(SearchStartV3.UNKNOWN)
            }
        } finally {
            deciding = false
        }
    }

    fun withSourceMutation(mutation: () -> Boolean): Boolean = controller.withSourceMutation {
        if (uncertain || deciding) return@withSourceMutation false
        deciding = true
        try {
            if (!markerClear()) return@withSourceMutation false
            mutation()
        } catch (failure: Throwable) {
            uncertain = true
            throw failure
        } finally {
            deciding = false
        }
    }

    // UI readiness is a hint; VPN must use admitVpn under the native lease.
    fun ready(): Boolean {
        var ready = false
        controller.withSourceMutation {
            if (uncertain || deciding) return@withSourceMutation false
            deciding = true
            try {
                if (markerClear() && disconnected()) ready = verifiedBytes() != null
                ready
            } finally {
                deciding = false
            }
        }
        return ready
    }

    fun <T : Any> admitVpn(reserve: () -> T?): VpnAdmission<T> {
        var outcome: VpnAdmission<T> = VpnAdmission.Unavailable
        controller.withSourceMutation {
            if (uncertain || deciding) return@withSourceMutation false
            deciding = true
            try {
                if (!markerClear() || !disconnected()) return@withSourceMutation false
                val bytes = verifiedBytes() ?: return@withSourceMutation false
                val reservation = try {
                    reserve()
                } catch (failure: Throwable) {
                    uncertain = true
                    if (failure !is Exception) throw failure
                    outcome = VpnAdmission.Unknown(null)
                    return@withSourceMutation false
                }
                val nowDisconnected = try {
                    vpnDisconnected()
                } catch (failure: Throwable) {
                    uncertain = true
                    if (failure !is Exception) throw failure
                    outcome = VpnAdmission.Unknown(reservation)
                    return@withSourceMutation false
                }
                outcome = when {
                    reservation != null && !nowDisconnected -> VpnAdmission.Admitted(reservation, bytes)
                    reservation == null && nowDisconnected -> VpnAdmission.Unavailable
                    else -> {
                        uncertain = true
                        VpnAdmission.Unknown(reservation)
                    }
                }
                outcome is VpnAdmission.Admitted<*>
            } finally {
                deciding = false
            }
        }
        return outcome
    }

    private fun markerClear(): Boolean = try {
        marker.read() == MarkerState.CLEAR
    } catch (_: Exception) {
        false
    }

    private fun disconnected(): Boolean = try {
        vpnDisconnected()
    } catch (_: Exception) {
        false
    }

    private fun verifiedBytes(): ByteArray? {
        return try {
            val revision = revisions.load() ?: return null
            if (completionReceipts.hasPending()) return null
            val receipt = completionReceipts.read() ?: return null
            verifiedCompletionBytes(storage, receipt, revision)
        } catch (_: Exception) {
            null
        }
    }

}

private object EmptySearchCompletionReceiptStorage : SearchCompletionReceiptStorage {
    override fun read(): SearchCompletionReceiptV1? = null
    override fun readPrepared(): SearchCompletionReceiptV1? = null
    override fun hasPending(): Boolean = false
    override fun prepare(receipt: SearchCompletionReceiptV1): Boolean = false
    override fun commit(): Boolean = false
    override fun write(receipt: SearchCompletionReceiptV1): Boolean = false
}
