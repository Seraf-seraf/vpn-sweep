package com.seraf.vpnsweep.resultactivation

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.seraf.vpnsweep.AcceptedSubscriptionReceiptV1
import com.seraf.vpnsweep.SearchLeaseReceiptV1
import com.seraf.vpnsweep.importer.MAX_CONFIG_BYTES
import com.seraf.vpnsweep.searchstorage.ResultManifest
import com.seraf.vpnsweep.searchstorage.ResultRevision
import com.seraf.vpnsweep.searchstorage.ResultStorage
import com.seraf.vpnsweep.searchstorage.digest
import com.seraf.vpnsweep.validIdentity
import com.seraf.vpnsweep.validRevision
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

internal enum class CleanCompletionTerminalV1 { COMPLETE }

internal data class SearchCompletionReceiptV1(
    val terminal: CleanCompletionTerminalV1,
    val search: SearchLeaseReceiptV1,
    val result: ResultManifest,
) {
    fun matchesCurrent(revision: ResultRevision, manifest: ResultManifest): Boolean =
        matchesCurrentRevision(revision) && result == manifest

    fun matchesCurrentRevision(revision: ResultRevision): Boolean =
        terminal == CleanCompletionTerminalV1.COMPLETE && validSearch(search) &&
            search.sourceRevision.toString() == revision.source &&
            search.selectionRevision.toString() == revision.selection &&
            result.revision == revision && validManifest(result)

    fun matchesAccepted(accepted: AcceptedSubscriptionReceiptV1): Boolean =
        accepted.requestGeneration > 0 && validRevision(accepted.sourceRevision) &&
            validRevision(accepted.selectionRevision) &&
            search.requestGeneration == accepted.requestGeneration &&
            search.sourceRevision.toString() == accepted.sourceRevision &&
            search.selectionRevision.toString() == accepted.selectionRevision

    companion object {
        fun cleanComplete(search: SearchLeaseReceiptV1, result: ResultManifest): SearchCompletionReceiptV1 =
            SearchCompletionReceiptV1(CleanCompletionTerminalV1.COMPLETE, search, result)

        fun validSearch(search: SearchLeaseReceiptV1): Boolean =
            search.requestGeneration > 0 && search.sessionGeneration > 0 &&
                validRevision(search.sourceRevision.toString()) && validRevision(search.selectionRevision.toString()) &&
                validIdentity(search.leaseIdentity)

        fun validManifest(manifest: ResultManifest): Boolean =
            validIdentity(manifest.generation) && validRevision(manifest.revision.source) &&
                validRevision(manifest.revision.selection) && manifest.sha256.matches(SHA256_PATTERN)
    }
}

internal fun verifiedCompletionBytes(
    storage: ResultStorage,
    receipt: SearchCompletionReceiptV1,
    revision: ResultRevision,
): ByteArray? {
    if (!receipt.matchesCurrentRevision(revision)) return null
    return try {
        val result = storage.readGeneration(receipt.result.generation) ?: return null
        result.input.use { input ->
            if (result.revision != revision || result.sha256 != receipt.result.sha256) return null
            val bytes = input.readNBytes(MAX_CONFIG_BYTES + 1)
            if (bytes.isEmpty() || bytes.size > MAX_CONFIG_BYTES ||
                digest(bytes.inputStream()) != receipt.result.sha256
            ) null else bytes
        }
    } catch (_: Exception) {
        null
    }
}

internal interface SearchCompletionReceiptStorage {
    fun read(): SearchCompletionReceiptV1?
    fun readPrepared(): SearchCompletionReceiptV1?
    fun hasPending(): Boolean
    fun prepare(receipt: SearchCompletionReceiptV1): Boolean
    fun commit(): Boolean
    fun safeForUpgrade(candidate: ResultRevision?, verify: (SearchCompletionReceiptV1) -> Boolean): Boolean = false

    fun write(receipt: SearchCompletionReceiptV1): Boolean = prepare(receipt) && commit()
}

internal class PrivateSearchCompletionReceiptStorage(
    root: File,
    private val atomicMove: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
    private val directorySync: (File) -> Unit = ::syncDirectory,
) : SearchCompletionReceiptStorage {
    private val directory = root
    private val receiptFile = File(directory, "completion")
    private val previousFile = File(directory, "completion.previous")
    private val pendingFile = File(directory, "completion.pending")
    private var commitUncertain = false

    override fun safeForUpgrade(candidate: ResultRevision?, verify: (SearchCompletionReceiptV1) -> Boolean): Boolean {
        return try {
        if (commitUncertain) return false
        val root = attributes(directory)
        if (root == null) return true
        if (!root.isDirectory || attributes(pendingFile) != null) return false
        val current = attributes(receiptFile)
        val previous = attributes(previousFile)
        if (current == null && previous != null) return false
        for ((file, attributes) in listOf(receiptFile to current, previousFile to previous)) {
            if (attributes != null) {
                if (!attributes.isRegularFile) return false
                val receipt = readRecord(file) ?: return false
                if (!receipt.matchesCurrentRevision(receipt.result.revision)) return false
                if (candidate != null && receipt.result.revision == candidate) return false
                if (!verify(receipt)) return false
            }
        }
        true
        } catch (_: Exception) { false }
    }

    private fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    override fun read(): SearchCompletionReceiptV1? {
        return try {
            if (commitUncertain) return null
            if (pendingFile.exists()) {
                if (!validPending()) return null
                readRecord(previousFile)
            } else {
                readRecord(receiptFile)
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun readPrepared(): SearchCompletionReceiptV1? {
        if (!pendingFile.exists() || !validPending()) return null
        return try { readRecord(receiptFile) } catch (_: Exception) { null }
    }

    override fun hasPending(): Boolean = commitUncertain || pendingFile.exists()

    override fun prepare(receipt: SearchCompletionReceiptV1): Boolean {
        if (commitUncertain) return false
        if (receipt.terminal != CleanCompletionTerminalV1.COMPLETE ||
            !SearchCompletionReceiptV1.validSearch(receipt.search) ||
            !SearchCompletionReceiptV1.validManifest(receipt.result)
        ) return false

        try {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return false
            if (pendingFile.exists()) {
                if (read() == null) {
                    if (previousFile.exists() || !validPending()) return false
                    val candidate = readRecordBytes(receiptFile)
                    if (candidate != null && decode(candidate) != null) {
                        if (!writeAtomically(previousFile, candidate)) return false
                        directorySync(directory)
                    }
                }
            } else if (receiptFile.exists()) {
                val previousBytes = readRecordBytes(receiptFile) ?: return false
                if (decode(previousBytes) == null) return false
                if (!writeAtomically(previousFile, previousBytes)) return false
                directorySync(directory)
            } else if (previousFile.exists()) {
                return false
            }
            if (!pendingFile.exists() && !writeAtomically(pendingFile, PENDING_RECORD)) return false
            directorySync(directory)
            if (!writeAtomically(receiptFile, encode(receipt))) return false
            directorySync(directory)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    override fun commit(): Boolean {
        if (!pendingFile.exists()) {
            if (!commitUncertain || readRecord(receiptFile) == null) return false
            return try {
                directorySync(directory)
                commitUncertain = false
                true
            } catch (_: Exception) {
                false
            }
        }
        if (!validPending() || readRecord(receiptFile) == null) return false
        if (!pendingFile.delete()) return false
        return try {
            directorySync(directory)
            commitUncertain = false
            true
        } catch (_: Exception) {
            commitUncertain = true
            try {
                writeAtomically(pendingFile, PENDING_RECORD)
                directorySync(directory)
                commitUncertain = false
            } catch (_: Exception) { }
            false
        }
    }

    private fun readRecord(file: File): SearchCompletionReceiptV1? {
        val bytes = readRecordBytes(file) ?: return null
        return decode(bytes)
    }

    private fun readRecordBytes(file: File): ByteArray? {
        val length = file.length()
        if (length < MIN_SEARCH_COMPLETION_RECEIPT_BYTES.toLong() ||
            length > MAX_SEARCH_COMPLETION_RECEIPT_BYTES.toLong()
        ) return null
        return FileInputStream(file).use { input ->
            val bytes = input.readNBytes(MAX_SEARCH_COMPLETION_RECEIPT_BYTES + 1)
            if (bytes.size.toLong() != length || input.read() != -1) null else bytes
        }
    }

    private fun decode(bytes: ByteArray): SearchCompletionReceiptV1? {
        if (bytes.size < MIN_SEARCH_COMPLETION_RECEIPT_BYTES || bytes.size > MAX_SEARCH_COMPLETION_RECEIPT_BYTES) return null
        val bodyLength = bytes.size - CHECKSUM_BYTES
        val body = bytes.copyOfRange(0, bodyLength)
        val checksum = bytes.copyOfRange(bodyLength, bytes.size)
        if (!MessageDigest.isEqual(checksum, digest(body))) return null
        return DataInputStream(ByteArrayInputStream(body)).use { input ->
            if (input.readInt() != RECORD_MAGIC || input.readInt() != RECORD_VERSION ||
                input.readUnsignedByte() != CLEAN_COMPLETE
            ) return null
            val search = SearchLeaseReceiptV1(
                requestGeneration = input.readLong(),
                sessionGeneration = input.readLong(),
                sourceRevision = input.readLong(),
                selectionRevision = input.readLong(),
                leaseIdentity = input.readUTF(),
            )
            val manifest = ResultManifest(
                generation = input.readUTF(),
                revision = ResultRevision(input.readUTF(), input.readUTF()),
                sha256 = input.readUTF(),
            )
            if (input.read() != -1) return null
            SearchCompletionReceiptV1.cleanComplete(search, manifest)
                .takeIf { SearchCompletionReceiptV1.validSearch(search) && SearchCompletionReceiptV1.validManifest(manifest) }
        }
    }

    private fun validPending(): Boolean = FileInputStream(pendingFile).use { input ->
        input.readNBytes(PENDING_RECORD.size + 1).contentEquals(PENDING_RECORD)
    }

    private fun encode(receipt: SearchCompletionReceiptV1): ByteArray {
        val bodyOutput = ByteArrayOutputStream()
        DataOutputStream(bodyOutput).use { data ->
            data.writeInt(RECORD_MAGIC)
            data.writeInt(RECORD_VERSION)
            data.writeByte(CLEAN_COMPLETE)
            data.writeLong(receipt.search.requestGeneration)
            data.writeLong(receipt.search.sessionGeneration)
            data.writeLong(receipt.search.sourceRevision)
            data.writeLong(receipt.search.selectionRevision)
            data.writeUTF(receipt.search.leaseIdentity)
            data.writeUTF(receipt.result.generation)
            data.writeUTF(receipt.result.revision.source)
            data.writeUTF(receipt.result.revision.selection)
            data.writeUTF(receipt.result.sha256)
        }
        val body = bodyOutput.toByteArray()
        return body + digest(body)
    }

    private fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        var restore: File? = null
        try {
            restore = File(directory, "completion-${UUID.randomUUID()}.tmp")
            FileOutputStream(restore).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            atomicMove(restore.toPath(), target.toPath())
            return true
        } catch (_: Exception) {
            return false
        } finally {
            restore?.delete()
        }
    }

}

private fun syncDirectory(target: File): Unit {
    try {
        val fd = Os.open(target.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw IOException("directory_invalid")
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    } catch (failure: ErrnoException) {
        throw IOException("directory_sync", failure)
    }
}

private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private const val RECORD_MAGIC = 0x56505343
private const val RECORD_VERSION = 1
private const val CLEAN_COMPLETE = 1
private const val CHECKSUM_BYTES = 32
private const val RECORD_HEADER_BYTES = 4 + 4 + 1
private const val LONG_FIELDS_BYTES = 4 * 8
private const val UTF_LENGTH_PREFIX_BYTES = 2
private const val UUID_TEXT_CHARS = 36
private const val MIN_REVISION_TEXT_CHARS = 1
private const val MAX_REVISION_TEXT_CHARS = 19
private const val SHA256_HEX_CHARS = 64
private const val UUID_FIELD_BYTES = UTF_LENGTH_PREFIX_BYTES + UUID_TEXT_CHARS
private const val MIN_REVISION_FIELD_BYTES = UTF_LENGTH_PREFIX_BYTES + MIN_REVISION_TEXT_CHARS
private const val MAX_REVISION_FIELD_BYTES = UTF_LENGTH_PREFIX_BYTES + MAX_REVISION_TEXT_CHARS
private const val SHA256_FIELD_BYTES = UTF_LENGTH_PREFIX_BYTES + SHA256_HEX_CHARS
private const val MIN_SEARCH_COMPLETION_RECEIPT_BYTES =
    RECORD_HEADER_BYTES + LONG_FIELDS_BYTES + UUID_FIELD_BYTES * 2 + MIN_REVISION_FIELD_BYTES * 2 + SHA256_FIELD_BYTES + CHECKSUM_BYTES
private const val MAX_SEARCH_COMPLETION_RECEIPT_BYTES =
    RECORD_HEADER_BYTES + LONG_FIELDS_BYTES + UUID_FIELD_BYTES * 2 + MAX_REVISION_FIELD_BYTES * 2 + SHA256_FIELD_BYTES + CHECKSUM_BYTES
private const val PENDING_MAGIC = 0x56505350
private const val PENDING_VERSION = 1
private val PENDING_RECORD = byteArrayOf(
    (PENDING_MAGIC ushr 24).toByte(), (PENDING_MAGIC ushr 16).toByte(),
    (PENDING_MAGIC ushr 8).toByte(), PENDING_MAGIC.toByte(), PENDING_VERSION.toByte(),
)
private val SHA256_PATTERN = Regex("[0-9a-f]{$SHA256_HEX_CHARS}")
