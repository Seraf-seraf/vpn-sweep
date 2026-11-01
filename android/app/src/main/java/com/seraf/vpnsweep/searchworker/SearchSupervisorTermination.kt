package com.seraf.vpnsweep.searchworker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.Process
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal enum class SupervisorStartResult { STARTED, RETRYING, UNKNOWN }

internal class SearchSupervisorTermination(
    context: Context,
    executor: Executor,
    confirmed: (SupervisorDeathProof) -> Unit,
    retry: () -> Unit,
    failed: () -> Unit,
) {
    private val app = requireNotNull(context.applicationContext)
    private val started = AtomicBoolean()
    private val attempt: SupervisorBindingAttempt = SupervisorBindingAttempt(
        dispatch = { action -> try { executor.execute(action); true } catch (_: Exception) { false } },
        link = { value, died ->
            val remote = value as? IBinder
            if (remote == null || remote is Binder) false else try { remote.linkToDeath(died, 0); true }
            catch (_: Exception) { false } catch (_: LinkageError) { false }
        },
        unbind = { app.unbindService(connection); true },
        confirmed = confirmed,
        retry = retry,
        failed = failed,
    )
    val identity: Any get() = attempt.identity
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?): Unit =
            attempt.connected(if (name != ComponentName(app, SearchSupervisorService::class.java) || service is Binder) null else service)
        override fun onServiceDisconnected(name: ComponentName?): Unit = attempt.bindingDied()
        override fun onBindingDied(name: ComponentName?): Unit = attempt.bindingDied()
        override fun onNullBinding(name: ComponentName?): Unit = attempt.connected(null)
    }

    fun start(): SupervisorStartResult {
        if (!started.compareAndSet(false, true) || !samePrivateSearchProcess()) return SupervisorStartResult.UNKNOWN
        val bound = try {
            app.bindService(Intent(app, SearchSupervisorService::class.java), connection, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) { attempt.bindUnknown(); return SupervisorStartResult.UNKNOWN } catch (_: LinkageError) { attempt.bindUnknown(); return SupervisorStartResult.UNKNOWN }
        attempt.bindReturned(bound)
        return if (bound) SupervisorStartResult.STARTED else SupervisorStartResult.RETRYING
    }

    fun connectionTimedOut(): Unit = attempt.connectionTimedOut()

    private fun samePrivateSearchProcess(): Boolean = try {
        val flags = PackageManager.ComponentInfoFlags.of(0)
        val supervisor = app.packageManager.getServiceInfo(ComponentName(app, SearchSupervisorService::class.java), flags)
        val worker = app.packageManager.getServiceInfo(ComponentName(app, SearchWorkerService::class.java), flags)
        fun valid(info: ServiceInfo): Boolean = !info.exported && info.applicationInfo.uid == Process.myUid() &&
            info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS == 0 && info.processName == "${app.packageName}:search"
        valid(supervisor) && valid(worker)
    } catch (_: Exception) { false } catch (_: LinkageError) { false }
}
