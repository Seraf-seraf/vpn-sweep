package com.seraf.vpnsweep

import android.net.ConnectivityManager
import com.seraf.vpnsweep.diagnostics.IntegrationChecksCore

object IntegrationChecks {
    fun run(manager: ConnectivityManager, report: (String) -> Unit): Unit =
        IntegrationChecksCore.run(manager, report)
}
