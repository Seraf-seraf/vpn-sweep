package com.seraf.vpnsweep

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import com.seraf.vpnsweep.ui.VpnSweepScreenContent
import com.seraf.vpnsweep.searchui.ScreenInformation

@Composable
fun VpnSweepScreen(
    state: VpnState,
    selected: Boolean,
    importing: Boolean,
    message: String,
    testing: Boolean,
    checkResults: List<String>,
    onPower: () -> Unit,
    onImport: () -> Unit,
    onRunChecks: () -> Unit,
    onSubscription: () -> Unit,
    onServers: () -> Unit,
    permissionMessage: String = "",
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
    sourceEnabled: Boolean = true,
    searchEnabled: Boolean = false,
    searchRunning: Boolean = false,
    searchMessage: String = "Подбор настроек пока недоступен",
    onSearch: () -> Unit = {},
    information: ScreenInformation = ScreenInformation(),
): Unit {
    VpnSweepScreenContent(
        state = state,
        selected = selected,
        importing = importing,
        onPower = onPower,
        onSubscription = onSubscription,
        onServers = onServers,
        permissionMessage = permissionMessage,
        contentWindowInsets = contentWindowInsets,
        sourceEnabled = sourceEnabled,
        searchEnabled = searchEnabled,
        searchRunning = searchRunning,
        searchMessage = searchMessage,
        onSearch = onSearch,
        information = information,
    )
}
