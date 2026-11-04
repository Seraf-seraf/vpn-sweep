package com.seraf.vpnsweep.searchui

import org.json.JSONObject

// The native projection contains only these non-secret, rendered fields.
internal fun parseAppliedParameters(bytes: ByteArray): AppliedParameters? = try {
    val root = JSONObject(bytes.toString(Charsets.UTF_8))
    check(root.getInt("version") == 1)
    val values = root.getJSONObject("values")
    val allowed = setOf("fragment", "packets", "length", "interval", "maxSplit", "mux", "tcpConcurrency", "xudpConcurrency", "quic", "fingerprint", "noise", "noises")
    check(values.keys().asSequence().toSet() == allowed)
    val fields = allowed.associateWith { key ->
        val entry = values.getJSONObject(key)
        val value = entry.get("value") as String
        val state = entry.get("state") as String
        check(state in setOf("value", "default", "not_applicable"))
        check(value.length <= 128 && value.none { it.isISOControl() })
        EffectiveParameter(value, state)
    }
    AppliedParameters(effectiveFields = fields)
} catch (_: Exception) { null }
