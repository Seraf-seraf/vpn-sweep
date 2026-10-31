package com.seraf.vpnsweep.subscriptionruntime

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.InetAddresses
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.seraf.vpnsweep.core.mobile.SubscriptionRoute
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val MAX_DNS_BYTES = 65_535
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val CANCELLATION_IDLE_SECONDS = 5L
private val dnsCancellationExecutor = ThreadPoolExecutor(
    0,
    1,
    CANCELLATION_IDLE_SECONDS,
    TimeUnit.SECONDS,
    LinkedBlockingQueue(),
    ThreadFactory { runnable -> Thread(runnable, "subscription-dns-cancel").apply { isDaemon = true } },
)

internal class AndroidSubscriptionRoute private constructor(
    private val manager: ConnectivityManager,
    private val selected: Network,
    private val properties: LinkProperties,
    private val capabilities: NetworkCapabilities,
    private val onRevoked: () -> Unit,
) : SubscriptionRoute, AutoCloseable {
    private val revoked = AtomicBoolean(false)
    private val lock = Any()
    private val pending = mutableSetOf<DnsQuery>()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network): Unit {
            if (network != selected) revoke()
        }

        override fun onLost(network: Network): Unit {
            if (network == selected) revoke()
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean): Unit {
            if (network == selected && blocked) revoke()
        }

        override fun onLinkPropertiesChanged(network: Network, value: LinkProperties): Unit {
            if (network == selected && value != properties) revoke()
        }

        override fun onCapabilitiesChanged(network: Network, value: NetworkCapabilities): Unit {
            if (network == selected && value != capabilities) revoke()
        }
    }

    override fun current(): Boolean = try {
        !revoked.get() && manager.activeNetwork == selected &&
            manager.getLinkProperties(selected) == properties &&
            manager.getNetworkCapabilities(selected) == capabilities &&
            manager.activeNetwork == selected && !revoked.get()
    } catch (_: Exception) {
        false
    }

    override fun queryDNS(packet: ByteArray, remainingMillis: Long): ByteArray {
        if (packet.isEmpty() || packet.size > MAX_DNS_BYTES || remainingMillis <= 0 || !current()) {
            throw IOException("subscription_network")
        }
        val budget = if (remainingMillis > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
            Long.MAX_VALUE
        } else {
            remainingMillis * NANOS_PER_MILLISECOND
        }
        val started = System.nanoTime()
        val query = DnsQuery()
        synchronized(lock) {
            if (revoked.get()) throw IOException("subscription_network")
            pending.add(query)
        }
        try {
            DnsResolver.getInstance().rawQuery(
                selected,
                packet.copyOf(),
                0,
                Executor { it.run() },
                query.signal,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int): Unit {
                        query.complete(if (answer.size in 1..MAX_DNS_BYTES) answer.copyOf() else null)
                    }

                    override fun onError(error: DnsResolver.DnsException): Unit = query.complete(null)
                },
            )
            val remaining = budget - (System.nanoTime() - started)
            if (remaining <= 0 || !query.done.await(remaining, TimeUnit.NANOSECONDS) ||
                budget - (System.nanoTime() - started) <= 0 || !current()
            ) throw IOException("subscription_network")
            return query.answer.get() ?: throw IOException("subscription_network")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("subscription_network")
        } finally {
            query.cancel()
            synchronized(lock) { pending.remove(query) }
        }
    }

    override fun verifyPublicDestination(address: String, remainingMillis: Long): Boolean {
        return try {
            if (remainingMillis <= 0 || !current() || properties.nat64Prefix != null) return false
            val destination = InetAddresses.parseNumericAddress(address)
            val matches = properties.routes.filter { it.matches(destination) }
            val bestPrefix = matches.maxOfOrNull { it.destination.prefixLength } ?: return false
            val best = matches.filter { it.destination.prefixLength == bestPrefix }
            best.isNotEmpty() && best.all {
                it.type == RouteInfo.RTN_UNICAST && !it.`interface`.isNullOrEmpty()
            } && current()
        } catch (_: Exception) {
            false
        }
    }

    override fun authorizeSocket(fd: Long, network: String, address: String, remainingMillis: Long): Boolean {
        return try {
            if (fd < 0 || fd > Int.MAX_VALUE || remainingMillis <= 0 || !current() ||
                (network != "tcp4" && network != "tcp6" && network != "tcp") || address.isEmpty()
            ) return false
            ParcelFileDescriptor.fromFd(fd.toInt()).use { selected.bindSocket(it.fileDescriptor) }
            current()
        } catch (_: Exception) {
            false
        }
    }

    private fun revoke(): Unit {
        if (!revoked.compareAndSet(false, true)) return
        val queries = synchronized(lock) { pending.toList() }
        for (query in queries) query.cancel()
        onRevoked()
    }

    fun cancel(): Unit = revoke()

    override fun close(): Unit {
        revoke()
        manager.unregisterNetworkCallback(callback)
    }

    private class DnsQuery {
        val signal = CancellationSignal()
        val answer = AtomicReference<ByteArray?>(null)
        val done = CountDownLatch(1)
        private val completed = AtomicBoolean(false)
        private val cancellationScheduled = AtomicBoolean(false)

        fun complete(value: ByteArray?): Unit {
            if (completed.compareAndSet(false, true)) {
                answer.set(value)
                done.countDown()
            }
        }

        fun cancel(): Unit {
            complete(null)
            if (!cancellationScheduled.compareAndSet(false, true)) return
            try {
                dnsCancellationExecutor.execute {
                    try {
                        signal.cancel()
                    } catch (_: Exception) {
                    }
                }
            } catch (_: RuntimeException) {
            }
        }
    }

    companion object {
        fun open(manager: ConnectivityManager, onRevoked: () -> Unit): AndroidSubscriptionRoute? {
            return try {
                val selected = manager.activeNetwork ?: return null
                val properties = manager.getLinkProperties(selected) ?: return null
                val capabilities = manager.getNetworkCapabilities(selected) ?: return null
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
                val route = AndroidSubscriptionRoute(manager, selected, properties, capabilities, onRevoked)
                manager.registerDefaultNetworkCallback(route.callback)
                if (route.current()) route else {
                    route.close()
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
