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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
            val scope = rememberCoroutineScope()
            var selected by remember { mutableStateOf(File(noBackupFilesDir, SweepVpnService.CONFIG_NAME).exists()) }
            var importing by remember { mutableStateOf(false) }
            var message by remember { mutableStateOf("") }
            var permissionMessage by remember { mutableStateOf("") }
            var testing by remember { mutableStateOf(false) }
            var checkResults by remember { mutableStateOf(emptyList<String>()) }
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
                    importing = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val bytes = contentResolver.openInputStream(uri)?.use {
                                    it.readNBytes(MAX_CONFIG_BYTES + 1)
                                } ?: error("file_unavailable")
                                check(bytes.size <= MAX_CONFIG_BYTES)
                                Mobile.validateConfig(bytes)
                                val file = AtomicFile(File(noBackupFilesDir, SweepVpnService.CONFIG_NAME))
                                val stream = file.startWrite()
                                try {
                                    stream.write(bytes)
                                    file.finishWrite(stream)
                                } catch (exception: Exception) {
                                    file.failWrite(stream)
                                    throw exception
                                }
                            }
                            selected = true
                            message = "Результат выбран. Параметры outbound сохранены"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            message = "Не удалось прочитать результат vpn-sweep"
                        } finally {
                            importing = false
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
                    testing = testing,
                    checkResults = checkResults,
                    onPower = {
                        if (busy) {
                            startService(Intent(this@MainActivity, SweepVpnService::class.java).setAction(SweepVpnService.STOP))
                        } else {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onImport = { document.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                    onRunChecks = {
                        testing = true
                        checkResults = emptyList()
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val checkContext = coroutineContext
                                    IntegrationChecks.run(getSystemService(ConnectivityManager::class.java)) { result ->
                                        checkContext.ensureActive()
                                        runOnUiThread { checkResults = checkResults + result }
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } finally {
                                testing = false
                            }
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

    companion object {
        private const val MAX_CONFIG_BYTES = 4 * 1024 * 1024
    }
}
