package com.seraf.vpnsweep

import androidx.compose.runtime.Composable
import com.seraf.vpnsweep.ui.SubscriptionDialogHost

@Composable
fun SubscriptionDialog(onDismiss: () -> Unit, onSubmit: (String) -> Boolean): Unit {
    SubscriptionDialogHost(onDismiss, onSubmit)
}
