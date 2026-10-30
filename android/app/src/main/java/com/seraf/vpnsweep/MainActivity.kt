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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
            var testing by remember { mutableStateOf(false) }
            var checkResults by remember { mutableStateOf(emptyList<String>()) }
            val busy = state.phase in setOf(VpnPhase.CONNECTING, VpnPhase.RUNNING, VpnPhase.STOPPING)
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) startVpn()
                else message = "Подключение VPN не разрешено"
            }
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                val intent = VpnService.prepare(this@MainActivity)
                if (intent == null) startVpn() else permission.launch(intent)
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
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFA9BFFF), secondary = Color(0xFFC6AEF5),
                    background = Color(0xFF10131E), surface = Color(0xFF191E2E),
                ),
            ) {
                Scaffold { padding: PaddingValues ->
                    Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
                        Column(
                            modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            Text("VPN Sweep", style = MaterialTheme.typography.headlineLarge)
                            Text("Проверка Android-интеграции · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                            Text("Выберите сохранённый working.json и разрешите системное VPN-подключение.")
                            Text(state.message, style = MaterialTheme.typography.titleLarge)
                            OutlinedButton(
                                onClick = { document.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                                enabled = !busy && !importing,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (importing) "Чтение…" else "Выбрать working.json") }
                            Button(
                                onClick = {
                                    if (busy) {
                                        startService(Intent(this@MainActivity, SweepVpnService::class.java).setAction(SweepVpnService.STOP))
                                    } else {
                                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                enabled = selected && !importing && state.phase != VpnPhase.STOPPING,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (busy) "Отключить" else "Подключить") }
                            if (message.isNotEmpty()) Text(message)
                            OutlinedButton(
                                enabled = state.phase == VpnPhase.RUNNING && !testing,
                                onClick = {
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
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (testing) "Проверка сети…" else "Проверить TCP, UDP и DNS") }
                            for (result in checkResults) Text(result)
                            if (state.outboundDigest.isNotEmpty()) {
                                Text("Защищено сокетов: ${state.protectedSockets}\nОшибок защиты: ${state.protectionFailures}")
                                Text("Данные TUN → Xray: ${state.tunUplinkBytes} байт\nДанные Xray → TUN: ${state.tunDownlinkBytes} байт")
                                Text("SHA-256 outbounds:\n${state.outboundDigest}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "На этом этапе проверяются TUN, DNS и передача трафика. Подписки и подбор будут доступны после проверки интеграции.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
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
