package com.seraf.vpnsweep.subscriptionruntime

import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionSnapshotV1
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

private const val MAX_RECORDS = 10_000
private const val MAX_METADATA_BYTES = 400_000L
private const val MAX_SNAPSHOT_BYTES = 44L + 8L * 1024L * 1024L + MAX_RECORDS * 49L
private const val METADATA_VERSION = 1
private const val DIGEST_BYTES = 32
private val ID_PATTERN = Regex("[0-9a-f]{32}")

internal class RecoveredSubscription(
    val snapshot: SubscriptionSnapshotV1,
    val selectedIds: Set<String>,
    val revision: ResultRevision,
)

internal interface SubscriptionSnapshotFiles {
    fun write(snapshot: SubscriptionSnapshotV1, file: File): Unit
    fun read(file: File): SubscriptionSnapshotV1
    fun syncDirectory(directory: File): Unit
}

internal class SubscriptionStorage(
    private val root: File,
    private val files: SubscriptionSnapshotFiles,
    private val currentRevision: () -> ResultRevision?,
) {
    private val random = SecureRandom()

    fun commitSource(snapshot: SubscriptionSnapshotV1): ResultRevision {
        check(snapshot.records.any { it.supported }) { "subscription_snapshot" }
        return store(snapshot, emptySet(), null)
    }

    fun commitSelection(selectedIds: Set<String>): ResultRevision {
        val previous = currentRevision() ?: throw IOException("subscription_revision")
        val recovered = recover(previous) ?: throw IOException("subscription_revision")
        try {
            if (!validSelection(recovered.snapshot, selectedIds) || selectedIds.isEmpty()) {
                throw IOException("subscription_selection")
            }
            return store(recovered.snapshot, selectedIds, previous.source)
        } finally {
            recovered.snapshot.close()
        }
    }

    fun recover(revision: ResultRevision): RecoveredSubscription? {
        if (!validRevision(revision)) return null
        var snapshot: SubscriptionSnapshotV1? = null
        try {
            val directory = File(root, "${revision.source}-${revision.selection}")
            val metadata = readMetadata(File(directory, "selection"), revision) ?: return null
            val payload = File(directory, "snapshot")
            if (!payload.isFile || payload.length() !in 1..MAX_SNAPSHOT_BYTES) return null
            snapshot = files.read(payload)
            if (!snapshot.records.any { it.supported } || !validSelection(snapshot, metadata)) {
                snapshot.close()
                return null
            }
            return RecoveredSubscription(snapshot, metadata, revision)
        } catch (_: Exception) {
            try {
                snapshot?.close()
            } catch (_: Exception) {
            }
            return null
        }
    }

    private fun store(snapshot: SubscriptionSnapshotV1, selectedIds: Set<String>, source: String?): ResultRevision {
        if (!validSelection(snapshot, selectedIds)) throw IOException("subscription_selection")
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) throw IOException("subscription_storage")
        files.syncDirectory(root.parentFile ?: throw IOException("subscription_storage"))
        var revision: ResultRevision
        var directory: File
        do {
            revision = ResultRevision(source ?: newRevision(), newRevision())
            directory = File(root, "${revision.source}-${revision.selection}")
        } while (!directory.mkdir() && directory.exists())
        if (!directory.isDirectory) throw IOException("subscription_storage")
        try {
            val payload = File(directory, "snapshot")
            files.write(snapshot, payload)
            if (!payload.isFile || payload.length() !in 1..MAX_SNAPSHOT_BYTES) throw IOException("subscription_snapshot")
            FileOutputStream(payload, true).use { it.fd.sync() }
            val data = metadata(revision, selectedIds)
            FileOutputStream(File(directory, "selection")).use {
                it.write(data)
                it.fd.sync()
            }
            files.syncDirectory(directory)
            files.syncDirectory(root)
            return revision
        } catch (failure: Exception) {
            File(directory, "snapshot").delete()
            File(directory, "selection").delete()
            directory.delete()
            throw failure
        }
    }

    private fun metadata(revision: ResultRevision, selectedIds: Set<String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(METADATA_VERSION)
            output.writeUTF(revision.source)
            output.writeUTF(revision.selection)
            output.writeInt(selectedIds.size)
            for (id in selectedIds.sorted()) output.writeUTF(id)
        }
        val body = bytes.toByteArray()
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }

    private fun readMetadata(file: File, revision: ResultRevision): Set<String>? {
        if (!file.isFile) return null
        val length = file.length()
        if (length !in DIGEST_BYTES.toLong()..MAX_METADATA_BYTES) return null
        val bytes = ByteArray(length.toInt())
        FileInputStream(file).use { raw ->
            DataInputStream(raw).readFully(bytes)
            if (raw.read() != -1) return null
        }
        val end = bytes.size - DIGEST_BYTES
        val body = bytes.copyOfRange(0, end)
        if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), bytes.copyOfRange(end, bytes.size))) return null
        DataInputStream(ByteArrayInputStream(body)).use { input ->
            if (input.readInt() != METADATA_VERSION || input.readUTF() != revision.source || input.readUTF() != revision.selection) return null
            val count = input.readInt()
            if (count !in 0..MAX_RECORDS) return null
            val selected = LinkedHashSet<String>()
            repeat(count) {
                val id = input.readUTF()
                if (!ID_PATTERN.matches(id) || !selected.add(id)) return null
            }
            if (input.read() != -1) return null
            return selected
        }
    }

    private fun validSelection(snapshot: SubscriptionSnapshotV1, ids: Set<String>): Boolean {
        if (ids.size > MAX_RECORDS) return false
        val records = snapshot.records
        if (records.isEmpty() || records.size > MAX_RECORDS) return false
        val all = HashSet<String>()
        val supported = HashSet<String>()
        for (record in records) {
            if (!ID_PATTERN.matches(record.id) || !all.add(record.id) || record.supported != (record.reason == null)) return false
            if (record.supported) supported.add(record.id)
        }
        return supported.isNotEmpty() && supported.containsAll(ids)
    }

    private fun validRevision(revision: ResultRevision): Boolean = listOf(revision.source, revision.selection).all {
        val number = it.toLongOrNull()
        number != null && number > 0 && number.toString() == it
    }

    private fun newRevision(): String {
        var value: Long
        do { value = random.nextLong() and Long.MAX_VALUE } while (value == 0L)
        return value.toString()
    }
}
