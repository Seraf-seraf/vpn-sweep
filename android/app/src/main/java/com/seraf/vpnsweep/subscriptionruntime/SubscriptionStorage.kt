package com.seraf.vpnsweep.subscriptionruntime

import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionSnapshotV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionRecordV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionRejectV1
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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

private const val MAX_RECORDS = 10_000
private const val MAX_METADATA_BYTES = 400_000L
private const val MAX_SNAPSHOT_BYTES = 44L + 8L * 1024L * 1024L + MAX_RECORDS * 49L
private const val METADATA_VERSION = 2
private const val LEGACY_METADATA_VERSION = 1
private const val LEGACY_REQUEST_GENERATION = 1L
private const val DIGEST_BYTES = 32
private const val MAX_DISPLAY_BYTES = 16 * 1024 * 1024
private const val DISPLAY_VERSION = 1
private val ID_PATTERN = Regex("[0-9a-f]{32}")

internal class RecoveredSubscription(
    val snapshot: SubscriptionSnapshotV1,
    val selectedIds: Set<String>,
    val revision: ResultRevision,
    val requestGeneration: Long,
)

internal class RecoveredSubscriptionDisplay(
    val records: List<SubscriptionRecordV1>,
    val selectedIds: Set<String>,
    val revision: ResultRevision,
    val requestGeneration: Long,
)

private class StoredSelection(val selectedIds: Set<String>, val requestGeneration: Long)
private class SnapshotEntry(val id: String, val supported: Boolean)

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

    fun commitSource(snapshot: SubscriptionSnapshotV1, requestGeneration: Long): ResultRevision {
        if (requestGeneration <= 0) throw IOException("subscription_generation")
        check(snapshot.records.any { it.supported }) { "subscription_snapshot" }
        return store(snapshot, emptySet(), null, requestGeneration)
    }

    fun commitSelection(selectedIds: Set<String>): ResultRevision {
        val previous = currentRevision() ?: throw IOException("subscription_revision")
        val recovered = recover(previous) ?: throw IOException("subscription_revision")
        try {
            if (!validSelection(recovered.snapshot, selectedIds) || selectedIds.isEmpty()) {
                throw IOException("subscription_selection")
            }
            return store(recovered.snapshot, selectedIds, previous.source, recovered.requestGeneration)
        } finally {
            recovered.snapshot.close()
        }
    }

    fun prepareSelection(expected: ResultRevision, records: List<SubscriptionRecordV1>, selectedIds: Set<String>): ResultRevision {
        if (currentRevision() != expected || !validRevision(expected) || selectedIds.isEmpty() ||
            !validSelection(records, selectedIds)
        ) throw IOException("subscription_selection")
        val directory = File(root, "${expected.source}-${expected.selection}")
        val previous = readMetadata(File(directory, "selection"), expected) ?: throw IOException("subscription_selection")
        if (!validSelection(records, previous.selectedIds)) throw IOException("subscription_selection")
        val payload = File(directory, "snapshot")
        if (!validSnapshotEnvelope(payload, records)) throw IOException("subscription_snapshot")
        val candidate = store(null, selectedIds, expected.source, previous.requestGeneration, payload, records)
        val copied = File(root, "${candidate.source}-${candidate.selection}/snapshot")
        if (!validSnapshotEnvelope(copied, records)) throw IOException("subscription_snapshot")
        return candidate
    }

    fun reissueCurrent(expected: ResultRevision): ResultRevision {
        if (currentRevision() != expected) throw IOException("subscription_revision")
        val recovered = recover(expected) ?: throw IOException("subscription_revision")
        try {
            val payload = File(root, "${expected.source}-${expected.selection}/snapshot")
            val attributes = java.nio.file.Files.readAttributes(
                payload.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS,
            )
            if (!attributes.isRegularFile || attributes.size() !in 1..MAX_SNAPSHOT_BYTES) throw IOException("subscription_snapshot")
            val revision = store(recovered.snapshot, recovered.selectedIds, expected.source, recovered.requestGeneration, payload)
            val verified = recover(revision) ?: throw IOException("subscription_snapshot")
            try {
                if (verified.selectedIds != recovered.selectedIds || verified.requestGeneration != recovered.requestGeneration) {
                    throw IOException("subscription_snapshot")
                }
            } finally {
                verified.snapshot.close()
            }
            return revision
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
            if (!snapshot.records.any { it.supported } || !validSelection(snapshot, metadata.selectedIds)) {
                snapshot.close()
                return null
            }
            return RecoveredSubscription(snapshot, metadata.selectedIds, revision, metadata.requestGeneration)
        } catch (_: Exception) {
            try {
                snapshot?.close()
            } catch (_: Exception) {
            }
            return null
        }
    }

    fun recoverDisplay(revision: ResultRevision): RecoveredSubscriptionDisplay? {
        if (!validRevision(revision)) return null
        return try {
            val directory = File(root, "${revision.source}-${revision.selection}")
            val selection = readMetadata(File(directory, "selection"), revision) ?: return null
            val payload = File(directory, "snapshot")
            val records = readDisplay(File(directory, "display"), revision, payload) ?: return null
            if (!validSelection(records, selection.selectedIds) || !validSnapshotEnvelope(payload, records)) return null
            RecoveredSubscriptionDisplay(records, selection.selectedIds, revision, selection.requestGeneration)
        } catch (_: Exception) { null }
    }

    fun isCurrentSelection(revision: ResultRevision, selectedIds: Set<String>): Boolean {
        if (currentRevision() != revision) return false
        val display = recoverDisplay(revision) ?: return false
        return display.selectedIds == selectedIds && currentRevision() == revision
    }

    fun legacyDisplayAbsent(revision: ResultRevision): Boolean {
        if (!validRevision(revision) || currentRevision() != revision) return false
        return try {
            val directory = File(root, "${revision.source}-${revision.selection}")
            val attributes = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isDirectory) return false
            try {
                Files.readAttributes(File(directory, "display").toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                false
            } catch (_: NoSuchFileException) {
                currentRevision() == revision
            }
        } catch (_: Exception) { false }
    }

    fun cacheDisplay(recovered: RecoveredSubscription): Boolean {
        return try {
            val revision = recovered.revision
            if (currentRevision() != revision || !validSelection(recovered.snapshot, recovered.selectedIds)) return false
            val directory = File(root, "${revision.source}-${revision.selection}")
            val target = File(directory, "display")
            val existing = try {
                Files.readAttributes(target.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                true
            } catch (_: NoSuchFileException) { false }
            if (existing) return recoverDisplay(revision) != null
            val temporary = File(directory, "display-${UUID.randomUUID()}")
            Files.createFile(temporary.toPath())
            FileOutputStream(temporary).use { output ->
                output.write(display(revision, recovered.snapshot.records, fileDigest(File(directory, "snapshot"))))
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            files.syncDirectory(directory)
            recoverDisplay(revision) != null
        } catch (_: Exception) { false }
    }

    private fun store(
        snapshot: SubscriptionSnapshotV1?,
        selectedIds: Set<String>,
        source: String?,
        requestGeneration: Long,
        copyFrom: File? = null,
        records: List<SubscriptionRecordV1> = requireNotNull(snapshot).records,
    ): ResultRevision {
        if (requestGeneration <= 0) throw IOException("subscription_generation")
        if (!validSelection(records, selectedIds)) throw IOException("subscription_selection")
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
            if (copyFrom == null) {
                files.write(requireNotNull(snapshot), payload)
            } else {
                FileInputStream(copyFrom).use { input -> FileOutputStream(payload).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_SNAPSHOT_BYTES) throw IOException("subscription_snapshot")
                        output.write(buffer, 0, count)
                    }
                } }
                if (copyFrom.length() != payload.length() ||
                    !MessageDigest.isEqual(fileDigest(copyFrom), fileDigest(payload))) throw IOException("subscription_snapshot")
            }
            if (!payload.isFile || payload.length() !in 1..MAX_SNAPSHOT_BYTES) throw IOException("subscription_snapshot")
            FileOutputStream(payload, true).use { it.fd.sync() }
            val data = metadata(revision, selectedIds, requestGeneration)
            FileOutputStream(File(directory, "selection")).use {
                it.write(data)
                it.fd.sync()
            }
            val display = display(revision, records, fileDigest(payload))
            FileOutputStream(File(directory, "display")).use {
                it.write(display)
                it.fd.sync()
            }
            files.syncDirectory(directory)
            files.syncDirectory(root)
            return revision
        } catch (failure: Exception) {
            File(directory, "snapshot").delete()
            File(directory, "selection").delete()
            File(directory, "display").delete()
            directory.delete()
            throw failure
        }
    }

    private fun fileDigest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        FileInputStream(file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_SNAPSHOT_BYTES) throw IOException("subscription_snapshot")
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun display(revision: ResultRevision, records: List<SubscriptionRecordV1>, snapshotDigest: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { output ->
            output.writeInt(DISPLAY_VERSION)
            output.writeUTF(revision.source)
            output.writeUTF(revision.selection)
            output.write(snapshotDigest)
            output.writeInt(records.size)
            for (record in records) {
                output.writeUTF(record.id)
                output.writeBoolean(record.supported)
                for (text in listOf(record.title, record.subtitle)) {
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    if (bytes.size > MAX_DISPLAY_BYTES || body.size().toLong() + bytes.size + 4 > MAX_DISPLAY_BYTES) {
                        throw IOException("subscription_display")
                    }
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
            }
        }
        val bytes = body.toByteArray()
        if (bytes.size > MAX_DISPLAY_BYTES - DIGEST_BYTES) throw IOException("subscription_display")
        return bytes + MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    private fun readDisplay(file: File, revision: ResultRevision, snapshot: File): List<SubscriptionRecordV1>? {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.size() !in DIGEST_BYTES.toLong()..MAX_DISPLAY_BYTES.toLong()) return null
        val bytes = FileInputStream(file).use { it.readNBytes(MAX_DISPLAY_BYTES + 1) }
        if (bytes.size.toLong() != attributes.size()) return null
        val body = bytes.copyOfRange(0, bytes.size - DIGEST_BYTES)
        if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), bytes.copyOfRange(body.size, bytes.size))) return null
        val input = DataInputStream(ByteArrayInputStream(body))
        if (input.readInt() != DISPLAY_VERSION || input.readUTF() != revision.source || input.readUTF() != revision.selection) return null
        val digest = ByteArray(DIGEST_BYTES)
        input.readFully(digest)
        if (!MessageDigest.isEqual(digest, fileDigest(snapshot))) return null
        val count = input.readInt()
        if (count !in 1..MAX_RECORDS) return null
        val records = ArrayList<SubscriptionRecordV1>(count)
        repeat(count) {
            val id = input.readUTF()
            val supported = input.readBoolean()
            fun readText(): String {
                val size = input.readInt()
                if (size !in 0..MAX_DISPLAY_BYTES || size > input.available()) throw IOException("subscription_display")
                val raw = ByteArray(size)
                input.readFully(raw)
                val value = String(raw, Charsets.UTF_8)
                if (!value.toByteArray(Charsets.UTF_8).contentEquals(raw)) throw IOException("subscription_display")
                return value
            }
            val title = readText()
            val subtitle = readText()
            records.add(SubscriptionRecordV1(id, title, subtitle, supported, if (supported) null else SubscriptionRejectV1.UNSUPPORTED_RECORD))
        }
        return records.takeIf { input.read() == -1 }
    }

    private fun metadata(revision: ResultRevision, selectedIds: Set<String>, requestGeneration: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(METADATA_VERSION)
            output.writeUTF(revision.source)
            output.writeUTF(revision.selection)
            output.writeLong(requestGeneration)
            output.writeInt(selectedIds.size)
            for (id in selectedIds.sorted()) output.writeUTF(id)
        }
        val body = bytes.toByteArray()
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }

    private fun readMetadata(file: File, revision: ResultRevision): StoredSelection? {
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
            val version = input.readInt()
            if (version != METADATA_VERSION && version != LEGACY_METADATA_VERSION) return null
            if (input.readUTF() != revision.source || input.readUTF() != revision.selection) return null
            val requestGeneration = if (version == METADATA_VERSION) {
                input.readLong().takeIf { it > 0 } ?: return null
            } else {
                LEGACY_REQUEST_GENERATION
            }
            val count = input.readInt()
            if (count !in 0..MAX_RECORDS) return null
            val selected = LinkedHashSet<String>()
            repeat(count) {
                val id = input.readUTF()
                if (!ID_PATTERN.matches(id) || !selected.add(id)) return null
            }
            if (input.read() != -1) return null
            return StoredSelection(selected, requestGeneration)
        }
    }

    private fun validSelection(snapshot: SubscriptionSnapshotV1, ids: Set<String>): Boolean = validSelection(snapshot.records, ids)

    private fun validSelection(records: List<SubscriptionRecordV1>, ids: Set<String>): Boolean {
        if (ids.size > MAX_RECORDS) return false
        if (records.isEmpty() || records.size > MAX_RECORDS) return false
        val all = HashSet<String>()
        val supported = HashSet<String>()
        for (record in records) {
            if (!ID_PATTERN.matches(record.id) || !all.add(record.id) || record.supported != (record.reason == null)) return false
            if (record.supported) supported.add(record.id)
        }
        return supported.isNotEmpty() && supported.containsAll(ids)
    }

    private fun validSnapshotEnvelope(file: File, records: List<SubscriptionRecordV1>): Boolean {
        val indexed = snapshotIndex(file) ?: return false
        return indexed.size == records.size && indexed.indices.all { index ->
            indexed[index].id == records[index].id && indexed[index].supported == records[index].supported
        }
    }

    private fun snapshotIndex(file: File): List<SnapshotEntry>? {
        return try {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.size() !in 44..MAX_SNAPSHOT_BYTES) return null
        val bytes = FileInputStream(file).use { it.readNBytes((MAX_SNAPSHOT_BYTES + 1).toInt()) }
        if (bytes.size.toLong() != attributes.size()) return null
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readInt() != 0x53565331) return null
        val rawLength = input.readInt()
        val count = input.readInt()
        if (rawLength !in 1..(8 * 1024 * 1024) || count !in 1..MAX_RECORDS ||
            44L + rawLength + count.toLong() * 49 != bytes.size.toLong()
        ) return null
        val expected = ByteArray(DIGEST_BYTES)
        input.readFully(expected)
        if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(44, bytes.size)))) return null
        var offset = 44 + rawLength
        val records = ArrayList<SnapshotEntry>(count)
        val seen = HashSet<String>()
        repeat(count) {
            val id = bytes.copyOfRange(offset, offset + 16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val supportedByte = bytes[offset + 16].toInt()
            if (!seen.add(id) || supportedByte !in 0..1) return null
            val supported = supportedByte == 1
            records.add(SnapshotEntry(id, supported))
            offset += 49
        }
        records
        } catch (_: Exception) { null }
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
