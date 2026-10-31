package com.seraf.vpnsweep.searchnetwork

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.CancellationSignal
import com.seraf.vpnsweep.searchprotocol.SearchNetworkV1
import com.seraf.vpnsweep.searchworker.RoundOwner
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val MAX_DNS_WIRE_BYTES = 65535
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val EXECUTOR_IDLE_SECONDS = 5L

internal interface DnsNetwork<N : Any> {
    fun activeNetwork(): N?
    fun identity(network: N): Long
    fun isNotVpn(network: N): Boolean
    fun newRequest(): DnsRequest<N>
}

internal interface DnsRequest<N : Any> {
    fun start(network: N, packet: ByteArray, callback: (ByteArray?) -> Unit): Unit
    fun cancel(): Unit
}

internal fun selectedNetworkDns(
    manager: ConnectivityManager,
    selected: SearchNetworkV1,
    owner: RoundOwner,
    onRevoked: () -> Unit,
): SelectedNetworkDns<Network> = SelectedNetworkDns(selected, owner, AndroidDnsNetwork(manager), onRevoked)

// The owner validates the selected epoch and lease; its lifecycle must call
// revoke on network, lease, or owner death events, even with no DNS in flight.
internal class SelectedNetworkDns<N : Any>(
    private val selected: SearchNetworkV1,
    private val owner: RoundOwner,
    private val network: DnsNetwork<N>,
    private val onRevoked: () -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var stopped = false
    private val pending = mutableSetOf<Query>()
    private val dispatch = singleWorker("search-dns-dispatch")
    private val cancellations = singleWorker("search-dns-cancel")
    private val notifications = singleWorker("search-dns-revoked")

    fun queryDns(packet: ByteArray, remainingMillis: Long): ByteArray {
        if (packet.isEmpty() || packet.size > MAX_DNS_WIRE_BYTES) throw IOException("invalid_dns_request")
        if (remainingMillis <= 0) throw SocketTimeoutException("search_dns_deadline")
        val started = System.nanoTime()
        val budgetNanos = if (remainingMillis > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
            Long.MAX_VALUE
        } else {
            remainingMillis * NANOS_PER_MILLISECOND
        }
        val query = Query()
        synchronized(lock) {
            if (stopped) throw IOException("search_network_unavailable")
            pending.add(query)
        }
        val data = packet.copyOf()
        val task = Runnable { dispatch(query, data, started, budgetNanos) }
        try {
            dispatch.execute(task)
            val remaining = remainingNanos(started, budgetNanos)
            if (remaining <= 0 || !query.done.await(remaining, TimeUnit.NANOSECONDS) ||
                remainingNanos(started, budgetNanos) <= 0
            ) {
                query.cancel()
                throw SocketTimeoutException("search_dns_deadline")
            }
            val answer = query.answer()
            val allowed = synchronized(lock) { !stopped }
            if (!allowed || answer == null) throw IOException("search_network_unavailable")
            return answer
        } catch (exception: InterruptedException) {
            query.cancel()
            Thread.currentThread().interrupt()
            throw InterruptedIOException("search_dns_interrupted")
        } catch (exception: RuntimeException) {
            revoke()
            throw IOException("search_network_unavailable")
        } finally {
            dispatch.remove(task)
            synchronized(lock) { pending.remove(query) }
        }
    }

    fun revoke(): Unit = stop(notify = true)

    override fun close(): Unit = stop(notify = false)

    private fun stop(notify: Boolean): Unit {
        val active = synchronized(lock) {
            if (stopped) return
            stopped = true
            pending.toList()
        }
        for (query in active) query.cancel()
        if (notify) {
            notifications.execute {
                try {
                    onRevoked()
                } catch (_: Exception) {
                    // Revocation remains effective even if the owner callback fails.
                }
            }
        }
    }

    private fun dispatch(query: Query, packet: ByteArray, started: Long, budgetNanos: Long): Unit {
        try {
            if (query.finished() || remainingNanos(started, budgetNanos) <= 0) return
            val current = currentNetwork()
            if (current == null) {
                if (!query.finished()) revoke()
                return
            }
            if (query.finished() || remainingNanos(started, budgetNanos) <= 0) return
            val request = network.newRequest()
            query.install(request)
            if (query.finished() || remainingNanos(started, budgetNanos) <= 0) {
                query.cancel()
                return
            }
            request.start(current, packet) { answer ->
                if (!query.finished()) {
                    if (remainingNanos(started, budgetNanos) <= 0) {
                        query.cancel()
                    } else {
                        val validAnswer = answer != null && answer.isNotEmpty() && answer.size <= MAX_DNS_WIRE_BYTES
                        val currentAnswerNetwork = if (validAnswer) currentNetwork() else null
                        if (!query.finished()) {
                            if (currentAnswerNetwork == null || answer == null) revoke()
                            else query.complete(answer.copyOf())
                        }
                    }
                }
            }
        } catch (_: Exception) {
            if (!query.finished()) revoke()
        } catch (_: LinkageError) {
            if (!query.finished()) revoke()
        }
    }

    private fun currentNetwork(): N? = try {
        currentNetworkUnchecked()
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }

    private fun currentNetworkUnchecked(): N? {
        if (!owner.isCurrent(selected)) return null
        val active = network.activeNetwork() ?: return null
        if (network.identity(active) != selected.identity || !network.isNotVpn(active) ||
            network.activeNetwork() != active || !owner.isCurrent(selected)
        ) return null
        return active
    }

    private inner class Query {
        val done = CountDownLatch(1)
        private val resultLock = Any()
        private var completed = false
        private var response: ByteArray? = null
        private val cancelled = AtomicBoolean(false)
        private val cancelScheduled = AtomicBoolean(false)
        private val request = AtomicReference<DnsRequest<N>?>(null)

        fun finished(): Boolean = synchronized(resultLock) { completed }

        fun answer(): ByteArray? = synchronized(resultLock) { response }

        fun complete(answer: ByteArray?): Unit {
            val completedNow = synchronized(resultLock) {
                if (completed) false else {
                    response = answer
                    completed = true
                    true
                }
            }
            if (completedNow) done.countDown()
        }

        fun install(value: DnsRequest<N>): Unit {
            request.set(value)
            if (cancelled.get()) scheduleCancellation(value)
        }

        fun cancel(): Unit {
            cancelled.set(true)
            complete(null)
            request.get()?.let(::scheduleCancellation)
        }

        private fun scheduleCancellation(value: DnsRequest<N>): Unit {
            if (!cancelScheduled.compareAndSet(false, true)) return
            cancellations.execute {
                try {
                    value.cancel()
                } catch (_: Exception) {
                    // The query is already closed to late results.
                }
            }
        }
    }
}

private fun remainingNanos(started: Long, budget: Long): Long = budget - (System.nanoTime() - started)

private fun singleWorker(name: String): ThreadPoolExecutor = ThreadPoolExecutor(
    0,
    1,
    EXECUTOR_IDLE_SECONDS,
    TimeUnit.SECONDS,
    LinkedBlockingQueue(),
    ThreadFactory { runnable -> Thread(runnable, name).apply { isDaemon = true } },
)

private class AndroidDnsNetwork(private val manager: ConnectivityManager) : DnsNetwork<Network> {
    override fun activeNetwork(): Network? = manager.activeNetwork

    override fun identity(network: Network): Long = network.networkHandle

    override fun isNotVpn(network: Network): Boolean {
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    override fun newRequest(): DnsRequest<Network> = AndroidDnsRequest()
}

private class AndroidDnsRequest : DnsRequest<Network> {
    private val signal = CancellationSignal()

    override fun start(network: Network, packet: ByteArray, callback: (ByteArray?) -> Unit): Unit {
        DnsResolver.getInstance().rawQuery(
            network,
            packet,
            0,
            Executor { it.run() },
            signal,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int): Unit = callback(answer)
                override fun onError(error: DnsResolver.DnsException): Unit = callback(null)
            },
        )
    }

    override fun cancel(): Unit = signal.cancel()
}
