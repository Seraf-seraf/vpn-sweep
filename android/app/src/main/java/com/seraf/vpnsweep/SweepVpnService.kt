package com.seraf.vpnsweep

import com.seraf.vpnsweep.diagnostics.DiagnosticsStatus
import com.seraf.vpnsweep.vpn.VpnCancellation
import com.seraf.vpnsweep.vpn.VpnRun
import com.seraf.vpnsweep.vpn.VpnRunFailure
import com.seraf.vpnsweep.vpn.VpnRunOperations
import com.seraf.vpnsweep.vpn.VpnRunRegistry
import com.seraf.vpnsweep.vpn.VpnServiceLifecycle
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.seraf.vpnsweep.core.mobile.Mobile
import com.seraf.vpnsweep.core.mobile.Protector
import java.io.File

class SweepVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = VpnServiceLifecycle()

    override fun onCreate(): Unit {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "VPN-подключение", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycle.started(startId)
        if (intent?.action == STOP) {
            if (lifecycle.current()?.stop() != true) stopRejected(startId)
            return START_NOT_STICKY
        }
        val epoch = lifecycle.epoch()
        if (intent?.action != START || epoch == null || prepare(this) != null) {
            if (lifecycle.current()?.stop() != true) stopRejected(startId)
            return START_NOT_STICKY
        }
        val diagnosticsSession = Any()
        val run = DiagnosticsStatus.withDeferredDelivery {
            lifecycle.reserve(sessions, epoch,
                deferDelivery = { DiagnosticsStatus.withDeferredDelivery(it) },
                onReserved = { DiagnosticsStatus.reserve(diagnosticsSession) },
            ) {
                DiagnosticsStatus.stopping(diagnosticsSession)
                VpnStatus.update(VpnState(VpnPhase.STOPPING, "Отключение…"))
            }
        }
        if (run == null) {
            if (!lifecycle.hasActiveConnection()) stopRejected(startId)
            return START_NOT_STICKY
        }
        run.launch(scope, ConnectionOperations(diagnosticsSession)) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            run.publishIfActive { VpnStatus.update(VpnState(VpnPhase.CONNECTING, "Подключение…")) }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke(): Unit {
        val run = lifecycle.beginRevoke()
        try {
            run?.stop()
            super.onRevoke()
        } finally {
            lifecycle.endRevoke()
        }
    }

    override fun onDestroy(): Unit {
        val run = lifecycle.destroy()
        run?.stop()
        VpnCancellation.scheduleScope(scope)
        super.onDestroy()
    }

    private fun stopRejected(startId: Int): Unit {
        scope.launch(Dispatchers.Main.immediate) { stopSelfResult(startId) }
    }

    private data class Preparation(
        val manager: ConnectivityManager,
        val network: Network,
        val capabilities: NetworkCapabilities?,
        val dns: String,
        val source: ByteArray,
        val digest: String,
    )

    private inner class ConnectionOperations(private val diagnosticsSession: Any) : VpnRunOperations<Preparation, ParcelFileDescriptor> {
        override fun prepare(run: VpnRun): Preparation {
            val manager = getSystemService(ConnectivityManager::class.java)
            val network = manager.activeNetwork ?: error("network_unavailable")
            val capabilities = manager.getNetworkCapabilities(network)
            check(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true) {
                "disconnect_other_vpn"
            }
            val dns = manager.getLinkProperties(network)?.dnsServers?.firstOrNull()
                ?.hostAddress ?: error("network_dns_unavailable")
            run.checkActive()
            val source = File(noBackupFilesDir, CONFIG_NAME).readBytes()
            run.checkActive()
            val digest = Mobile.outboundDigest(source)
            run.checkActive()
            Mobile.validateConfig(source)
            run.checkActive()
            return Preparation(manager, network, capabilities, dns, source, digest)
        }

        override fun setUnderlying(prepared: Preparation): Unit {
            setUnderlyingNetworks(arrayOf(prepared.network))
        }

        override fun establish(prepared: Preparation): ParcelFileDescriptor = Builder()
            .setSession("VPN Sweep")
            .setMtu(MTU_BYTES)
            .addAddress("10.111.0.1", 30)
            .addAddress("fd66:7377:6565::1", 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .setMetered(prepared.capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
            .establish() ?: error("vpn_permission_revoked")

        override fun start(prepared: Preparation, tunnel: ParcelFileDescriptor): Unit {
            Mobile.start(prepared.source, tunnel.fd.toLong(), prepared.dns, SocketProtector(prepared.network))
        }

        override suspend fun monitor(prepared: Preparation, run: VpnRun): Unit {
            while (true) {
                check(prepared.manager.getNetworkCapabilities(prepared.network) != null) { "network_lost" }
                check(Mobile.protectionFailures() == 0L) { "socket_protection_failed" }
                val state = VpnState(
                    VpnPhase.RUNNING,
                    "Туннель запущен · доступность сети требует проверки",
                    prepared.digest,
                    Mobile.protectedSockets(),
                    Mobile.protectionFailures(),
                    Mobile.tunUplinkBytes(),
                    Mobile.tunDownlinkBytes(),
                )
                run.publishIfActive {
                    DiagnosticsStatus.running(diagnosticsSession)
                    VpnStatus.update(state)
                }
                delay(STATUS_INTERVAL_MS)
            }
        }

        override fun stop(): Unit { Mobile.stop() }

        override fun close(tunnel: ParcelFileDescriptor): Unit { tunnel.close() }

        override suspend fun cleanupService(run: VpnRun, complete: (Exception?) -> Unit): Unit {
            lifecycle.cleanup(
                run = run,
                dispatcher = Dispatchers.Main.immediate,
                stopForeground = { stopForeground(STOP_FOREGROUND_REMOVE) },
                stopSelf = { startId -> stopSelfResult(startId) },
                complete = complete,
            )
        }

        override fun finished(failure: VpnRunFailure?): Unit {
            // Never expose core messages, the configuration or endpoint names.
            val message = when (failure) {
                is VpnRunFailure.Operation -> when (failure.exception.message) {
                    "network_unavailable" -> "Нет доступной сети"
                    "disconnect_other_vpn" -> "Отключите другой VPN перед проверкой"
                    "network_dns_unavailable" -> "Сеть не предоставила DNS для подключения к серверу"
                    "vpn_permission_revoked" -> "Разрешение VPN отозвано"
                    "network_lost" -> "Сеть потеряна. Подключите VPN заново"
                    "socket_protection_failed" -> "Не удалось защитить сетевой сокет; VPN остановлен"
                    else -> "Не удалось запустить VPN. Проверьте выбранный результат"
                }
                VpnRunFailure.Foreground -> "Не удалось запустить VPN-сервис"
                VpnRunFailure.Status -> "Ошибка обновления состояния VPN"
                VpnRunFailure.Stop -> "Ошибка остановки ядра"
                VpnRunFailure.Close -> "Ошибка закрытия VPN-интерфейса"
                VpnRunFailure.ServiceCleanup -> "Ошибка остановки VPN-сервиса"
                null -> null
            }
            DiagnosticsStatus.publishIfCurrent(diagnosticsSession) {
                VpnStatus.update(message?.let { VpnState(VpnPhase.ERROR, it) } ?: VpnState())
            }
        }
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
        private val sessions = VpnRunRegistry()
        const val START = "com.seraf.vpnsweep.START"
        const val STOP = "com.seraf.vpnsweep.STOP"
        const val CONFIG_NAME = "integration-result.json"
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 1
        private const val MTU_BYTES = 1500
        private const val STATUS_INTERVAL_MS = 1000L
    }
}
