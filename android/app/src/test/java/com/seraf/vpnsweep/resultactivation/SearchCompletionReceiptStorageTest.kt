package com.seraf.vpnsweep.resultactivation

import com.seraf.vpnsweep.SearchLeaseReceiptV1
import com.seraf.vpnsweep.searchstorage.ResultManifest
import com.seraf.vpnsweep.searchstorage.ResultRevision
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class MemorySearchCompletionReceiptStorage : SearchCompletionReceiptStorage {
    var receipt: SearchCompletionReceiptV1? = null
    var failRead = false
    var failWrite = false
    var failCommit = false
    private var prepared: SearchCompletionReceiptV1? = null
    private var pending = false

    override fun read(): SearchCompletionReceiptV1? {
        if (failRead) throw IOException("synthetic")
        return receipt
    }

    override fun readPrepared(): SearchCompletionReceiptV1? {
        if (failRead) throw IOException("synthetic")
        return if (pending) prepared else null
    }

    override fun hasPending(): Boolean = pending

    override fun prepare(receipt: SearchCompletionReceiptV1): Boolean {
        if (failWrite) return false
        prepared = receipt
        pending = true
        return true
    }

    override fun commit(): Boolean {
        if (failCommit || !pending) return false
        receipt = prepared
        prepared = null
        pending = false
        return true
    }
}

class SearchCompletionReceiptStorageTest {
    @Test
    fun versionedRecordRoundTripsExactReceiptAndManifest(): Unit = withTempDirectory { root ->
        val storage = PrivateSearchCompletionReceiptStorage(root, directorySync = {})
        val expected = receipt()

        assertTrue(storage.write(expected))
        assertEquals(expected, PrivateSearchCompletionReceiptStorage(root, directorySync = {}).read())
    }

    @Test
    fun noPriorReceiptRemainsUnavailableAcrossRestartUntilPendingCommit(): Unit = withTempDirectory { root ->
        val candidate = receipt()
        val storage = PrivateSearchCompletionReceiptStorage(root, directorySync = {})

        assertTrue(storage.prepare(candidate))
        assertTrue(storage.hasPending())
        assertNull(storage.read())
        assertEquals(candidate, storage.readPrepared())

        val restarted = PrivateSearchCompletionReceiptStorage(root, directorySync = {})
        assertTrue(restarted.hasPending())
        assertNull(restarted.read())
        assertEquals(candidate, restarted.readPrepared())
        assertTrue(restarted.commit())

        assertFalse(restarted.hasPending())
        assertNull(restarted.readPrepared())
        assertEquals(candidate, restarted.read())
    }

    @Test
    fun priorReceiptIsPreservedWhilePendingAndReplacedOnlyOnCommit(): Unit = withTempDirectory { root ->
        val storage = PrivateSearchCompletionReceiptStorage(root, directorySync = {})
        val previous = receipt()
        val candidate = receipt(requestGeneration = 12L, sessionGeneration = 22L, leaseIdentity = UUID.randomUUID().toString())
        assertTrue(storage.write(previous))

        assertTrue(storage.prepare(candidate))
        assertTrue(storage.hasPending())
        assertEquals(previous, storage.read())
        assertEquals(candidate, storage.readPrepared())

        val restarted = PrivateSearchCompletionReceiptStorage(root, directorySync = {})
        assertEquals(previous, restarted.read())
        assertEquals(candidate, restarted.readPrepared())
        assertTrue(restarted.commit())
        assertEquals(candidate, restarted.read())
    }

    @Test
    fun failedCommitKeepsPendingAndLastGoodReceipt(): Unit = withTempDirectory { root ->
        val storage = PrivateSearchCompletionReceiptStorage(root, directorySync = {})
        val previous = receipt()
        val candidate = receipt(requestGeneration = 12L, sessionGeneration = 22L, leaseIdentity = UUID.randomUUID().toString())
        assertTrue(storage.write(previous))
        assertTrue(storage.prepare(candidate))
        File(root, "completion").writeBytes(byteArrayOf(1, 2, 3))

        assertFalse(storage.commit())
        assertTrue(storage.hasPending())
        assertEquals(previous, storage.read())
    }

    @Test
    fun failedCommitDirectorySyncRemainsUnavailableAndCanRetry(): Unit = withTempDirectory { root ->
        var failSync = false
        val storage = PrivateSearchCompletionReceiptStorage(root, directorySync = {
            if (failSync) throw IOException("synthetic")
        })
        val previous = receipt()
        val candidate = receipt(requestGeneration = 12L, sessionGeneration = 22L, leaseIdentity = UUID.randomUUID().toString())
        assertTrue(storage.write(previous))
        assertTrue(storage.prepare(candidate))

        failSync = true
        assertFalse(storage.commit())
        assertTrue(storage.hasPending())
        assertNull(storage.read())

        failSync = false
        assertTrue(storage.commit())
        assertFalse(storage.hasPending())
        assertEquals(candidate, storage.read())
    }

    @Test
    fun missingCorruptTruncatedIncompleteAndTrailingRecordsAreUnavailable(): Unit = withTempDirectory { root ->
        val storage = PrivateSearchCompletionReceiptStorage(root, directorySync = {})
        assertNull(storage.read())
        assertTrue(storage.write(receipt()))
        val file = File(root, "completion")
        val valid = file.readBytes()

        file.writeBytes(valid.copyOf(7))
        assertNull(storage.read())

        val incompleteBody = valid.copyOfRange(0, valid.size - 32).also { it[8] = 0 }
        file.writeBytes(withChecksum(incompleteBody))
        assertNull(storage.read())

        val trailingBody = valid.copyOfRange(0, valid.size - 32) + byteArrayOf(1)
        file.writeBytes(withChecksum(trailingBody))
        assertNull(storage.read())

        val wrongVersionBody = valid.copyOfRange(0, valid.size - 32).also { it[4] = (it[4].toInt() + 1).toByte() }
        file.writeBytes(withChecksum(wrongVersionBody))
        assertNull(storage.read())

        file.writeBytes(valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        assertNull(storage.read())

        file.writeBytes(valid.copyOf().also { it[43] = '1'.code.toByte() })
        assertNull(storage.read())
    }

    @Test
    fun recordLengthAcceptsSchemaBoundsAndRejectsOutsideThem(): Unit = withTempDirectory { root ->
        val storage = PrivateSearchCompletionReceiptStorage(root, directorySync = {})
        val file = File(root, "completion")

        assertTrue(storage.write(receipt()))
        val minimum = file.readBytes()
        assertEquals(221L, file.length())
        assertEquals(receipt(), storage.read())

        file.writeBytes(minimum.copyOf(minimum.size - 1))
        assertNull(storage.read())
    }

    @Test
    fun largerValidRecordAndMaximumLengthAreAccepted(): Unit = withTempDirectory { root ->
        val storage = PrivateSearchCompletionReceiptStorage(root, directorySync = {})
        val file = File(root, "completion")
        val aboveMinimum = receipt(sourceRevision = 10L, selectionRevision = 2L)
        assertTrue(storage.write(aboveMinimum))
        assertEquals(222L, file.length())
        assertEquals(aboveMinimum, storage.read())
    }

    @Test
    fun maximumLengthIsAcceptedAndAnyLargerFileIsRejected(): Unit = withTempDirectory { root ->
        val storage = PrivateSearchCompletionReceiptStorage(root, directorySync = {})
        val file = File(root, "completion")
        val maximum = receipt(
            requestGeneration = Long.MAX_VALUE,
            sessionGeneration = Long.MAX_VALUE,
            sourceRevision = Long.MAX_VALUE,
            selectionRevision = Long.MAX_VALUE,
        )
        assertTrue(storage.write(maximum))
        val maximumBytes = file.readBytes()
        assertEquals(257L, file.length())
        assertEquals(maximum, storage.read())

        file.writeBytes(maximumBytes + byteArrayOf(0))
        assertNull(storage.read())
    }

    @Test
    fun failedAtomicReplaceKeepsPreviousCleanReceipt(): Unit = withTempDirectory { root ->
        var failMove = false
        val storage = PrivateSearchCompletionReceiptStorage(
            root,
            atomicMove = { source, target ->
                if (failMove && target.fileName.toString() == "completion") throw IOException("synthetic")
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            },
            directorySync = {},
        )
        val previous = receipt()
        val attempted = receipt(requestGeneration = 12L, sessionGeneration = 22L, leaseIdentity = UUID.randomUUID().toString())
        assertTrue(storage.write(previous))

        failMove = true
        assertFalse(storage.write(attempted))
        assertEquals(previous, PrivateSearchCompletionReceiptStorage(root, directorySync = {}).read())
    }

    @Test
    fun failedDirectorySyncAfterReplaceKeepsPreviousCleanReceipt(): Unit = withTempDirectory { root ->
        var syncCalls = 0
        val storage = PrivateSearchCompletionReceiptStorage(root, directorySync = {
            syncCalls++
            if (syncCalls == 6) throw IOException("synthetic")
        })
        val previous = receipt()
        val attempted = receipt(requestGeneration = 12L, sessionGeneration = 22L, leaseIdentity = UUID.randomUUID().toString())
        assertTrue(storage.write(previous))

        assertFalse(storage.write(attempted))
        assertEquals(previous, PrivateSearchCompletionReceiptStorage(root, directorySync = {}).read())
    }

    @Test
    fun failedFirstWriteCanRecoverOnTheNextCleanComplete(): Unit = withTempDirectory { root ->
        var syncCalls = 0
        val failingStorage = PrivateSearchCompletionReceiptStorage(root, directorySync = {
            syncCalls++
            if (syncCalls == 2) throw IOException("synthetic")
        })
        val interrupted = receipt()
        val recovered = receipt(requestGeneration = 12L, sessionGeneration = 22L, leaseIdentity = UUID.randomUUID().toString())

        assertFalse(failingStorage.write(interrupted))
        assertNull(PrivateSearchCompletionReceiptStorage(root, directorySync = {}).read())
        assertFalse(File(root, "completion.previous").exists())
        assertTrue(File(root, "completion.pending").exists())

        val retry = PrivateSearchCompletionReceiptStorage(root, directorySync = {})
        assertTrue(retry.write(recovered))
        assertEquals(recovered, PrivateSearchCompletionReceiptStorage(root, directorySync = {}).read())
    }

    @Test
    fun retryFailurePreservesValidFirstWriteCandidateAsLastGood(): Unit = withTempDirectory { root ->
        var firstSyncCalls = 0
        val firstWrite = PrivateSearchCompletionReceiptStorage(root, directorySync = {
            firstSyncCalls++
            if (firstSyncCalls == 2) throw IOException("synthetic")
        })
        val previousCandidate = receipt()
        assertFalse(firstWrite.write(previousCandidate))
        assertTrue(File(root, "completion.pending").exists())
        assertNull(PrivateSearchCompletionReceiptStorage(root, directorySync = {}).read())

        var retrySyncCalls = 0
        val retry = PrivateSearchCompletionReceiptStorage(root, directorySync = {
            retrySyncCalls++
            if (retrySyncCalls == 3) throw IOException("synthetic")
        })
        val attempted = receipt(requestGeneration = 12L, sessionGeneration = 22L, leaseIdentity = UUID.randomUUID().toString())
        assertFalse(retry.write(attempted))
        assertTrue(File(root, "completion.previous").exists())
        assertEquals(previousCandidate, PrivateSearchCompletionReceiptStorage(root, directorySync = {}).read())
    }

    @Test
    fun invalidSearchOrManifestCannotBeWritten(): Unit = withTempDirectory { root ->
        val storage = PrivateSearchCompletionReceiptStorage(root, directorySync = {})
        val valid = receipt()
        val invalidSearch = valid.copy(search = valid.search.copy(sessionGeneration = 0L))
        val invalidManifest = valid.copy(result = valid.result.copy(sha256 = "bad"))

        assertFalse(storage.write(invalidSearch))
        assertFalse(storage.write(invalidManifest))
        assertNull(storage.read())
    }

    private fun receipt(
        requestGeneration: Long = 11L,
        sessionGeneration: Long = 21L,
        sourceRevision: Long = 1L,
        selectionRevision: Long = 2L,
        leaseIdentity: String = "00000000-0000-0000-0000-000000000021",
    ): SearchCompletionReceiptV1 {
        val result = ResultManifest(
            generation = "00000000-0000-0000-0000-000000000031",
            revision = ResultRevision(sourceRevision.toString(), selectionRevision.toString()),
            sha256 = "0".repeat(64),
        )
        return SearchCompletionReceiptV1.cleanComplete(
            SearchLeaseReceiptV1(requestGeneration, sessionGeneration, sourceRevision, selectionRevision, leaseIdentity),
            result,
        )
    }

    private fun withChecksum(body: ByteArray): ByteArray =
        body + MessageDigest.getInstance("SHA-256").digest(body)

    private fun withTempDirectory(action: (File) -> Unit): Unit {
        val root = Files.createTempDirectory("search-completion-receipt").toFile()
        try {
            action(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
