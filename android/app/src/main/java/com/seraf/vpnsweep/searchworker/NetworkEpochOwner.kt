package com.seraf.vpnsweep.searchworker

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

internal data class DefaultNetworkSnapshot(val identity: Long, val notVpn: Boolean)

internal sealed interface DefaultNetworkEvent {
    data class Available(val identity: Long) : DefaultNetworkEvent
    data class Capabilities(val identity: Long, val notVpn: Boolean) : DefaultNetworkEvent
    data class Blocked(val identity: Long, val blocked: Boolean) : DefaultNetworkEvent
    data object Lost : DefaultNetworkEvent
}

internal sealed interface NetworkOwnerOpen {
    data class Ready(val owner: NetworkEpochOwner, val network: SearchNetworkV1) : NetworkOwnerOpen
    data class Unavailable(val reason: NetworkPolicyUnavailableReason) : NetworkOwnerOpen
    data object Unknown : NetworkOwnerOpen
}

internal enum class NetworkPolicyUnavailableReason { UNCONFIRMED, CHECK_FAILED, CALLBACK_EVENT, NON_DIRECT, BLOCKED }

internal fun requireSearchNetwork(opened: NetworkOwnerOpen): NetworkOwnerOpen.Ready = when (opened) {
    is NetworkOwnerOpen.Ready -> opened
    is NetworkOwnerOpen.Unavailable -> throw SearchNetworkPolicyUnavailable(opened.reason)
    NetworkOwnerOpen.Unknown -> throw IOException("search_resolver_unknown")
}

// RETURNED confirms only callback return, not worker death or DNS cleanup.
internal enum class NetworkRevocation { NOT_REQUESTED, PENDING, RETURNED, UNKNOWN }

// The cached RoundOwner check is safe under admission locks. Socket and DNS
// callbacks must additionally call verifyOnCallback outside those locks.
internal class NetworkEpochOwner private constructor(
    private val snapshot: () -> DefaultNetworkSnapshot?,
    private val leaseCurrent: () -> Boolean,
    private val stopRoundAndDns: () -> Unit,
) : RoundOwner {
    private val lock = Any()
    private var registration: AutoCloseable? = null
    private var network: SearchNetworkV1? = null
    private var eventSerial = 0L
    private var lastBlocked: Pair<Long, Boolean>? = null
    private var lastObservedIdentity: Long? = null
    private var lastEventLost = false
    private var initialOnlyAvailable = true
    private var initialAvailableIdentity: Long? = null
    private var revoked = false
    private var closed = false
    private var closeConfirmed = false
    private var revocation = NetworkRevocation.NOT_REQUESTED

    override fun isCurrent(network: SearchNetworkV1): Boolean = synchronized(lock) {
        !closed && !revoked && this.network == network
    }

    // Performs potentially blocking Android/lease checks without a state lock.
    fun verifyOnCallback(network: SearchNetworkV1): Boolean {
        if (!isCurrent(network)) return false
        val valid = try {
            leaseCurrent() && snapshot()?.let { it.notVpn && it.identity == network.identity } == true && leaseCurrent()
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
        if (!valid) revoke()
        return valid && isCurrent(network)
    }

    // May be called on owner/death callbacks; teardown is dispatched separately.
    fun ownerLost(): Unit = revoke()

    // Call from cleanup after worker retirement, not from a Binder-death thread.
    fun close(): Boolean {
        val handle = synchronized(lock) {
            if (closed) return closeConfirmed
            closed = true
            revoked = true
            network = null
            registration.also { registration = null }
        }
        val confirmed = try {
            handle?.close()
            handle != null
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
        synchronized(lock) { closeConfirmed = confirmed }
        return confirmed
    }

    fun revocationStatus(): NetworkRevocation = synchronized(lock) { revocation }

    private fun changed(event: DefaultNetworkEvent): Unit {
        val notify = synchronized(lock) {
            if (closed || revoked) return
            if (network == null) {
                if (event is DefaultNetworkEvent.Available) {
                    val previous = initialAvailableIdentity
                    if (previous == null) initialAvailableIdentity = event.identity
                    else if (previous != event.identity) initialOnlyAvailable = false
                } else initialOnlyAvailable = false
            }
            if (event is DefaultNetworkEvent.Blocked) lastBlocked = event.identity to event.blocked
            if (event == DefaultNetworkEvent.Lost) lastEventLost = true
            if (event is DefaultNetworkEvent.Available) lastEventLost = false
            lastObservedIdentity = when (event) {
                is DefaultNetworkEvent.Available -> event.identity
                is DefaultNetworkEvent.Capabilities -> event.identity
                is DefaultNetworkEvent.Blocked -> event.identity
                DefaultNetworkEvent.Lost -> lastObservedIdentity
            }
            val invalidate = if (eventSerial == Long.MAX_VALUE) {
                true
            } else {
                eventSerial++
                val current = network ?: return
                when (event) {
                    is DefaultNetworkEvent.Available -> event.identity != current.identity
                    is DefaultNetworkEvent.Capabilities -> event.identity != current.identity || !event.notVpn
                    is DefaultNetworkEvent.Blocked -> event.identity != current.identity || event.blocked
                    DefaultNetworkEvent.Lost -> true
                }
            }
            if (invalidate) {
                revoked = true
                (network != null).also { if (it) revocation = NetworkRevocation.PENDING }
            } else false
        }
        if (notify) dispatchRevocation()
    }

    private fun revoke(): Unit {
        val notify = synchronized(lock) {
            if (closed || revoked) return
            revoked = true
            (network != null).also { if (it) revocation = NetworkRevocation.PENDING }
        }
        if (!notify) return
        dispatchRevocation()
    }

    private fun dispatchRevocation(): Unit {
        try {
            Thread({
                try {
                    stopRoundAndDns()
                    synchronized(lock) { revocation = NetworkRevocation.RETURNED }
                } catch (failure: Throwable) {
                    synchronized(lock) { revocation = NetworkRevocation.UNKNOWN }
                    if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                }
            }, "search-network-revoke").apply { isDaemon = true }.start()
        } catch (failure: Throwable) {
            synchronized(lock) { revocation = NetworkRevocation.UNKNOWN }
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
        }
    }

    companion object {
        private val epochs = AtomicLong()

        private fun nextEpoch(): Long? {
            while (true) {
                val previous = epochs.get()
                if (previous == Long.MAX_VALUE) return null
                if (epochs.compareAndSet(previous, previous + 1)) return previous + 1
            }
        }

        fun open(
            register: ((DefaultNetworkEvent) -> Unit) -> AutoCloseable,
            snapshot: () -> DefaultNetworkSnapshot?,
            leaseCurrent: () -> Boolean,
            stopRoundAndDns: () -> Unit,
        ): NetworkOwnerOpen {
            val owner = NetworkEpochOwner(snapshot, leaseCurrent, stopRoundAndDns)
            val registration = try {
                register(owner::changed)
            } catch (_: Exception) {
                return NetworkOwnerOpen.Unknown
            } catch (_: LinkageError) {
                return NetworkOwnerOpen.Unknown
            }
            val before = synchronized(owner.lock) {
                owner.registration = registration
                owner.eventSerial
            }
            fun currentLease(): Boolean = try { leaseCurrent() } catch (_: Exception) { false } catch (_: LinkageError) { false }
            data class Capture(val leaseValid: Boolean, val observed: DefaultNetworkSnapshot?, val checkFailed: Boolean)
            fun capture(): Capture {
                val initialLease = currentLease()
                var checkFailed = false
                val observed = if (initialLease) try {
                    snapshot()
                } catch (_: Exception) {
                    checkFailed = true
                    null
                } catch (_: LinkageError) {
                    checkFailed = true
                    null
                } else null
                return Capture(initialLease && currentLease(), observed, checkFailed)
            }
            val first = capture()
            val recaptureBefore = synchronized(owner.lock) {
                if (first.leaseValid && !first.checkFailed && first.observed?.notVpn == true &&
                    !owner.closed && !owner.revoked && owner.eventSerial != before && owner.initialOnlyAvailable &&
                    owner.initialAvailableIdentity == first.observed.identity
                ) owner.eventSerial else null
            }
            val final = if (recaptureBefore == null) first else capture()
            val observed = final.observed
            val result = synchronized(owner.lock) {
                when {
                    !final.leaseValid || owner.closed || owner.revoked -> NetworkOwnerOpen.Unknown
                    owner.eventSerial != (recaptureBefore ?: before) || owner.lastEventLost -> NetworkOwnerOpen.Unavailable(NetworkPolicyUnavailableReason.CALLBACK_EVENT)
                    final.checkFailed -> NetworkOwnerOpen.Unavailable(NetworkPolicyUnavailableReason.CHECK_FAILED)
                    observed == null -> NetworkOwnerOpen.Unavailable(NetworkPolicyUnavailableReason.UNCONFIRMED)
                    !observed.notVpn -> NetworkOwnerOpen.Unavailable(NetworkPolicyUnavailableReason.NON_DIRECT)
                    recaptureBefore != null && observed.identity != first.observed?.identity -> NetworkOwnerOpen.Unavailable(NetworkPolicyUnavailableReason.CALLBACK_EVENT)
                    owner.lastObservedIdentity?.let { it != observed.identity } == true -> NetworkOwnerOpen.Unavailable(NetworkPolicyUnavailableReason.CALLBACK_EVENT)
                    owner.lastBlocked == (observed.identity to true) -> NetworkOwnerOpen.Unavailable(NetworkPolicyUnavailableReason.BLOCKED)
                    else -> {
                        val token = nextEpoch()?.let { SearchNetworkV1(observed.identity, it) }
                        if (token == null) NetworkOwnerOpen.Unknown
                        else NetworkOwnerOpen.Ready(owner, token.also { owner.network = it })
                    }
                }
            }
            if (result is NetworkOwnerOpen.Ready) return result
            return if (owner.close()) result else NetworkOwnerOpen.Unknown
        }
    }
}

internal fun openAndroidNetworkOwner(
    connectivity: ConnectivityManager,
    leaseCurrent: () -> Boolean,
    stopRoundAndDns: () -> Unit,
): NetworkOwnerOpen = NetworkEpochOwner.open(
    register = { changed ->
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network): Unit = changed(DefaultNetworkEvent.Available(network.networkHandle))
            override fun onLost(network: Network): Unit = changed(DefaultNetworkEvent.Lost)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities): Unit =
                changed(DefaultNetworkEvent.Capabilities(network.networkHandle, capabilities.isNonVpn()))
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean): Unit =
                changed(DefaultNetworkEvent.Blocked(network.networkHandle, blocked))
            override fun onUnavailable(): Unit = changed(DefaultNetworkEvent.Lost)
        }
        connectivity.registerDefaultNetworkCallback(callback)
        AutoCloseable { connectivity.unregisterNetworkCallback(callback) }
    },
    snapshot = {
        val first = connectivity.activeNetwork
        val capabilities = first?.let(connectivity::getNetworkCapabilities)
        val second = connectivity.activeNetwork
        if (first == null || first != second || capabilities == null) null
        else DefaultNetworkSnapshot(first.networkHandle, capabilities.isNonVpn())
    },
    leaseCurrent = leaseCurrent,
    stopRoundAndDns = stopRoundAndDns,
)

private fun NetworkCapabilities.isNonVpn(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) && !hasTransport(NetworkCapabilities.TRANSPORT_VPN)
