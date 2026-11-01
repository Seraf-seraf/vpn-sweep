package com.seraf.vpnsweep

import androidx.compose.runtime.Composable
import com.seraf.vpnsweep.ui.ServerSelectionDialogHost

data class ServerChoice(
    val id: String,
    val title: String,
    val subtitle: String,
    val supported: Boolean,
)

@Composable
fun ServerSelectionDialog(
    servers: List<ServerChoice>,
    selectedIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
    canConfirm: Boolean = true,
    errorMessage: String = "",
): Unit {
    ServerSelectionDialogHost(servers, selectedIds, onDismiss, onConfirm, canConfirm, errorMessage)
}
