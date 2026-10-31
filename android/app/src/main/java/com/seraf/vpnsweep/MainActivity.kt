package com.seraf.vpnsweep

import com.seraf.vpnsweep.ui.SweepTheme
import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        setContent {
            val state by VpnStatus.state.collectAsStateWithLifecycle()
            val selected = remember { File(noBackupFilesDir, SweepVpnService.CONFIG_NAME).exists() }
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
            SweepTheme {
                VpnSweepScreen(
                    state = state,
                    selected = selected,
                    importing = false,
                    message = "",
                    testing = false,
                    checkResults = emptyList(),
                    onPower = {
                        if (busy) {
                            startService(Intent(this@MainActivity, SweepVpnService::class.java).setAction(SweepVpnService.STOP))
                        } else {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onImport = {},
                    onRunChecks = {},
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
