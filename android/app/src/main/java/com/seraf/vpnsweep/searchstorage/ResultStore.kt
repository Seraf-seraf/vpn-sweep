package com.seraf.vpnsweep.searchstorage

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

internal data class ResultRevision(val source: String, val selection: String)
internal class PublicationToken internal constructor(
    val generation: Long,
    val revision: ResultRevision,
)
internal data class ResultManifest(val generation: String, val revision: ResultRevision, val sha256: String)
internal class StoredResult(val revision: ResultRevision, val sha256: String, val input: InputStream)

internal interface ResultStorage {
    fun prepare(revision: ResultRevision, sha256: String, input: InputStream): String
    fun readGeneration(generation: String): StoredResult?
    fun readManifest(): ResultManifest?
    fun publish(manifest: ResultManifest): Unit
    fun discard(generation: String): Unit
}

internal enum class Publication { SUCCESS, CANCELLED, INVALID, FAILED, INDETERMINATE }

internal class DurabilityUnknownException(cause: IOException) : IOException(cause)
internal class PreparationCleanupUnknownException(cause: IOException) : IOException(cause)

internal class ResultStore(private val storage: ResultStorage) {
    private var active: PublicationToken? = null
    private var nextGeneration = 0L
    private var uncertain = false

    @Synchronized
    fun begin(revision: ResultRevision): PublicationToken? {
        if (uncertain || active != null || revision.source.isEmpty() || revision.selection.isEmpty()) return null
        return PublicationToken(++nextGeneration, revision).also { active = it }
    }

    @Synchronized
    fun cancel(token: PublicationToken): Boolean {
        if (active !== token || uncertain) return false
        active = null
        return true
    }

    fun publish(token: PublicationToken, input: InputStream, expectedSha256: String): Publication {
        synchronized(this) {
            if (active !== token) return Publication.CANCELLED
        }
        val generation = try {
            storage.prepare(token.revision, expectedSha256, input)
        } catch (failure: IOException) {
            return finishBeforeCommit(token, Publication.FAILED, failure is PreparationCleanupUnknownException)
        }
        val manifest = ResultManifest(generation, token.revision, expectedSha256)
        val validation = try {
            if (valid(manifest)) null else Publication.INVALID
        } catch (_: IOException) {
            Publication.FAILED
        }
        if (validation != null) return discardAndFinish(token, generation, validation)
        val outcome = synchronized(this) {
            if (active !== token) return@synchronized Publication.CANCELLED
            val previous = try {
                storage.readManifest()
            } catch (_: IOException) {
                return@synchronized Publication.FAILED
            }
            try {
                storage.publish(manifest)
                active = null
                Publication.SUCCESS
            } catch (failure: IOException) {
                if (failure is DurabilityUnknownException) {
                    active = null
                    uncertain = true
                    return@synchronized Publication.INDETERMINATE
                }
                val result = try {
                    val actual = storage.readManifest()
                    when {
                        actual == manifest && valid(manifest) -> Publication.SUCCESS
                        actual == previous && (actual == null || valid(actual)) -> Publication.FAILED
                        else -> Publication.INDETERMINATE
                    }
                } catch (_: IOException) {
                    Publication.INDETERMINATE
                }
                if (result != Publication.FAILED) active = null
                uncertain = result == Publication.INDETERMINATE
                result
            }
        }
        return when (outcome) {
            Publication.CANCELLED, Publication.FAILED -> discardAndFinish(token, generation, outcome)
            else -> outcome
        }
    }

    private fun discardAndFinish(token: PublicationToken, generation: String, outcome: Publication): Publication {
        val cleanupFailed = try {
            storage.discard(generation)
            false
        } catch (_: IOException) {
            true
        }
        return finishBeforeCommit(token, outcome, cleanupFailed)
    }

    @Synchronized
    private fun finishBeforeCommit(token: PublicationToken, outcome: Publication, cleanupUnknown: Boolean): Publication {
        if (cleanupUnknown || uncertain) {
            active = null
            uncertain = true
            return Publication.INDETERMINATE
        }
        if (active !== token) return Publication.CANCELLED
        active = null
        return outcome
    }

    @Synchronized
    fun resolve(revision: ResultRevision): ResultManifest? {
        if (uncertain) return null
        return try {
            val manifest = storage.readManifest() ?: return null
            if (manifest.revision == revision && valid(manifest)) manifest else null
        } catch (_: IOException) {
            null
        }
    }

    private fun valid(manifest: ResultManifest): Boolean {
        val result = storage.readGeneration(manifest.generation) ?: return false
        return result.input.use {
            result.revision == manifest.revision && result.sha256 == manifest.sha256 &&
                digest(it) == manifest.sha256
        }
    }
}

internal fun digest(input: InputStream): String {
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) hash.update(buffer, 0, count)
    }
    return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
