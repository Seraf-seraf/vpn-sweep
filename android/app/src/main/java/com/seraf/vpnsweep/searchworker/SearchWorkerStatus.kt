package com.seraf.vpnsweep.searchworker

// Mirrors android/core/search_session.go values omitted from the generated gomobile API.
internal object SearchWorkerStatus {
    const val READY = 0L
    const val BUSY = 1L
    const val OWNER_LOST = 2L
    const val DELIVERY_UNKNOWN = 3L
    const val DIED = 4L
    const val CLEANUP_INCOMPLETE = 5L
    const val UNAVAILABLE = 6L
    const val CAPABILITY_UNAVAILABLE = 7L
    const val CANDIDATE_CONFIG_REJECTED = 8L
    const val CANDIDATE_START_FAILED = 9L
    const val CANDIDATE_START_TIMEOUT = 10L
    const val CANDIDATE_EXITED_BEFORE_READY = 11L
    const val CANCELLED = 12L
    const val DEADLINE = 13L
    const val RETRYABLE_FLAG = 1L shl 8
}
