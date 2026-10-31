package com.seraf.vpnsweep.searchstate

internal enum class MarkerState { CLEAR, ACTIVE, UNKNOWN }

internal interface SessionMarker {
    fun read(): MarkerState
    fun markActive(): Boolean
    fun markClear(): Boolean
}
