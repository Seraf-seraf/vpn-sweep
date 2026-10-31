package com.seraf.vpnsweep

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.seraf.vpnsweep.core.mobile.Mobile
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        setContent {
            val state by VpnStatus.state.collectAsStateWithLifecycle()
            val diagnosticsOwner = remember { DiagnosticsStatus.newOwner() }
            DisposableEffect(diagnosticsOwner) {
                diagnosticsOwner.attach()
                onDispose { diagnosticsOwner.close() }
            }
            val observedDiagnostics by diagnosticsOwner.state.collectAsStateWithLifecycle(
                initialValue = diagnosticsOwner.currentState,
            )
            val diagnostics = diagnosticsOwner.current(observedDiagnostics)
            val scope = rememberCoroutineScope()
            var selected by remember { mutableStateOf(File(noBackupFilesDir, SweepVpnService.CONFIG_NAME).exists()) }
            var importing by remember { mutableStateOf(false) }
            val importOwner = remember { ImportUiOwner() }
            var message by remember { mutableStateOf("") }
            var permissionMessage by remember { mutableStateOf("") }
            var subscriptionOpen by remember { mutableStateOf(false) }
            var serversOpen by remember { mutableStateOf(false) }
            val busy = state.phase in setOf(VpnPhase.CONNECTING, VpnPhase.RUNNING, VpnPhase.STOPPING)
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    permissionMessage = ""
                    startVpn()
                } else {
                    permissionMessage = "Подключение VPN не разрешено"
                }
            }
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                val intent = VpnService.prepare(this@MainActivity)
                if (intent == null) {
                    permissionMessage = ""
                    startVpn()
                } else {
                    permission.launch(intent)
                }
            }
            val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    val owner = importOwner.begin()
                    importing = true
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            val token = configImportCoordinator.begin()
                            val saved = withContext(Dispatchers.IO) {
                                configImportCoordinator.complete(
                                    token = token,
                                    read = { readImportedConfig(contentResolver.openInputStream(uri)) },
                                    validate = { bytes -> Mobile.validateConfig(bytes) },
                                    write = { bytes, checkBeforeCommit ->
                                        val file = AtomicFile(File(noBackupFilesDir, SweepVpnService.CONFIG_NAME))
                                        writeImportedConfig(file, bytes, checkBeforeCommit)
                                    },
                                )
                            }
                            if (saved && importOwner.isCurrent(owner)) {
                                selected = true
                                message = "Результат выбран. Параметры outbound сохранены"
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (importOwner.isCurrent(owner)) message = "Не удалось прочитать результат vpn-sweep"
                        } finally {
                            if (importOwner.finish(owner)) importing = false
                        }
                    }
                }
            }
            SweepTheme {
                VpnSweepScreen(
                    state = state,
                    selected = selected,
                    importing = importing,
                    message = message,
                    testing = diagnostics.testing,
                    checkResults = diagnostics.results,
                    onPower = {
                        if (busy) {
                            startService(Intent(this@MainActivity, SweepVpnService::class.java).setAction(SweepVpnService.STOP))
                        } else {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onImport = { document.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                    onRunChecks = onRunChecks@{
                        val token = diagnosticsOwner.begin() ?: return@onRunChecks
                        val appContext = applicationContext
                        val job = diagnosticsOwner.launch {
                            var successful = false
                            try {
                                val checkContext = coroutineContext
                                checkContext.ensureActive()
                                if (!diagnosticsOwner.admit(token)) {
                                    throw CancellationException("diagnostics_stale")
                                }
                                IntegrationChecks.run(appContext.getSystemService(ConnectivityManager::class.java)) { result ->
                                    checkContext.ensureActive()
                                    if (!diagnosticsOwner.report(token, result)) {
                                        throw CancellationException("diagnostics_stale")
                                    }
                                }
                                successful = true
                            } finally {
                                diagnosticsOwner.finish(token, successful)
                            }
                        }
                        if (diagnosticsOwner.attachJob(token, job)) {
                            if (!job.start()) diagnosticsOwner.finish(token, successful = false)
                        } else {
                            diagnosticsOwner.cancel(job)
                            diagnosticsOwner.finish(token, successful = false)
                        }
                    },
                    onSubscription = { subscriptionOpen = true },
                    onServers = { serversOpen = true },
                    permissionMessage = permissionMessage,
                )
                if (subscriptionOpen) {
                    SubscriptionDialog(onDismiss = { subscriptionOpen = false })
                }
                if (serversOpen) {
                    ServerSelectionDialog(
                        servers = emptyList(),
                        selectedIds = emptySet(),
                        onDismiss = { serversOpen = false },
                        // TODO: connect confirmed server selection when a subscription runtime exists.
                        onConfirm = {},
                    )
                }
            }
        }
    }

    private fun startVpn(): Unit {
        startForegroundService(Intent(this, SweepVpnService::class.java).setAction(SweepVpnService.START))
    }
}
