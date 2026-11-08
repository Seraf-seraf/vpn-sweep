package com.seraf.vpnsweep.vpn

internal class XrayLogWindow(private val startedAtMillis: Long) {
    private val lastLogMillis = mutableMapOf<Long, Long>()

    fun shouldReport(code: Long, nowMillis: Long): Boolean {
        val previous = lastLogMillis[code]
        if (previous != null && nowMillis - previous < XRAY_LOG_REPEAT_INTERVAL_MS) return false
        lastLogMillis[code] = nowMillis
        return true
    }

    fun starting(nowMillis: Long): Boolean = nowMillis - startedAtMillis < VPN_STARTING_WINDOW_MS

    private companion object {
        const val VPN_STARTING_WINDOW_MS = 15_000L
        const val XRAY_LOG_REPEAT_INTERVAL_MS = 15_000L
    }
}
