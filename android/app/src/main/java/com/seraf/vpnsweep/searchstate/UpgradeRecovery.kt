package com.seraf.vpnsweep.searchstate

internal data class RecoveryStamp(val bootId: String) {
    fun valid(): Boolean = BOOT_ID.matches(bootId)

    companion object {
        private val BOOT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}

internal sealed interface RecoveryIntentState {
    data object Absent : RecoveryIntentState
    data object Done : RecoveryIntentState
    data object Unknown : RecoveryIntentState
    data class Armed(val stamp: RecoveryStamp) : RecoveryIntentState
}

internal interface RecoveryOwner : AutoCloseable

internal interface UpgradeRecoveryBackend {
    var recoveryBlocked: Boolean
    var quarantined: Boolean
    fun readIntent(): RecoveryIntentState
    fun writeIntent(stamp: RecoveryStamp): Boolean
    fun finishIntent(): Boolean
    fun readStamp(): RecoveryStamp?
    fun readRawMarker(): MarkerState
    fun clearRawMarker(): Boolean
    fun acquireOwner(): RecoveryOwner?
    fun reconcile(): Boolean
}

internal enum class UpgradeRecoveryResult { REBOOT_REQUIRED, RECOVERED, UNKNOWN }

internal class RecoveryFencedMarker(
    private val raw: SessionMarker,
    private val backend: UpgradeRecoveryBackend,
) : SessionMarker {
    override fun read(): MarkerState = synchronized(backend) {
        if (backend.quarantined) return@synchronized MarkerState.UNKNOWN
        when (intent()) {
            RecoveryIntentState.Absent, RecoveryIntentState.Done -> if (backend.recoveryBlocked) MarkerState.ACTIVE else raw.read()
            RecoveryIntentState.Unknown -> MarkerState.UNKNOWN
            is RecoveryIntentState.Armed -> MarkerState.ACTIVE
        }
    }

    override fun markActive(): Boolean = synchronized(backend) {
        mayWrite() && raw.markActive()
    }

    override fun markClear(): Boolean = synchronized(backend) {
        mayWrite() && raw.markClear()
    }

    private fun mayWrite(): Boolean = !backend.quarantined && !backend.recoveryBlocked && intent() in setOf(RecoveryIntentState.Absent, RecoveryIntentState.Done)

    private fun intent(): RecoveryIntentState = try {
        when (val value = backend.readIntent()) {
            is RecoveryIntentState.Armed -> if (value.stamp.valid()) value else RecoveryIntentState.Unknown
            else -> value
        }
    } catch (_: Exception) { RecoveryIntentState.Unknown }
}

internal class UpgradeRecovery(
    private val backend: UpgradeRecoveryBackend,
    private var eligibleAtStartup: Boolean,
) {
    init {
        if (eligibleAtStartup) backend.recoveryBlocked = true
    }

    fun blocked(): Boolean = synchronized(backend) {
        backend.quarantined || backend.recoveryBlocked || safeIntent() !in setOf(RecoveryIntentState.Absent, RecoveryIntentState.Done)
    }

    fun arm(): UpgradeRecoveryResult = synchronized(backend) {
        if (!eligibleAtStartup || backend.quarantined || interrupted() || safeRawMarker() == MarkerState.UNKNOWN) return@synchronized UpgradeRecoveryResult.UNKNOWN
        val existing = safeIntent()
        if (existing == RecoveryIntentState.Unknown) return@synchronized UpgradeRecoveryResult.UNKNOWN
        val stamp = safeStamp() ?: return@synchronized UpgradeRecoveryResult.UNKNOWN
        if (existing is RecoveryIntentState.Armed) {
            return@synchronized if (existing.stamp.valid() && existing.stamp == stamp) UpgradeRecoveryResult.REBOOT_REQUIRED else UpgradeRecoveryResult.UNKNOWN
        }
        val written = try { backend.writeIntent(stamp) } catch (_: Exception) { false }
        if (!written || interrupted() || safeIntent() != RecoveryIntentState.Armed(stamp)) return@synchronized UpgradeRecoveryResult.UNKNOWN
        UpgradeRecoveryResult.REBOOT_REQUIRED
    }

    fun recover(): UpgradeRecoveryResult = synchronized(backend) {
        if (!eligibleAtStartup || backend.quarantined || interrupted()) return@synchronized UpgradeRecoveryResult.UNKNOWN
        val saved = (safeIntent() as? RecoveryIntentState.Armed)?.stamp
            ?: return@synchronized UpgradeRecoveryResult.UNKNOWN
        if (!saved.valid() || safeRawMarker() == MarkerState.UNKNOWN) return@synchronized UpgradeRecoveryResult.UNKNOWN
        val current = safeStamp() ?: return@synchronized UpgradeRecoveryResult.UNKNOWN
        if (saved == current) return@synchronized UpgradeRecoveryResult.REBOOT_REQUIRED
        val owner = try { backend.acquireOwner() } catch (_: Exception) { null } catch (_: LinkageError) { null }
            ?: return@synchronized UpgradeRecoveryResult.UNKNOWN
        var ownerCloseAttempted = false
        try {
            if (interrupted() || !backend.reconcile() || interrupted()) return@synchronized UpgradeRecoveryResult.UNKNOWN
            if (!backend.clearRawMarker() || interrupted() || safeRawMarker() != MarkerState.CLEAR || interrupted()) return@synchronized UpgradeRecoveryResult.UNKNOWN
            ownerCloseAttempted = true
            owner.close()
            if (interrupted()) return@synchronized UpgradeRecoveryResult.UNKNOWN
            if (!backend.finishIntent() || safeIntent() != RecoveryIntentState.Done || interrupted()) {
                backend.quarantined = true
                return@synchronized UpgradeRecoveryResult.UNKNOWN
            }
            backend.recoveryBlocked = false
            eligibleAtStartup = false
            UpgradeRecoveryResult.RECOVERED
        } catch (_: Exception) {
            backend.quarantined = true
            UpgradeRecoveryResult.UNKNOWN
        } catch (_: LinkageError) {
            backend.quarantined = true
            UpgradeRecoveryResult.UNKNOWN
        } finally {
            if (!ownerCloseAttempted) try { owner.close() } catch (_: Exception) { backend.quarantined = true } catch (_: LinkageError) { backend.quarantined = true }
        }
    }

    private fun safeIntent(): RecoveryIntentState = try { backend.readIntent() } catch (_: Exception) { RecoveryIntentState.Unknown }
    private fun safeStamp(): RecoveryStamp? = try { backend.readStamp()?.takeIf { it.valid() } } catch (_: Exception) { null }
    private fun safeRawMarker(): MarkerState = try { backend.readRawMarker() } catch (_: Exception) { MarkerState.UNKNOWN }
    private fun interrupted(): Boolean = Thread.currentThread().isInterrupted
}

internal fun recoveryRequiredAtStartup(marker: SessionMarker, intent: RecoveryIntentState, strictMarker: () -> MarkerState): Boolean = try {
    when (intent) {
        RecoveryIntentState.Absent -> marker.read() != MarkerState.CLEAR
        RecoveryIntentState.Done -> strictMarker() != MarkerState.CLEAR
        RecoveryIntentState.Unknown, is RecoveryIntentState.Armed -> true
    }
} catch (_: Exception) { true }
