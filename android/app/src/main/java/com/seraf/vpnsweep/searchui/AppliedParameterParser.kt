package com.seraf.vpnsweep.searchui

import org.json.JSONArray
import org.json.JSONObject

// Only the fields rendered by the parameter panel leave this boundary.
internal fun parseAppliedParameters(bytes: ByteArray): AppliedParameters? = try {
    val root = JSONObject(bytes.toString(Charsets.UTF_8))
    val outbounds = root.getJSONArray("outbounds")
    outbounds.singleTagged("candidate") ?: error("candidate_missing")
    // Older results did not preserve which values the search selected.
    val selection = root.optionalObject("vpnSweepSelection")
    val allowed = setOf("fragment", "packets", "length", "interval", "maxSplit", "mux", "tcpConcurrency", "xudpConcurrency", "quic", "fingerprint", "noise", "noises")
    selection?.keys()?.forEach { check(it in allowed) }
    val noises = selection?.optionalArray("noises")
    AppliedParameters(
        fragmentEnabled = selection?.optionalBoolean("fragment"),
        fragmentPackets = selection?.optionalString("packets")?.let { value ->
            FragmentPackets.entries.singleOrNull { it.wireValue == value } ?: error("invalid_packets")
        },
        fragmentLengthBytes = selection?.optionalRange("length"),
        fragmentIntervalMillis = selection?.optionalRange("interval"),
        fragmentMaxSplit = selection?.optionalRange("maxSplit"),
        muxEnabled = selection?.optionalBoolean("mux"),
        tcpConcurrency = selection?.optionalInt("tcpConcurrency"),
        xudpConcurrency = selection?.optionalInt("xudpConcurrency"),
        udp443 = selection?.optionalString("quic")?.let { value ->
            Udp443.entries.singleOrNull { it.wireValue == value } ?: error("invalid_udp443")
        },
        fingerprint = selection?.optionalString("fingerprint")?.takeIf { it.isNotEmpty() }?.let { value ->
            Fingerprint.entries.singleOrNull { it.wireValue == value } ?: error("invalid_fingerprint")
        },
        udpNoiseEnabled = selection?.optionalBoolean("noise"),
        udpNoiseCount = noises?.length(),
    )
} catch (_: Exception) { null }

private fun JSONArray.singleTagged(tag: String): JSONObject? {
    var match: JSONObject? = null
    for (index in 0 until length()) {
        val item = getJSONObject(index)
        if (item.optionalString("tag") == tag) {
            check(match == null)
            match = item
        }
    }
    return match
}

private fun JSONObject.optionalValue(name: String): Any? = if (has(name)) {
    get(name).also { check(it != JSONObject.NULL) }
} else null
private fun JSONObject.optionalObject(name: String): JSONObject? = optionalValue(name)?.let { it as JSONObject }
private fun JSONObject.optionalArray(name: String): JSONArray? = optionalValue(name)?.let { it as JSONArray }
private fun JSONObject.optionalString(name: String): String? = optionalValue(name)?.let { it as String }
private fun JSONObject.optionalBoolean(name: String): Boolean? = optionalValue(name)?.let { it as Boolean }
private fun JSONObject.optionalInt(name: String): Int? = optionalValue(name)?.let {
    val number = it as Number
    val value = number.toLong()
    check(number.toDouble() == value.toDouble() && value in Int.MIN_VALUE..Int.MAX_VALUE)
    value.toInt()
}

private fun JSONObject.optionalRange(name: String): NumericRange? {
    val value = optionalValue(name) ?: return null
    val parts = when (value) {
        is String -> value.split('-')
        is Int, is Long -> listOf(value.toString())
        else -> error("invalid_range")
    }
    check(parts.size in 1..2)
    val numbers = parts.map { part ->
        check(part.isNotEmpty() && part.all { it in '0'..'9' })
        part.toInt()
    }
    if (numbers.size == 1) return NumericRange.Single(numbers[0])
    check(numbers[0] <= numbers[1])
    return NumericRange.Pair(numbers[0], numbers[1])
}
