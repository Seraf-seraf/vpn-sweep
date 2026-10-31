package com.seraf.vpnsweep.subscriptionprotocol

import com.seraf.vpnsweep.searchprotocol.PrivateInputV1

internal const val SUBSCRIPTION_PROTOCOL_VERSION_V1: Int = 1

internal enum class SubscriptionStartV1 { ACCEPTED, BUSY, REJECTED }
// NO_SUPPORTED is not a successful import; its records may still be displayed.
internal enum class SubscriptionBatchStatusV1 { HAS_SUPPORTED, NO_SUPPORTED }
internal enum class SubscriptionRejectV1 { UNSUPPORTED_RECORD }
internal class SubscriptionProfilesTooLargeException : Exception()
internal enum class SubscriptionFailureV1 {
    INVALID_URL,
    TIMEOUT,
    HTTP,
    NETWORK,
    TLS,
    MALFORMED,
    INVALID_BASE64,
    LIMIT_EXCEEDED,
    CANCELLED,
    INTERNAL,
}

// The strings are for local display only and must not be logged. IDs are opaque
// and belong to exactly one immutable snapshot.
internal class SubscriptionRecordV1(
    val id: String,
    val title: String,
    val subtitle: String,
    val supported: Boolean,
    val reason: SubscriptionRejectV1?,
)

// This is a logical port, not a Binder parcel schema. Across processes, bounded
// private streams carry the batch and profiles; record count remains at most 10,000.
internal interface SubscriptionSnapshotV1 : AutoCloseable {
    val records: List<SubscriptionRecordV1>

    // Reject empty, unknown or unsupported IDs. The returned private stream is
    // owned by the caller and is transferred via a bounded FD, not a Binder parcel.
    fun selectedProfiles(ids: Set<String>): PrivateInputV1?
}

internal interface SubscriptionObserverV1 {
    // Ownership of every delivered snapshot transfers to the observer, even for
    // a late callback after cancel; the observer must close discarded snapshots.
    fun onParsed(generation: Long, status: SubscriptionBatchStatusV1, snapshot: SubscriptionSnapshotV1): Unit
    fun onFailure(generation: Long, failure: SubscriptionFailureV1): Unit
}

internal interface SubscriptionPortV1 {
    // The input is sensitive. It is an operation argument, not UI state or log data.
    // A rejected start must not dispatch a callback or transfer snapshot ownership.
    // An accepted start may deliver a callback before start returns.
    fun start(generation: Long, input: String, observer: SubscriptionObserverV1): SubscriptionStartV1
    fun cancel(generation: Long): Unit
}
