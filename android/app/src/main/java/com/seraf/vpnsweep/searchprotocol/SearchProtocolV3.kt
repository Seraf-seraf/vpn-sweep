package com.seraf.vpnsweep.searchprotocol

internal const val SEARCH_SESSION_PROTOCOL_VERSION_V3: Int = 3
internal const val SEARCH_SESSION_PROTOCOL_VERSION_V4: Int = 4

internal enum class SearchStartV3 { ACCEPTED, BUSY, REJECTED, UNKNOWN }

internal enum class SearchResultReceiptV3 {
    COMMITTED_CLOSED,
    CANCELLED_CLOSED,
    FAILED_CLOSED,
    IGNORED_CLOSED,
    UNKNOWN,
}

internal enum class SearchResultFenceV3 { CONFIRMED, UNKNOWN }

internal data class SearchResultOfferV3(
    val sessionGeneration: Long,
    val phaseEpoch: Long,
    val sourceRevision: Long,
    val selectionRevision: Long,
    val resultSha256: String,
    val result: PrivateInputV1,
)

// Final terminals carry no result resource. Lease and result fence are distinct.
internal sealed interface SearchTerminalV3 {
    val sessionGeneration: Long
    val phaseEpoch: Long
    val resources: SearchResourceStateV2

    data class Complete(
        override val sessionGeneration: Long,
        override val phaseEpoch: Long,
        override val resources: SearchResourceStateV2,
        val resultFence: SearchResultFenceV3,
    ) : SearchTerminalV3

    data class Cancelled(
        override val sessionGeneration: Long,
        override val phaseEpoch: Long,
        override val resources: SearchResourceStateV2,
        val resultFence: SearchResultFenceV3,
    ) : SearchTerminalV3

    data class Paused(
        override val sessionGeneration: Long,
        override val phaseEpoch: Long,
        val reason: SearchPauseReasonV1,
        override val resources: SearchResourceStateV2,
    ) : SearchTerminalV3

    data class Stopped(
        override val sessionGeneration: Long,
        override val phaseEpoch: Long,
        val reason: SearchFailureV1,
        override val resources: SearchResourceStateV2,
        val resultFence: SearchResultFenceV3,
    ) : SearchTerminalV3
}

internal interface SearchSessionObserverV3 {
    fun onProgress(phaseEpoch: Long, progress: SearchProgressV1): Unit
    // The receiving consumer owns the result after handoff, including stale offers.
    fun onResult(offer: SearchResultOfferV3): SearchResultReceiptV3
    fun onTerminal(terminal: SearchTerminalV3): Unit
}

internal interface SearchSessionPortV3 {
    // ACCEPTED transfers profiles to B. BUSY/REJECTED retain them at C and
    // require that B neither opened nor retained them. UNKNOWN/exception leaves
    // ownership unconfirmed: neither side may blindly reuse or double-close.
    fun start(snapshot: SearchSnapshotV1, phaseEpoch: Long, observer: SearchSessionObserverV3): SearchStartV3
    fun pause(sessionGeneration: Long, phaseEpoch: Long): Unit
    fun resume(sessionGeneration: Long, phaseEpoch: Long): SearchStartV3
    fun cancel(sessionGeneration: Long, phaseEpoch: Long): Unit
}

// This same-process handoff accepts one exact final terminal before marker CLEAR.
internal interface SearchSessionPortV4 : SearchSessionPortV3 {
    fun acceptTerminal(terminal: SearchTerminalV3): Boolean
}
