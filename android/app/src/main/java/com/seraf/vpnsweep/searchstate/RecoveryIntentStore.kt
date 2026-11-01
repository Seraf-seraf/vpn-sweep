package com.seraf.vpnsweep.searchstate

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

internal class RecoveryIntentStore(
    private val directory: File,
    private val syncDirectory: (File) -> Unit,
) {
    private val intent = File(directory, "intent")
    private val arming = File(directory, "arming")
    private var confirmedDone: String? = null
    private var confirmedMissingDirectory = false

    @Synchronized
    fun read(): RecoveryIntentState {
        return try {
        val root = attributes(directory)
        if (root == null) {
            if (!confirmedMissingDirectory) {
                syncDirectory(directory.parentFile ?: return RecoveryIntentState.Unknown)
                if (attributes(directory) != null) return RecoveryIntentState.Unknown
                confirmedMissingDirectory = true
            }
            RecoveryIntentState.Absent
        } else if (!root.isDirectory || attributes(arming) != null) {
            RecoveryIntentState.Unknown
        } else {
            confirmedMissingDirectory = false
            val supervised = readSupervisorRecord()
            if (supervised != null) {
                if (supervised.state == PENDING) {
                    confirmedDone = null
                    return RecoveryIntentState.SupervisedPending
                }
                if (confirmedDone != supervised.id) {
                    syncDirectory(directory)
                    if (attributes(arming) != null || readSupervisorRecord() != supervised) return RecoveryIntentState.Unknown
                    confirmedDone = supervised.id
                }
                return RecoveryIntentState.Done
            }
            val record = readRecord() ?: return RecoveryIntentState.Unknown
            if (record.state == ARMED) {
                confirmedDone = null
                RecoveryIntentState.Armed(record.stamp)
            } else {
                if (confirmedDone != record.id) {
                    syncDirectory(directory)
                    if (attributes(arming) != null || readRecord() != record) return RecoveryIntentState.Unknown
                    confirmedDone = record.id
                }
                RecoveryIntentState.Done
            }
        }
        } catch (_: Exception) { RecoveryIntentState.Unknown }
    }

    @Synchronized
    fun prepareSupervisorRecovery(): Boolean {
        return try {
            var created = false
            val root = attributes(directory)
            if (root == null) {
                Files.createDirectory(directory.toPath())
                syncDirectory(directory.parentFile ?: return false)
                created = true
            } else if (!root.isDirectory) return false
            confirmedDone = null
            confirmedMissingDirectory = false
            val creating = attributes(arming)
            if (creating != null) {
                if (!creating.isRegularFile || creating.size() != 0L) return false
                val pending = readSupervisorRecord() ?: return false
                if (pending.state != PENDING) return false
                syncDirectory(directory)
                if (readSupervisorRecord() != pending) return false
                Files.delete(arming.toPath())
                syncDirectory(directory)
                return read() == RecoveryIntentState.SupervisedPending
            }
            val previous = if (created) RecoveryIntentState.Absent else read()
            if (previous == RecoveryIntentState.SupervisedPending) return true
            if (previous !is RecoveryIntentState.Armed && previous != RecoveryIntentState.Done && previous != RecoveryIntentState.Absent) return false
            Files.createFile(arming.toPath())
            syncDirectory(directory)
            val record = SupervisorRecord(PENDING, UUID.randomUUID().toString())
            if (!writeSupervisorRecord(record) || readSupervisorRecord() != record) return false
            Files.delete(arming.toPath())
            syncDirectory(directory)
            read() == RecoveryIntentState.SupervisedPending
        } catch (_: Exception) { false }
    }

    @Synchronized
    fun finishSupervisorRecovery(): Boolean {
        return try {
            if (attributes(arming) != null) return false
            val previous = readSupervisorRecord() ?: return false
            if (previous.state != PENDING) return false
            confirmedDone = null
            val done = previous.copy(state = SUPERVISOR_DONE)
            writeSupervisorRecord(done) && read() == RecoveryIntentState.Done
        } catch (_: Exception) { false }
    }

    private fun writeSupervisorRecord(record: SupervisorRecord): Boolean {
        val body = "$SUPERVISOR_FORMAT\n${record.state}\n${record.id}\n".toByteArray(StandardCharsets.US_ASCII)
        val payload = body + hex(MessageDigest.getInstance("SHA-256").digest(body)).toByteArray(StandardCharsets.US_ASCII) + byteArrayOf('\n'.code.toByte())
        val temporary = File(directory, "pending-${UUID.randomUUID()}")
        Files.createFile(temporary.toPath())
        FileOutputStream(temporary).use { output -> output.write(payload); output.fd.sync() }
        Files.move(temporary.toPath(), intent.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(directory)
        return readSupervisorRecord() == record
    }

    private fun readSupervisorRecord(): SupervisorRecord? {
        val value = attributes(intent) ?: return null
        if (!value.isRegularFile || value.size() !in 1..MAX_INTENT_BYTES.toLong()) return null
        val bytes = FileInputStream(intent).use { it.readNBytes(MAX_INTENT_BYTES + 1) }
        if (bytes.size.toLong() != value.size() || bytes.any { it.toInt() < 0 }) return null
        val lines = String(bytes, StandardCharsets.US_ASCII).split('\n')
        if (lines.size != 5 || lines[0] != SUPERVISOR_FORMAT || lines[1] !in setOf(PENDING, SUPERVISOR_DONE) || lines[4].isNotEmpty()) return null
        if (!SUPERVISOR_ID.matches(lines[2])) return null
        val body = "${lines[0]}\n${lines[1]}\n${lines[2]}\n".toByteArray(StandardCharsets.US_ASCII)
        if (lines[3] != hex(MessageDigest.getInstance("SHA-256").digest(body))) return null
        return SupervisorRecord(lines[1], lines[2])
    }

    @Synchronized
    fun create(stamp: RecoveryStamp): Boolean {
        return try {
            if (!stamp.valid()) return false
            val root = attributes(directory)
            val previous = if (root == null) {
                Files.createDirectory(directory.toPath())
                syncDirectory(directory.parentFile ?: return false)
                null
            } else {
                if (!root.isDirectory || read() != RecoveryIntentState.Done) return false
                readRecord() ?: return false
            }
            confirmedDone = null
            confirmedMissingDirectory = false
            Files.createFile(arming.toPath())
            syncDirectory(directory)
            var id: String
            do { id = UUID.randomUUID().toString() } while (id == previous?.id)
            val record = Record(ARMED, id, stamp)
            if (!writeRecord(record) || readRecord() != record) return false
            Files.delete(arming.toPath())
            syncDirectory(directory)
            read() == RecoveryIntentState.Armed(stamp)
        } catch (_: Exception) { false }
    }

    // DONE is kept as a durable witness; an interrupted replacement remains blocked.
    @Synchronized
    fun finish(): Boolean {
        return try {
            if (attributes(arming) != null) return false
            val record = readRecord() ?: return false
            if (record.state != ARMED) return false
            confirmedDone = null
            val done = record.copy(state = DONE)
            if (!writeRecord(done)) return false
            read() == RecoveryIntentState.Done
        } catch (_: Exception) { false }
    }

    private fun writeRecord(record: Record): Boolean {
        val body = "$FORMAT\n${record.state}\n${record.id}\n${record.stamp.bootId}\n".toByteArray(StandardCharsets.US_ASCII)
        val payload = body + hex(MessageDigest.getInstance("SHA-256").digest(body)).toByteArray(StandardCharsets.US_ASCII) + byteArrayOf('\n'.code.toByte())
        val temporary = File(directory, "pending-${UUID.randomUUID()}")
        Files.createFile(temporary.toPath())
        FileOutputStream(temporary).use { output -> output.write(payload); output.fd.sync() }
        Files.move(temporary.toPath(), intent.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(directory)
        return readRecord() == record
    }

    private fun readRecord(): Record? {
        val value = attributes(intent) ?: return null
        if (!value.isRegularFile || value.size() !in 1..MAX_INTENT_BYTES.toLong()) return null
        val bytes = FileInputStream(intent).use { it.readNBytes(MAX_INTENT_BYTES + 1) }
        if (bytes.size.toLong() != value.size() || bytes.any { it.toInt() < 0 }) return null
        val lines = String(bytes, StandardCharsets.US_ASCII).split('\n')
        if (lines.size != 6 || lines[0] != FORMAT || lines[1] !in setOf(ARMED, DONE) || lines[5].isNotEmpty()) return null
        if (!RecoveryStamp(lines[2]).valid()) return null
        val stamp = RecoveryStamp(lines[3])
        if (!stamp.valid()) return null
        val body = "${lines[0]}\n${lines[1]}\n${lines[2]}\n${lines[3]}\n".toByteArray(StandardCharsets.US_ASCII)
        if (lines[4] != hex(MessageDigest.getInstance("SHA-256").digest(body))) return null
        return Record(lines[1], lines[2], stamp)
    }

    private fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private data class Record(val state: String, val id: String, val stamp: RecoveryStamp)
    private data class SupervisorRecord(val state: String, val id: String)

    private companion object {
        const val FORMAT = "upgrade-recovery-v2"
        const val SUPERVISOR_FORMAT = "supervisor-recovery-v1"
        const val PENDING = "PENDING"
        const val SUPERVISOR_DONE = "DONE"
        val SUPERVISOR_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        const val ARMED = "ARMED"
        const val DONE = "DONE"
        const val MAX_INTENT_BYTES = 256
    }
}

internal fun readKernelBootId(): RecoveryStamp? = try {
    val bytes = FileInputStream("/proc/sys/kernel/random/boot_id").use { it.readNBytes(38) }
    parseKernelBootId(bytes)
} catch (_: Exception) { null }

internal fun parseKernelBootId(bytes: ByteArray): RecoveryStamp? {
    if (bytes.size != 37 || bytes[36] != '\n'.code.toByte()) return null
    if (bytes.any { it.toInt() < 0 }) return null
    return RecoveryStamp(String(bytes, 0, 36, StandardCharsets.US_ASCII)).takeIf { it.valid() }
}
