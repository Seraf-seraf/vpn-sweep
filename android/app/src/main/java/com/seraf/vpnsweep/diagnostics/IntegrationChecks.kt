package com.seraf.vpnsweep.diagnostics

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.CancellationSignal
import android.os.SystemClock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Device evidence for the integration stage; independent of the sweep policy. */
internal object IntegrationChecksCore {
    private const val GOOGLE_HOST = "www.google.com"
    private const val DNS_ADDRESS = "1.1.1.1"
    private const val CONTROL_IPV6 = "2606:4700:4700::1111"
    private const val CONTROL_HOST = "one.one.one.one"
    private const val HTTPS_PORT = 443
    private const val DNS_PORT = 53
    private const val TIMEOUT_MS = 8000
    private const val DNS_TRANSACTION_ID_COUNT = 65536
    private const val MAX_DNS_RESPONSE_BYTES = 4096

    fun run(manager: ConnectivityManager, report: (String) -> Unit): Unit {
        val vpn = manager.activeNetwork
        if (vpn == null || !isVpn(manager, vpn)) {
            report("Проверка: системный VPN не активен")
            return
        }
        var addresses = emptyList<InetAddress>()
        fun runCheck(name: String, action: ((String) -> Unit) -> Unit): Unit {
            report("$name: выполняется…")
            val started = SystemClock.elapsedRealtime()
            var phase = "проверка VPN-сети"
            val result = try {
                requireVpn(manager, vpn)
                action { phase = it }
                requireVpn(manager, vpn)
                "успешно"
            } catch (exception: Exception) {
                "$phase: ${safeReason(exception)}"
            }
            report("$name: $result (${SystemClock.elapsedRealtime() - started} мс)")
        }
        // Numeric destinations keep TCP evidence independent of DNS failures.
        runCheck("TCP/IPv4 + TLS без DNS") { phase -> tls(vpn, InetAddress.getByName(DNS_ADDRESS), CONTROL_HOST, phase) }
        runCheck("TCP/IPv6 + TLS без DNS") { phase -> tls(vpn, InetAddress.getByName(CONTROL_IPV6), CONTROL_HOST, phase) }
        runCheck("DNS по TCP через VPN") { phase -> tcpDns(vpn, phase) }
        runCheck("UDP и DNS через VPN") { phase -> udpDns(vpn, phase) }
        runCheck("Системный DNS через VPN") { phase ->
            phase("системный DNS")
            addresses = systemDns(vpn)
        }
        for (ipv6 in listOf(false, true)) {
            val name = if (ipv6) "Google TCP/IPv6 + TLS" else "Google TCP/IPv4 + TLS"
            val address = addresses.firstOrNull { if (ipv6) it is Inet6Address else it is Inet4Address }
            if (address == null) report("$name: DNS не вернул адрес; не проверено")
            else runCheck(name) { phase -> tls(vpn, address, GOOGLE_HOST, phase) }
        }
    }

    private class CheckFailure(val reason: String) : IOException()

    private fun safeReason(exception: Exception): String {
        return when (exception) {
            is CheckFailure -> exception.reason
            is SocketTimeoutException -> "истекло время ожидания"
            is UnknownHostException -> "имя не разрешено"
            is SSLHandshakeException -> "ошибка TLS или сертификата"
            is SSLException -> "ошибка обмена TLS"
            is ConnectException -> "не удалось установить соединение"
            is SocketException -> "ошибка сетевого сокета"
            is IOException -> "соединение закрыто или ошибка ввода-вывода"
            is SecurityException -> "доступ к VPN-сети запрещён"
            else -> "ошибка проверки"
        }
    }

    private fun requireVpn(manager: ConnectivityManager, network: Network): Unit {
        if (!isVpn(manager, network)) throw CheckFailure("VPN отключён; проверка остановлена")
    }

    private fun isVpn(manager: ConnectivityManager, network: Network): Boolean {
        return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private fun tls(network: Network, address: InetAddress, host: String, phase: (String) -> Unit): Unit {
        phase("создание сокета VPN")
        network.socketFactory.createSocket().use { socket ->
            phase("соединение TCP")
            socket.connect(InetSocketAddress(address, HTTPS_PORT), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            phase("обмен TLS")
            (factory.createSocket(socket, host, HTTPS_PORT, true) as SSLSocket).use { tls ->
                tls.soTimeout = TIMEOUT_MS
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.startHandshake()
            }
        }
    }

    private fun systemDns(network: Network): List<InetAddress> {
        val done = CountDownLatch(1)
        val cancellation = CancellationSignal()
        var addresses = emptyList<InetAddress>()
        var failure: CheckFailure? = null
        try {
            DnsResolver.getInstance().query(
                network, GOOGLE_HOST, DnsResolver.FLAG_NO_CACHE_LOOKUP, Executor { it.run() }, cancellation,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int): Unit {
                        if (rcode == 0 && answer.isNotEmpty()) addresses = answer
                        else failure = CheckFailure("DNS вернул код $rcode или пустой ответ")
                        done.countDown()
                    }

                    override fun onError(error: DnsResolver.DnsException): Unit {
                        failure = CheckFailure("ошибка системного DNS (код ${error.code})")
                        done.countDown()
                    }
                },
            )
            if (!done.await(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)) throw SocketTimeoutException()
            failure?.let { throw it }
            return addresses
        } finally {
            cancellation.cancel()
        }
    }

    private fun dnsQuery(): ByteArray {
        val id = SecureRandom().nextInt(DNS_TRANSACTION_ID_COUNT)
        // A query for the same Google host, sent to the VPN's configured DNS.
        return byteArrayOf(
            (id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            3, 119, 119, 119, 6, 103, 111, 111, 103, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1,
        )
    }

    private fun validateDns(data: ByteArray, length: Int, query: ByteArray): Unit {
        if (length < 12 || data[0] != query[0] || data[1] != query[1] || data[2].toInt() and 0x80 == 0) {
            throw CheckFailure("неверный ответ DNS")
        }
        val rcode = data[3].toInt() and 0x0f
        if (rcode != 0) throw CheckFailure("DNS вернул код $rcode")
        if (data[6].toInt() == 0 && data[7].toInt() == 0) throw CheckFailure("пустой ответ DNS")
    }

    private fun tcpDns(network: Network, phase: (String) -> Unit): Unit {
        val query = dnsQuery()
        phase("создание сокета VPN")
        network.socketFactory.createSocket().use { socket ->
            phase("соединение TCP/53")
            socket.connect(InetSocketAddress(InetAddress.getByName(DNS_ADDRESS), DNS_PORT), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            phase("ответ DNS по TCP")
            val output = DataOutputStream(socket.getOutputStream())
            output.writeShort(query.size)
            output.write(query)
            output.flush()
            val input = DataInputStream(socket.getInputStream())
            val size = input.readUnsignedShort()
            if (size !in 12..MAX_DNS_RESPONSE_BYTES) throw CheckFailure("неверный размер ответа DNS")
            val response = ByteArray(size)
            input.readFully(response)
            validateDns(response, size, query)
        }
    }

    private fun udpDns(network: Network, phase: (String) -> Unit): Unit {
        val query = dnsQuery()
        phase("привязка UDP-сокета к VPN")
        DatagramSocket().use { socket ->
            network.bindSocket(socket)
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetAddress.getByName(DNS_ADDRESS), DNS_PORT)
            phase("ответ DNS по UDP")
            socket.send(DatagramPacket(query, query.size))
            val packet = DatagramPacket(ByteArray(MAX_DNS_RESPONSE_BYTES), MAX_DNS_RESPONSE_BYTES)
            socket.receive(packet)
            validateDns(packet.data, packet.length, query)
        }
    }
}
