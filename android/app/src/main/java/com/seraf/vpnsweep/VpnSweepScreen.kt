package com.seraf.vpnsweep

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import com.seraf.vpnsweep.ui.VpnSweepScreenContent

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
): Unit {
    VpnSweepScreenContent(
        state = state,
        selected = selected,
        importing = importing,
        message = message,
        testing = testing,
        checkResults = checkResults,
        onPower = onPower,
        onImport = onImport,
        onRunChecks = onRunChecks,
        onSubscription = onSubscription,
        onServers = onServers,
        permissionMessage = permissionMessage,
        contentWindowInsets = contentWindowInsets,
    )
}
