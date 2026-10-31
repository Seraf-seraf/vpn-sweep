package com.seraf.vpnsweep.subscriptionruntime

import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionRecordV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionRejectV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionSnapshotV1
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val FIRST_ID = "00000000000000000000000000000001"
private const val SECOND_ID = "00000000000000000000000000000002"

class SubscriptionStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun currentRevisionControlsRecoveryAndSelectionSurvivesRestart(): Unit {
        val files = FakeSnapshotFiles()
        var current: ResultRevision? = null
        val root = File(temporary.root, "snapshots")
        val store = SubscriptionStorage(root, files) { current }
        val initial = store.commitSource(FakeSnapshot(), requestGeneration = 17)
        current = initial
        assertEquals(emptySet<String>(), store.recover(initial)?.selectedIds)
        assertEquals(17L, store.recover(initial)?.requestGeneration)
        val selected = store.commitSelection(setOf(FIRST_ID))
        assertEquals(initial.source, selected.source)
        assertNotEquals(initial, selected)
        assertEquals(emptySet<String>(), store.recover(initial)?.selectedIds)
        current = selected
        val recovered = SubscriptionStorage(root, files) { current }.recover(selected)
        assertEquals(setOf(FIRST_ID), recovered?.selectedIds)
        assertEquals(FIRST_ID, recovered?.snapshot?.records?.first()?.id)
        assertEquals(17L, recovered?.requestGeneration)
        recovered?.snapshot?.close()
    }

    @Test
    fun legacyMetadataRecoversWithFreshLocalGenerationWithoutRewriting(): Unit {
        val files = FakeSnapshotFiles()
        var current: ResultRevision? = null
        val root = File(temporary.root, "legacy")
        val store = SubscriptionStorage(root, files) { current }
        val revision = store.commitSource(FakeSnapshot(), requestGeneration = 23)
        current = revision
        val metadataFile = File(root, "${revision.source}-${revision.selection}/selection")
        val legacy = legacyMetadata(revision, setOf(FIRST_ID))
        metadataFile.writeBytes(legacy)

        val recovered = store.recover(revision)
        assertEquals(setOf(FIRST_ID), recovered?.selectedIds)
        assertEquals(1L, recovered?.requestGeneration)
        assertTrue(metadataFile.readBytes().contentEquals(legacy))
        recovered?.snapshot?.close()
    }

    @Test
    fun failedWriteOrDurabilityDoesNotReplaceLastGood(): Unit {
        for (failure in listOf("write", "sync1", "sync2", "sync3")) {
            val root = temporary.newFolder(failure)
            val files = FakeSnapshotFiles()
            var current: ResultRevision? = null
            val store = SubscriptionStorage(root, files) { current }
            val previous = store.commitSource(FakeSnapshot(), requestGeneration = 3)
            current = previous
            files.syncCount = 0
            files.failure = failure
            var failed = false
            try {
                store.commitSelection(setOf(FIRST_ID))
            } catch (_: IOException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(previous, current)
            files.failure = null
            assertEquals(emptySet<String>(), store.recover(previous)?.selectedIds)
        }
    }

    @Test
    fun unknownOrUnsupportedSelectionAndCorruptMetadataFailClosed(): Unit {
        val files = FakeSnapshotFiles()
        var current: ResultRevision? = null
        val root = File(temporary.root, "store")
        val store = SubscriptionStorage(root, files) { current }
        val previous = store.commitSource(FakeSnapshot(), requestGeneration = 9)
        current = previous
        for (ids in listOf(setOf(SECOND_ID), setOf("00000000000000000000000000000003"))) {
            var failed = false
            try {
                store.commitSelection(ids)
            } catch (_: IOException) {
                failed = true
            }
            assertTrue(failed)
        }
        val selected = store.commitSelection(setOf(FIRST_ID))
        val metadata = File(root, "${selected.source}-${selected.selection}/selection")
        val bytes = metadata.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        metadata.writeBytes(bytes)
        assertNull(store.recover(selected))
        assertEquals(emptySet<String>(), store.recover(previous)?.selectedIds)
    }

    private class FakeSnapshot : SubscriptionSnapshotV1 {
        override val records = listOf(
            SubscriptionRecordV1(FIRST_ID, "", "", true, null),
            SubscriptionRecordV1(SECOND_ID, "", "", false, SubscriptionRejectV1.UNSUPPORTED_RECORD),
        )
        override fun selectedProfiles(ids: Set<String>): PrivateInputV1? = null
        override fun close(): Unit = Unit
    }

    private fun legacyMetadata(revision: ResultRevision, selectedIds: Set<String>): ByteArray {
        val body = java.io.ByteArrayOutputStream().also { bytes ->
            java.io.DataOutputStream(bytes).use { output ->
                output.writeInt(1)
                output.writeUTF(revision.source)
                output.writeUTF(revision.selection)
                output.writeInt(selectedIds.size)
                for (id in selectedIds.sorted()) output.writeUTF(id)
            }
        }.toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(body)
        return body + digest
    }

    private class FakeSnapshotFiles : SubscriptionSnapshotFiles {
        var failure: String? = null
        var syncCount = 0
        override fun write(snapshot: SubscriptionSnapshotV1, file: File): Unit {
            file.writeText("synthetic")
            if (failure == "write") throw IOException("synthetic_failure")
        }
        override fun read(file: File): SubscriptionSnapshotV1 {
            if (file.readText() != "synthetic") throw IOException("synthetic_failure")
            return FakeSnapshot()
        }
        override fun syncDirectory(directory: File): Unit {
            syncCount++
            if (failure == "sync$syncCount") throw IOException("synthetic_failure")
        }
    }
}
