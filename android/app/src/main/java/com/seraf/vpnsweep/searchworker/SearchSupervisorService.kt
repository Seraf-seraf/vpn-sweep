package com.seraf.vpnsweep.searchworker

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process

internal class SearchSupervisorService : Service() {
    private val endpoint = Binder()

    override fun onBind(intent: Intent?): IBinder? =
        if (Application.getProcessName() == "$packageName:search") endpoint else null

    override fun onUnbind(intent: Intent?): Boolean {
        if (Application.getProcessName() == "$packageName:search") Process.killProcess(Process.myPid())
        return false
    }
}
