package com.seraf.vpnsweep

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.seraf.vpnsweep.core.mobile.Mobile
import com.seraf.vpnsweep.core.mobile.Protector
import java.io.File

class SweepVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connection: Job? = null

    override fun onCreate(): Unit {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "VPN-подключение", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            connection?.cancel()
            if (connection == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != START || prepare(this) != null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (connection?.isCompleted == false) return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        VpnStatus.update(VpnState(VpnPhase.CONNECTING, "Подключение…"))
        connection = scope.launch {
            var tunnel: ParcelFileDescriptor? = null
            var failure: String? = null
            try {
                val manager = getSystemService(ConnectivityManager::class.java)
                val network = manager.activeNetwork ?: error("network_unavailable")
                val capabilities = manager.getNetworkCapabilities(network)
                check(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true) {
                    "disconnect_other_vpn"
                }
                val dns = manager.getLinkProperties(network)?.dnsServers?.firstOrNull()
                    ?.hostAddress ?: error("network_dns_unavailable")
                val source = File(noBackupFilesDir, CONFIG_NAME).readBytes()
                val digest = Mobile.outboundDigest(source)
                Mobile.validateConfig(source)
                setUnderlyingNetworks(arrayOf(network))
                tunnel = Builder()
                    .setSession("VPN Sweep")
                    .setMtu(MTU_BYTES)
                    .addAddress("10.111.0.1", 30)
                    .addAddress("fd66:7377:6565::1", 126)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer("1.1.1.1")
                    .setMetered(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
                    .establish() ?: error("vpn_permission_revoked")
                Mobile.start(source, tunnel.fd.toLong(), dns, SocketProtector(network))
                while (true) {
                    check(manager.getNetworkCapabilities(network) != null) { "network_lost" }
                    check(Mobile.protectionFailures() == 0L) { "socket_protection_failed" }
                    VpnStatus.update(
                        VpnState(
                            VpnPhase.RUNNING,
                            "Туннель запущен · доступность сети требует проверки",
                            digest,
                            Mobile.protectedSockets(),
                            Mobile.protectionFailures(),
                            Mobile.tunUplinkBytes(),
                            Mobile.tunDownlinkBytes(),
                        ),
                    )
                    delay(STATUS_INTERVAL_MS)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                // Never expose core messages, the configuration or endpoint names.
                failure = when (exception.message) {
                    "network_unavailable" -> "Нет доступной сети"
                    "disconnect_other_vpn" -> "Отключите другой VPN перед проверкой"
                    "network_dns_unavailable" -> "Сеть не предоставила DNS для подключения к серверу"
                    "vpn_permission_revoked" -> "Разрешение VPN отозвано"
                    "network_lost" -> "Сеть потеряна. Подключите VPN заново"
                    "socket_protection_failed" -> "Не удалось защитить сетевой сокет; VPN остановлен"
                    else -> "Не удалось запустить VPN. Проверьте выбранный результат"
                }
            } finally {
                withContext(NonCancellable) {
                    VpnStatus.update(VpnState(VpnPhase.STOPPING, "Отключение…"))
                    try {
                        Mobile.stop()
                    } catch (_: Exception) {
                        failure = "Ошибка остановки ядра"
                    } finally {
                        try {
                            tunnel?.close()
                        } catch (_: Exception) {
                            failure = "Ошибка закрытия VPN-интерфейса"
                        }
                        VpnStatus.update(
                            failure?.let { VpnState(VpnPhase.ERROR, it) } ?: VpnState(),
                        )
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke(): Unit {
        connection?.cancel()
        super.onRevoke()
    }

    override fun onDestroy(): Unit {
        scope.cancel()
        super.onDestroy()
    }

    private inner class SocketProtector(private val network: Network) : Protector {
        override fun protect(fd: Long): Boolean {
            if (!this@SweepVpnService.protect(fd.toInt())) return false
            return try {
                ParcelFileDescriptor.fromFd(fd.toInt()).use { network.bindSocket(it.fileDescriptor) }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SweepVpnService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("VPN Sweep · проверка подключения")
            .setContentText("Подключение и состояние — в приложении")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Отключить", stop).build())
            .build()
    }

    companion object {
        const val START = "com.seraf.vpnsweep.START"
        const val STOP = "com.seraf.vpnsweep.STOP"
        const val CONFIG_NAME = "integration-result.json"
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 1
        private const val MTU_BYTES = 1500
        private const val STATUS_INTERVAL_MS = 1000L
    }
}
