package com.seraf.vpnsweep

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnPhase { DISCONNECTED, CONNECTING, RUNNING, STOPPING, ERROR }

data class VpnState(
    val phase: VpnPhase = VpnPhase.DISCONNECTED,
    val message: String = "Отключено",
    val outboundDigest: String = "",
    val protectedSockets: Long = 0,
    val protectionFailures: Long = 0,
    val tunUplinkBytes: Long = 0,
    val tunDownlinkBytes: Long = 0,
)

object VpnStatus {
    private val mutableState = MutableStateFlow(VpnState())
    val state = mutableState.asStateFlow()

    internal var onPhaseChanged: ((VpnState) -> Unit)? = null

    @Synchronized
    fun update(value: VpnState): Unit {
        val changed = mutableState.value.phase != value.phase
        mutableState.value = value
        if (changed) onPhaseChanged?.invoke(value)
    }
}
