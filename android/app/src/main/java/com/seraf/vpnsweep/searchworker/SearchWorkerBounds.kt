package com.seraf.vpnsweep.searchworker

/** Bounds shared by the producer, Binder port, and worker service. */
internal object SearchWorkerBounds {
    // Caller-proven per-round caps are enforced inclusively; there is no guessed default.
    const val FRAME_HEADER_BYTES = 16
    const val MAX_PROFILE_BYTES = 8 * 1024 * 1024
    const val MIN_REPLY_BYTES = 1

    fun supports(frameBytes: Int?, replyBytes: Int?): Boolean =
        frameBytes != null && frameBytes >= FRAME_HEADER_BYTES && replyBytes != null && replyBytes >= MIN_REPLY_BYTES

    fun frameFits(length: Long, provenFrameBound: Int): Boolean =
        provenFrameBound >= FRAME_HEADER_BYTES && length in FRAME_HEADER_BYTES.toLong()..provenFrameBound.toLong()

    fun replyFits(length: Long, provenReplyBound: Int): Boolean =
        provenReplyBound >= MIN_REPLY_BYTES && length in MIN_REPLY_BYTES.toLong()..provenReplyBound.toLong()
}
