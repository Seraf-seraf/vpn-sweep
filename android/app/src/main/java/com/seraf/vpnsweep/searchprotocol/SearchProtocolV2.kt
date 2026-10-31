package com.seraf.vpnsweep.searchprotocol

import java.io.InputStream

internal const val SEARCH_PROTOCOL_VERSION_V1: Int = 1
internal const val SEARCH_SESSION_PROTOCOL_VERSION_V2: Int = 2
internal const val SEARCH_WORKER_PROTOCOL_VERSION_V2: Int = 2

// Profile and result bytes stay in private streams. Android IPC transfers their
// file descriptors, not these objects or their contents as Binder parcels.
internal interface PrivateInputV1 : AutoCloseable {
    fun open(): InputStream
}

internal class SearchSnapshotV1(
    val sessionGeneration: Long,
    val sourceRevision: Long,
    val selectionRevision: Long,
    val profiles: PrivateInputV1,
)

internal enum class SearchStartV1 { ACCEPTED, BUSY, REJECTED }

internal enum class SearchProgressKindV1 {
    ATTEMPT_STARTED,
    ATTEMPT_FINISHED,
    CANDIDATE_SKIPPED,
    CORE_STARTING,
    CORE_READY,
    CORE_STOPPING,
    CORE_STOPPED,
    PROBE_STARTED,
    PROBE_FINISHED,
    MEDIA_STARTED,
    BUDGET_REACHED,
    SAVED_RESULT_SKIPPED,
}

internal enum class SearchProgressOutcomeV1 { NONE, RUNNING, PASS, FAILED, INCONCLUSIVE, BLOCKED, SKIPPED, BUDGET }

internal data class SearchProgressV1(
    val sessionGeneration: Long,
    val kind: SearchProgressKindV1,
    val outcome: SearchProgressOutcomeV1,
    val attempt: ULong,
    val profile: Int,
    val round: Int,
    val probe: Int,
    val bytes: Long,
    val durationMillis: Long,
)

internal enum class SearchFailureV1 {
    BUSY,
    INVALID_INPUT,
    CAPABILITY_BLOCKED,
    EXHAUSTED,
    OWNER_LOST,
    WORKER_QUARANTINED,
    INTERRUPTED,
    INTERNAL_ERROR,
    PROFILE_TOO_LARGE,
}

internal enum class SearchResourceStateV2 {
    // Cleanup and worker death are confirmed and all lease FDs are closed.
    RELEASED,
    // Cleanup and worker death are confirmed; the live coordinator holds the lease.
    PAUSED_LEASE_HELD,
    // An outcome is unknown, so no subsequent owner may start.
    QUARANTINED,
}

internal sealed interface SearchTerminalV2 {
    val sessionGeneration: Long
    val resources: SearchResourceStateV2

    // Success is delivered only after COMPLETE, digest calculation and cleanup.
    // Publication and cancellation are serialized by the session owner.
    class Complete(
        override val sessionGeneration: Long,
        val sourceRevision: Long,
        val selectionRevision: Long,
        val resultSha256: String,
        val result: PrivateInputV1,
    ) : SearchTerminalV2 {
        override val resources: SearchResourceStateV2 = SearchResourceStateV2.RELEASED
    }

    data class Cancelled(
        override val sessionGeneration: Long,
        override val resources: SearchResourceStateV2,
    ) : SearchTerminalV2 {
        init { require(resources != SearchResourceStateV2.PAUSED_LEASE_HELD) }
    }

    data class Paused(
        override val sessionGeneration: Long,
        val reason: SearchPauseReasonV1,
        override val resources: SearchResourceStateV2,
    ) : SearchTerminalV2 {
        init { require(resources != SearchResourceStateV2.QUARANTINED) }
    }

    data class Stopped(
        override val sessionGeneration: Long,
        val reason: SearchFailureV1,
        override val resources: SearchResourceStateV2,
    ) : SearchTerminalV2 {
        init { require(resources != SearchResourceStateV2.PAUSED_LEASE_HELD) }
    }
}

internal enum class SearchPauseReasonV1 { USER_REQUEST, BUDGET }

internal interface SearchSessionObserverV2 {
    fun onProgress(progress: SearchProgressV1): Unit
    fun onTerminal(terminal: SearchTerminalV2): Unit
}

internal interface SearchSessionPortV2 {
    fun start(snapshot: SearchSnapshotV1, observer: SearchSessionObserverV2): SearchStartV1
    fun pause(sessionGeneration: Long): Unit
    // Resume only the same in-memory session after its Paused event and cleanup.
    fun resume(sessionGeneration: Long): SearchStartV1
    fun cancel(sessionGeneration: Long): Unit
}

internal data class WorkerIdentityV1(
    val sessionGeneration: Long,
    val attempt: ULong,
    val round: Int,
    val workerGeneration: Long,
)

internal data class SearchNetworkV1(val identity: Long, val version: Long)

// The platform implementation transfers a duplicate of the locked open file
// description. It must keep the duplicate open until the worker process dies.
internal interface SearchLeaseDuplicateV1 : AutoCloseable

internal enum class WorkerOperationV1 { CHECK, UDP, MEDIA }
internal enum class WorkerCloseV1 { CLEAN_AND_DEAD, QUARANTINED }

internal enum class WorkerCandidateFailureV2 {
    CONFIG_REJECTED,
    CORE_START_FAILED,
    CORE_START_TIMEOUT,
    CORE_EXITED_BEFORE_READY,
}

internal enum class WorkerInterruptedV2 { CANCELLED, DEADLINE }

internal enum class WorkerFatalV2 {
    BUSY,
    OWNER_LOST,
    CAPABILITY_UNAVAILABLE,
    UNKNOWN_DELIVERY,
    WORKER_DIED,
    CLEANUP_INCOMPLETE,
}

internal sealed interface WorkerOpenV2 {
    data object Ready : WorkerOpenV2
    // Candidate failure and interruption require confirmed cleanup and death.
    data class CandidateFailure(val reason: WorkerCandidateFailureV2, val retryable: Boolean) : WorkerOpenV2
    data class Interrupted(val reason: WorkerInterruptedV2) : WorkerOpenV2
    data class Fatal(val reason: WorkerFatalV2) : WorkerOpenV2
}

internal sealed interface WorkerReplyV1 {
    class Complete(val result: PrivateInputV1) : WorkerReplyV1
    // Known call interruption after reply and transferred FD disposition;
    // worker cleanup and process death are confirmed separately by close.
    data class Interrupted(val reason: WorkerInterruptedV2) : WorkerReplyV1
    data object Rejected : WorkerReplyV1
    data object Quarantined : WorkerReplyV1
}

internal interface SearchWorkerPortV2 {
    // Admission includes the binding, both Binder death registrations and the
    // lease transfer; READY additionally requires authenticated SOCKS readiness.
    fun open(
        identity: WorkerIdentityV1,
        network: SearchNetworkV1,
        lease: SearchLeaseDuplicateV1,
        configuration: PrivateInputV1,
        startupMillis: Long,
        cleanupMillis: Long,
    ): WorkerOpenV2

    fun execute(identity: WorkerIdentityV1, operation: WorkerOperationV1, input: PrivateInputV1): WorkerReplyV1

    fun cancel(identity: WorkerIdentityV1): Unit

    // A cleanup acknowledgement alone cannot produce CLEAN_AND_DEAD.
    fun close(identity: WorkerIdentityV1): WorkerCloseV1
}
